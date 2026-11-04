package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.*;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real REQUIRED transaction proves edits cannot leave a new snapshot with old materials. */
class AgentTaskRequirementEditServiceImplTest {
    static final AgentTaskExecutionGrantService.Scope OWNER=new AgentTaskExecutionGrantService.Scope("0","client","owner");
    JdbcTemplate jdbc; AgentTaskRequirementSnapshotService snapshots; PersonalWorkspaceTaskLinkService links;
    AgentTaskRequirementEditService service; AgentTaskMetaDao tasks; boolean failLink;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:edit-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE fixture_task(version BIGINT,status VARCHAR(20),assignee VARCHAR(100),name VARCHAR(200),description VARCHAR(20000))");
        jdbc.execute("CREATE TABLE fixture_snapshot(revision BIGINT,title VARCHAR(200),description VARCHAR(20000),confirmation VARCHAR(100),version BIGINT)");
        jdbc.execute("CREATE TABLE fixture_link(file_id VARCHAR(100),file_version INT)");
        jdbc.execute("CREATE TABLE agent_task_funding(tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),task_id VARCHAR(100))");
        jdbc.update("INSERT INTO fixture_task VALUES (0,'open',NULL,'Old','Old body')");
        jdbc.update("INSERT INTO fixture_snapshot VALUES (1,'Old','Old body','CREATE',0)");
        jdbc.update("INSERT INTO fixture_link VALUES ('old-file',1)");
        tasks=mock(AgentTaskMetaDao.class);
        when(tasks.findByTaskIdForUpdateInOwnerScope(anyString(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            if (!"0".equals(i.getArgument(0)) || !"client".equals(i.getArgument(1)) || !"owner".equals(i.getArgument(2)) || !"1".equals(i.getArgument(3))) return null;
            return jdbc.queryForObject("SELECT version,status,assignee FROM fixture_task",(r,n) -> {
                var root=new AgentTaskMetaEntity().setTaskId("1").setOwnerJiacn("owner").setTaskVersion(r.getLong(1))
                        .setCurrentEventVersion(0L).setRewardStatus(r.getString(2)).setAssignedAgentId(r.getString(3));
                root.setTenantId("0");root.setClientId("client");return root;
            });
        });
        when(tasks.updateById(any(AgentTaskMetaEntity.class))).thenAnswer(i -> jdbc.update("UPDATE fixture_task SET version=?",((AgentTaskMetaEntity)i.getArgument(0)).getTaskVersion()));
        snapshots=mock(AgentTaskRequirementSnapshotService.class);
        when(snapshots.readCurrent(eq(OWNER),eq("1"))).thenAnswer(i -> jdbc.queryForObject("SELECT revision,title,description FROM fixture_snapshot ORDER BY revision DESC LIMIT 1",(r,n) ->
                new AgentTaskRequirementSnapshotService.CurrentSnapshot("1",jdbc.queryForObject("SELECT version FROM fixture_task",Long.class),r.getLong(1),r.getString(2),r.getString(3),"a".repeat(64),"RECONFIRM")));
        when(snapshots.reconfirm(eq(OWNER),eq("1"),anyLong(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            String key=i.getArgument(3),title=i.getArgument(4),body=i.getArgument(5); long version=i.getArgument(2);
            var rows=jdbc.queryForList("SELECT title,description,version FROM fixture_snapshot WHERE confirmation=?",key);
            if (rows.isEmpty()) jdbc.update("INSERT INTO fixture_snapshot SELECT MAX(revision)+1,?,?,?,? FROM fixture_snapshot",title,body,key,version);
            else if (!title.equals(rows.getFirst().get("TITLE")) || !body.equals(rows.getFirst().get("DESCRIPTION")) || ((Number)rows.getFirst().get("VERSION")).longValue()!=version) throw new IllegalStateException("key conflict");
            return null;
        });
        links=mock(PersonalWorkspaceTaskLinkService.class);
        when(links.list(any(),eq("1"),isNull())).thenAnswer(i -> new PersonalWorkspaceTaskLinkService.LinkListView(jdbc.query("SELECT file_id,file_version FROM fixture_link",(r,n) -> new PersonalWorkspaceTaskLinkService.LinkView(r.getString(1),"1",r.getString(1),r.getInt(2),"INPUT","ACTIVE",1,1)),null));
        when(links.detach(any(),eq("1"),anyString(),anyString(),anyString())).thenAnswer(i -> { jdbc.update("DELETE FROM fixture_link WHERE file_id=?",(String)i.getArgument(2));return null; });
        when(links.create(any(),eq("1"),any())).thenAnswer(i -> {
            if (failLink) throw new PersonalWorkspaceTaskLinkService.Failure(PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND);
            var c=(PersonalWorkspaceTaskLinkService.CreateCommand)i.getArgument(2);
            jdbc.update("INSERT INTO fixture_link VALUES (?,?)",c.fileId(),c.version());return null;
        });
        var plans=mock(TaskService.class);
        when(plans.updateSummaryInOwnerScope(eq("0"),eq("client"),eq("owner"),eq(1L),anyString(),anyString())).thenAnswer(i -> jdbc.update("UPDATE fixture_task SET name=?,description=?",(String)i.getArgument(4),(String)i.getArgument(5))==1);
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(plans);
        service=new AgentTaskRequirementEditServiceImpl(new AgentTaskMutationTransactionImpl(tasks,new DataSourceTransactionManager(ds)),snapshots,links,tasks,provider,jdbc);
    }
    AgentTaskRequirementEditService.Command command() { return new AgentTaskRequirementEditService.Command(0,1,"New title","New body",List.of(new AgentTaskRequirementEditService.Attachment("new-file",2))); }
    @Test void savesTextAndSelectedVersionsOnceAndReplaysLostReply() {
        var saved=service.save(OWNER,"1","edit-key",command());
        assertEquals("1",saved.taskVersion());assertEquals("2",saved.requirementRevision());
        assertEquals(command().attachments(),saved.attachments());
        assertEquals(saved,service.save(OWNER,"1","edit-key",command()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM fixture_snapshot",Integer.class));
        assertEquals("new-file",jdbc.queryForObject("SELECT file_id FROM fixture_link",String.class));
        assertEquals("Old",jdbc.queryForObject("SELECT title FROM fixture_snapshot WHERE revision=1",String.class));
    }
    @Test void failedMaterialWriteRollsBackSnapshotDetachSummaryAndVersion() {
        failLink=true;
        assertThrows(PersonalWorkspaceTaskLinkService.Failure.class,() -> service.save(OWNER,"1","edit-key",command()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fixture_snapshot",Integer.class));
        assertEquals("old-file",jdbc.queryForObject("SELECT file_id FROM fixture_link",String.class));
        assertEquals(0L,jdbc.queryForObject("SELECT version FROM fixture_task",Long.class));
        assertEquals("Old",jdbc.queryForObject("SELECT name FROM fixture_task",String.class));
    }
    @Test void assignedFundedAndWrongOwnerCannotEdit() {
        jdbc.update("UPDATE fixture_task SET status='assigned',assignee='agent'");
        assertEquals(AgentTaskRequirementEditService.Reason.NOT_EDITABLE,assertThrows(AgentTaskRequirementEditService.Failure.class,() -> service.save(OWNER,"1","key",command())).reason());
        jdbc.update("UPDATE fixture_task SET status='open',assignee=NULL");jdbc.update("INSERT INTO agent_task_funding VALUES ('0','client','owner','1')");
        assertThrows(AgentTaskRequirementEditService.Failure.class,() -> service.save(OWNER,"1","key",command()));
        assertThrows(cn.jia.agent.exception.AgentTaskCollaborationException.class,() -> service.read(new AgentTaskExecutionGrantService.Scope("0","client","other"),"1"));
        verify(snapshots,never()).reconfirm(any(),anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void staleVersionOrRequirementCannotOverwriteNewerEdits() {
        jdbc.update("UPDATE fixture_task SET version=1");
        assertEquals(AgentTaskRequirementEditService.Reason.CONFLICT,assertThrows(AgentTaskRequirementEditService.Failure.class,() -> service.save(OWNER,"1","key",command())).reason());
        verify(snapshots,never()).reconfirm(any(),anyString(),anyLong(),anyString(),anyString(),anyString());
    }
    @Test void failedFinalWriteRollsBackEverything() {
        when(tasks.updateById(any(AgentTaskMetaEntity.class))).thenReturn(0);
        assertThrows(AgentTaskRequirementEditService.Failure.class,() -> service.save(OWNER,"1","key",command()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fixture_snapshot",Integer.class));
        assertEquals("old-file",jdbc.queryForObject("SELECT file_id FROM fixture_link",String.class));
        assertEquals("Old",jdbc.queryForObject("SELECT name FROM fixture_task",String.class));
    }
    @Test void clearingMaterialsAndPreservingLongTextAreSupported() {
        var c=new AgentTaskRequirementEditService.Command(0,1,"事项标题".repeat(25),"需求描述".repeat(100),List.of());
        var saved=service.save(OWNER,"1","key",c);assertEquals(c.title(),saved.title());
        assertEquals(c.description(),service.read(OWNER,"1").description());
        assertTrue(service.read(OWNER,"1").attachments().isEmpty());
    }
}
