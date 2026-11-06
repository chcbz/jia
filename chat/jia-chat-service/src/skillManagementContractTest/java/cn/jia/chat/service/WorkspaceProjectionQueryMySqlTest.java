package cn.jia.chat.service;

import cn.jia.agent.entity.WorkspaceExecutionProjectionHint;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.*;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.service.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceProjectionQueryMySqlTest extends SkillManagementMySqlFixture {
    String database;
    JdbcTemplate jdbc;
    ChatBountyAssetProjector projector;
    @BeforeEach void setup() throws Exception {
        database="cyf_projection_"+UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        jdbc=new JdbcTemplate(source(database));
        jdbc.execute("CREATE TABLE chat_interaction_step (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),request_id VARCHAR(100),step_id VARCHAR(100),kind VARCHAR(30),state VARCHAR(30))");
        jdbc.execute("CREATE TABLE chat_step_execution_link (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),step_id VARCHAR(100),execution_id VARCHAR(100),state VARCHAR(30))");
        jdbc.execute("CREATE TABLE agent_personal_workspace_execution (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),execution_id VARCHAR(100),execution_mode VARCHAR(30),execution_state VARCHAR(30),execution_protocol_version INT,KEY execution_scope(tenant_id,client_id,execution_id))");
        projector=new ChatBountyAssetProjector(jdbc,mock(ChatDeliberationService.class),mock(ChatDeliberationDao.class),
                mock(ChatConversationDao.class),mock(ChatMessageDao.class),mock(PersonalWorkspaceExecutionService.class),mock(ChatConversationEventBroker.class));
    }
    @AfterEach void cleanup() {
        if(database!=null && database.matches("cyf_projection_[0-9a-f]{32}"))admin.execute("DROP DATABASE `"+database+"`");
    }
    void row(String id,String state,int protocol) {
        jdbc.update("INSERT INTO chat_interaction_step VALUES ('0','client','owner','request',?,'EXECUTE','RUNNING')","step-"+id);
        jdbc.update("INSERT INTO chat_step_execution_link VALUES ('0','client','owner',?,?,'RUNNING')","step-"+id,id);
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES ('0','client','owner',?,'CONVERSATION',?,?)",id,state,protocol);
    }
    @Test void exactLookupAndCompensationShareTerminalPredicate() {
        row("ok","RESULT_COMMITTED",5);row("failed","FAILED",5);row("revoked","INPUTS_REVOKED",5);row("cancelled","CANCELLED",5);
        row("queued","QUEUED",5);row("retired","RESULT_COMMITTED",4);
        var hint=new WorkspaceExecutionProjectionHint("0","client","owner","ok");
        assertEquals(List.of("ok"),projector.pendingExecution(hint).stream().map(ChatBountyAssetProjector.Candidate::executionId).toList());
        assertEquals(Set.of("ok","failed","revoked","cancelled"),new HashSet<>(projector.pending(null,64).stream().map(ChatBountyAssetProjector.Candidate::executionId).toList()));
        assertEquals(List.of("cancelled"),projector.pendingExecution(new WorkspaceExecutionProjectionHint("0","client","owner","cancelled")).stream().map(ChatBountyAssetProjector.Candidate::executionId).toList());
        assertEquals(1,projector.pending(null,1).size());
        jdbc.update("UPDATE chat_step_execution_link SET state='RESULT_COMMITTED' WHERE execution_id='ok'");
        assertTrue(projector.pendingExecution(hint).isEmpty());
    }
    @Test void exactScopeRejectsCaseAndTrailingSpaceAliases() {
        row("exec","RESULT_COMMITTED",5);
        for(var hint:List.of(new WorkspaceExecutionProjectionHint("0","CLIENT","owner","exec"),
                new WorkspaceExecutionProjectionHint("0 ","client","owner","exec"),
                new WorkspaceExecutionProjectionHint("0","client","Owner","exec"),
                new WorkspaceExecutionProjectionHint("0","client","owner","EXEC"),
                new WorkspaceExecutionProjectionHint("0","client","owner","exec ")))
            assertTrue(projector.pendingExecution(hint).isEmpty());
    }
}
