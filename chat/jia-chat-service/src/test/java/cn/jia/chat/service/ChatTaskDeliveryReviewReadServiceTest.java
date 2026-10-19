package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTaskDeliveryReviewReadServiceTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final AgentTaskFormalDeliveryReadService deliveries=mock(AgentTaskFormalDeliveryReadService.class);
    final ChatSelectedOutputFinalizationStore submissions=mock(ChatSelectedOutputFinalizationStore.class);
    final ChatTaskDeliveryReviewReadService reads=new ChatTaskDeliveryReviewReadService(jdbc,deliveries,submissions);
    final Scope scope=new Scope("0","client","owner");
    AgentTaskFormalDeliveryViewDTO batch() {
        var b=ChatDeliveryReviewContractTest.batch(); b.setManifestArtifactId("artifact_manifest"); b.setManifestArtifactVersion(1); b.setSubmittedAt(123L); return b;
    }
    @Test void unresolvableResultIsVisibleButNeverAcquiresWriteEligibility() {
        var b=batch(); var items=reads.sourceItems(scope,"task_fixture",b);
        assertEquals(2,items.size()); assertEquals("SOURCE_UNAVAILABLE",items.getFirst().unavailableReason());
        assertEquals("MANIFEST",items.getLast().displayRole()); assertNull(items.getLast().unavailableReason());
        assertFalse(ChatTaskDeliveryReviewReadService.sourcesComplete(items));
        verify(jdbc).query(contains("agent_selected_output_finalization"),any(RowMapper.class),eq("0"),eq("owner"),eq("client"),eq("task_fixture"),eq("delivery_fixture"));
    }
    @Test void exactCommittedWorkspaceMappingDoesNotExposeAStorageUri() throws Exception {
        when(jdbc.query(contains("SELECT o.workspace_file_id"),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            RowMapper<?> mapper=invocation.getArgument(1); ResultSet row=mock(ResultSet.class);
            when(row.getString(1)).thenReturn("file-1"); when(row.getInt(2)).thenReturn(1);
            return List.of(mapper.mapRow(row,0));
        });
        var result=reads.sourceItems(scope,"task_fixture",batch());
        assertTrue(ChatTaskDeliveryReviewReadService.sourcesComplete(result));
        assertEquals(java.util.Map.of("kind","WORKSPACE_FILE","fileId","file-1","version","1"),result.getFirst().source());
        assertFalse(ChatDeliveryReviewContract.json(result).contains("storageUri"));
    }
    @Test void countBoundaryCannotSilentlyTruncateFormalHistory() {
        when(submissions.task("0","owner","client","task_fixture")).thenReturn(new ChatSelectedOutputFinalizationStore.TaskFact("reviewing",7));
        when(jdbc.queryForObject(contains("COUNT(*)"),eq(Long.class),eq("0"),eq("client"),eq("task_fixture"))).thenReturn(101L);
        assertThrows(Conflict.class,()->reads.read(scope,"task_fixture")); verifyNoInteractions(deliveries);
    }
    @Test void ownerAuthorizationPrecedesEveryAssociatedRead() {
        when(submissions.task("0","owner","client","task_fixture")).thenThrow(new ChatSelectedOutputFinalizationStore.Missing());
        assertThrows(ChatSelectedOutputFinalizationStore.Missing.class,()->reads.read(scope,"task_fixture"));
        verifyNoInteractions(jdbc,deliveries);
    }
    @Test void immutableReceiptMustMatchItsPersistedOperationAndCannotClaimSubmissionIsAcceptance() {
        var receipt=new Receipt(1,"review-1","task","delivery","ACCEPT","COMMITTED","2","COMPLETED","8","ACCEPTED","1",null,null,null,null,false);
        var operation=new ChatTaskDeliveryReviewStore.Operation("review-1","task","review-key","a".repeat(64),"delivery","COMMITTED",2,ChatDeliveryReviewContract.json(receipt));
        assertEquals(receipt,ChatTaskDeliveryReviewStore.committedReceipt(operation));
        assertThrows(Storage.class,()->ChatDeliveryReviewContract.receipt(ChatDeliveryReviewContract.json(receipt).replace("ACCEPTED","SUBMITTED")));
        assertThrows(Conflict.class,()->ChatTaskDeliveryReviewStore.committedReceipt(new ChatTaskDeliveryReviewStore.Operation("other","task","review-key","a".repeat(64),"delivery","COMMITTED",2,operation.receiptJson())));
    }
}
