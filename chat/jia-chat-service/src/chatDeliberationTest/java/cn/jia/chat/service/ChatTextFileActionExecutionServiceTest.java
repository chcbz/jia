package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatTextFileActionExecutionServiceTest {
    static class Fixture {
        final ChatActionExecutionServiceTest.Fixture f=new ChatActionExecutionServiceTest.Fixture(false,true);
        final PersonalWorkspaceExecutionService executions=mock(PersonalWorkspaceExecutionService.class);
        final ChatTextFileActionExecutionService service=new ChatTextFileActionExecutionService(
                f.base.deliberation,f.base.dao,f.base.typed,f.steps,f.grants,executions);
        Fixture() {
            when(f.grants.resolveAndAdmit(any(),eq("task"),eq(3L),eq("agent"),eq("GENERATE_TEXT_FILE"),eq(false)))
                    .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",2,3,"agent","GENERATE_TEXT_FILE",false,
                            List.of(),null,null,3L,1L,"b".repeat(64),"assignment","c".repeat(64)));
            when(executions.createConversation(any(),any())).thenAnswer(i->{
                PersonalWorkspaceExecutionService.OwnerScope scope=i.getArgument(0);
                PersonalWorkspaceExecutionService.ConversationCreate cmd=i.getArgument(1);
                assertEquals("GENERATE_TEXT_FILE",cmd.permittedOperation()); assertEquals("text/plain",cmd.outputContentMimeType());
                assertFalse(cmd.controlledImage()); assertEquals(List.of(),cmd.references());
                return new PersonalWorkspaceExecutionService.ExecutionView(PersonalWorkspaceExecutionService.textFileExecutionId(scope,cmd.intentId()),
                        cmd.taskId(),"run",cmd.conversationId(),cmd.targetAgentId(),"QUEUED",null,null,1,"text/plain",List.of(),null,
                        "CONVERSATION",null,null,null);
            });
        }
    }
    @Test void exactReplayDoesNotReexecuteOrRequireRuntimeAgain() {
        var x=new Fixture(); var id=x.service.admit(x.f.action);
        assertEquals(id,x.service.admit(x.f.action));
        verify(x.executions,times(1)).createConversation(any(),any());
        verify(x.f.grants,times(1)).resolveAndAdmit(any(),eq("task"),eq(3L),eq("agent"),eq("GENERATE_TEXT_FILE"),eq(false));
        assertEquals(1,x.f.links.size()); assertEquals(1,x.f.stepRows.size());
        assertEquals("EXECUTE",x.f.stepRows.get(id).kind());
    }
    @Test void corruptedExecutionLinkIsNotAcceptedAsReplay() {
        var x=new Fixture();x.service.admit(x.f.action);
        var row=x.f.links.values().iterator().next();
        x.f.links.put(row.stepId(),new ChatInteractionStepStore.ExecutionLink(row.executionIntentId(),row.tenantId(),row.ownerJiacn(),
                row.clientId(),row.stepId(),"foreign-execution",row.state(),row.stateVersion(),row.createdAt(),row.updatedAt()));
        assertThrows(ChatDeliberationException.class,()->x.service.admit(x.f.action));
        verify(x.executions,times(1)).createConversation(any(),any());
    }
    @Test void changedOriginalUserAndUnavailableGrantDoNotAdmitAnotherChild() {
        var x=new Fixture();x.service.admit(x.f.action);x.f.user().setContent("changed");
        assertThrows(ChatDeliberationException.class,()->x.service.admit(x.f.action));
        var denied=new Fixture();when(denied.f.grants.resolveAndAdmit(any(),anyString(),anyLong(),anyString(),anyString(),anyBoolean())).thenReturn(null);
        assertThrows(ChatDeliberationException.class,()->denied.service.admit(denied.f.action));
        verifyNoInteractions(denied.executions);assertTrue(denied.f.links.isEmpty());
    }
    @Test void imageActionIsNotRoutedThroughUnpaidTextAdapter() {
        var x=new Fixture();var image=new ChatActionExecutionServiceTest.Fixture(false);
        assertThrows(ChatDeliberationException.class,()->x.service.admit(image.action));verifyNoInteractions(x.executions);
    }
    @Test void stableExecutionIdIsScopedAndWithinSqlColumn() {
        var a=new PersonalWorkspaceExecutionService.OwnerScope("t","c","o");
        String id=PersonalWorkspaceExecutionService.textFileExecutionId(a,"intent");assertEquals(64,id.length());
        assertNotEquals(id,PersonalWorkspaceExecutionService.textFileExecutionId(new PersonalWorkspaceExecutionService.OwnerScope("other","c","o"),"intent"));
        assertNotEquals(id,PersonalWorkspaceExecutionService.textFileExecutionId(a,"other"));
    }
}
