package cn.jia.agent.service.impl;

import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallPrivateMarkDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.HallPrivateMarkEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.HallPrivateMarkService;
import cn.jia.agent.service.HallRequestDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HallPrivateMarkServiceImplTest {
    private static final HallRequestDraftService.OwnerScope OWNER = new HallRequestDraftService.OwnerScope("0","c","o");
    private HallPrivateMarkDao marks;
    private PersonalWorkspaceExecutionDao executions;
    private HallRequestDraftService results;
    private HallPrivateMarkService service;
    private PersonalWorkspaceExecutionEntity execution;
    @BeforeEach void setUp() {
        marks=mock(HallPrivateMarkDao.class);executions=mock(PersonalWorkspaceExecutionDao.class);results=mock(HallRequestDraftService.class);
        service=new HallPrivateMarkServiceImpl(marks,mock(HallPrivateCaseDao.class),executions,results,()->1000L);
        execution=new PersonalWorkspaceExecutionEntity().setExecutionId("e").setOwnerJiacn("o").setExecutionMode("PRIVATE")
                .setExecutionState("OUTPUT_COMMITTED").setCreatedAt(100L);
        execution.setTenantId("0");execution.setClientId("c");execution.setUpdateTime(200L);
        when(executions.find("0","c","o","e")).thenReturn(execution);
        when(executions.lock("0","c","o","e")).thenReturn(execution);
        when(marks.insert(any())).thenReturn(1);
        when(results.getExecutionResults(OWNER,"e")).thenReturn(new HallRequestDraftService.ExecutionResultsView(
                "e","OUTPUT_COMMITTED","pwe_m_fixed",List.of(new HallRequestDraftService.ResultItemView(
                "out","file",1,"application/pdf","result.pdf",123,"a".repeat(64),"AVAILABLE")),List.of("VIEW")));
    }
    @Test void sourceAndReceiptWritesShareReadCommittedTransactionWhileGetIsReadOnly() throws Exception {
        var mutation=HallPrivateMarkServiceImpl.class.getMethod("mark",HallRequestDraftService.OwnerScope.class,
                String.class,String.class,HallPrivateMarkService.Command.class,String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Isolation.READ_COMMITTED,mutation.isolation());
        assertFalse(mutation.readOnly());
        assertTrue(List.of(mutation.rollbackFor()).contains(Exception.class));
        assertTrue(HallPrivateMarkServiceImpl.class.getMethod("get",HallRequestDraftService.OwnerScope.class,String.class,String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly());
    }
    @Test void fixedManifestIsServerCheckedAndMarkNeverMutatesAnExecution() {
        var reference=new HallPrivateMarkService.ResultRef("e","pwe_m_fixed");
        var view=service.mark(OWNER,"LEGACY_EXECUTION","e",new HallPrivateMarkService.Command(0,true,reference),"key");
        assertEquals(1,view.revision());assertEquals(reference,view.viewedResultRef());assertTrue(view.archived());
        verify(results).getExecutionResults(OWNER,"e");verify(executions,never()).update(any());
        verify(marks).insert(argThat(row->"e".equals(row.getSnapshotExecutionId()) && row.getSnapshotUpdatedAt()==200L));
    }
    @Test void fabricatedManifestWrongRunOrUnreadableResultCannotBeMarkedSeen() {
        for (var ref:List.of(new HallPrivateMarkService.ResultRef("e","forged"),new HallPrivateMarkService.ResultRef("other","pwe_m_fixed"))) {
            assertThrows(HallRequestDraftService.Failure.class,()->service.mark(OWNER,"LEGACY_EXECUTION","e",new HallPrivateMarkService.Command(0,false,ref),"key"));
        }
        when(results.getExecutionResults(OWNER,"e")).thenThrow(new HallRequestDraftService.Failure(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE));
        assertThrows(HallRequestDraftService.Failure.class,()->service.mark(OWNER,"LEGACY_EXECUTION","e",new HallPrivateMarkService.Command(0,false,new HallPrivateMarkService.ResultRef("e","pwe_m_fixed")),"key"));
        verify(marks,never()).insert(any());
    }
    @Test void nonResultsAreArchivableButCannotBeMarkedViewedAndTaskDomainIsRejected() {
        execution.setExecutionState("QUEUED");
        assertThrows(HallRequestDraftService.Failure.class,()->service.mark(OWNER,"LEGACY_EXECUTION","e",new HallPrivateMarkService.Command(0,false,new HallPrivateMarkService.ResultRef("e","pwe_m_fixed")),"key"));
        assertTrue(service.mark(OWNER,"LEGACY_EXECUTION","e",new HallPrivateMarkService.Command(0,true,null),"archive").archived());
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,assertThrows(HallRequestDraftService.Failure.class,
                ()->service.mark(OWNER,"TASK","e",new HallPrivateMarkService.Command(0,true,null),"task")).reason());
        execution.setExecutionMode("TASK");
        assertEquals(HallRequestDraftService.Reason.NOT_FOUND,assertThrows(HallRequestDraftService.Failure.class,
                ()->service.get(OWNER,"LEGACY_EXECUTION","e")).reason());
    }
    @Test void changedSourceSnapshotReopensArchivedItemAndForeignProjectionFailsClosed() {
        HallPrivateMarkEntity row=new HallPrivateMarkEntity().setTenantId("0").setClientId("c").setOwnerJiacn("o")
                .setSourceType("LEGACY_EXECUTION").setSourceId("e").setRevision(1L).setArchived(true)
                .setSnapshotExecutionId("e").setSnapshotState("QUEUED").setSnapshotUpdatedAt(100L).setUpdatedAt(110L);
        when(marks.current("0","c","o","LEGACY_EXECUTION","e")).thenReturn(row);
        assertFalse(service.get(OWNER,"LEGACY_EXECUTION","e").archived());
        row.setOwnerJiacn("foreign");
        assertEquals(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE,assertThrows(HallRequestDraftService.Failure.class,
                ()->service.get(OWNER,"LEGACY_EXECUTION","e")).reason());
    }
}
