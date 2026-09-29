package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Scope and optimistic-version contract; real MySQL migration is tested separately. */
class ChatInteractionStepStoreTest {
    @Test
    void stateCasBindsExactScopeAndVersion() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        ChatInteractionStepStore store=new ChatInteractionStepStore(jdbc);
        var step=new ChatInteractionStepStore.Step("step-1","0","owner-1","client-1",
                "request-1",1,1,"conversation-1",1,"task-1",3,"grant-1",1,
                "agent-1","EXECUTE","WAITING",7,"digest",10,10);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        assertEquals(1,store.updateStepState(step,"RUNNING",11));
        verify(jdbc).update(argThat(sql -> sql.contains("state_version=state_version+1")
                        && sql.contains("tenant_id=? AND owner_jiacn=? AND client_id=?")
                        && sql.contains("state_version=?")),
                eq("RUNNING"),eq(11L),eq("step-1"),eq("0"),eq("owner-1"),
                eq("client-1"),eq(7L));
    }

    @Test
    void executionBindingRequiresUnboundIdWithExactIntentStepAndVersion() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        ChatInteractionStepStore store=new ChatInteractionStepStore(jdbc);
        var link=new ChatInteractionStepStore.ExecutionLink("intent-1","0","owner-1",
                "client-1","step-1",null,"READY",2,10,10);
        store.bindExecution(link,"execution-1",11);
        verify(jdbc).update(argThat(sql -> sql.contains("execution_id IS NULL")
                        && sql.contains("state_version=?")),eq("execution-1"),eq(11L),
                eq("intent-1"),eq("0"),eq("owner-1"),eq("client-1"),
                eq("step-1"),eq(2L));
    }
}
