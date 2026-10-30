package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.skill.SkillManagementMySqlFixture;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real material service and private MySQL transactions. Fence/DAO adapters use minimal
 * row-lock SQL; this is not a production authentication or full WebSocket E2E test. */
class ChatMaterialsLockOrderMySqlTest extends SkillManagementMySqlFixture {
    @Test void materialReadAndNativeMutationShareBindingBeforeTaskOrder() throws Exception {
        String database="cyf_material_lock_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+database);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var dataSource=source(database);
            var jdbc=new JdbcTemplate(dataSource);
            for(String table:new String[]{"binding_row","task_row"}) {
                jdbc.execute("CREATE TABLE "+table+" (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
                jdbc.update("INSERT INTO "+table+" VALUES (1)");
            }
            var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var fixture=new ChatMaterialsFenceTest();fixture.setup();
            var nativeHasBinding=new CountDownLatch(1);
            var materialAttemptedBinding=new CountDownLatch(1);
            doAnswer(i->tx.execute(status->{
                materialAttemptedBinding.countDown();
                jdbc.queryForObject("SELECT id FROM binding_row WHERE id=1 FOR UPDATE",Long.class);
                fixture.fenced=true;fixture.fences++;
                try{return ((Supplier<?>)i.getArgument(1)).get();}finally{fixture.fenced=false;}
            })).when(fixture.authentication).withNativeFence(eq(fixture.runtime),any());
            doAnswer(i->{
                assertTrue(fixture.fenced);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                jdbc.queryForObject("SELECT id FROM task_row WHERE id=1 FOR UPDATE",Long.class);
                // incomingTurn -> admitFollowupBaseline re-enters the already-held binding.
                jdbc.queryForObject("SELECT id FROM binding_row WHERE id=1 FOR UPDATE",Long.class);
                return new ChatDispatchAuthorityService.Binding(null,fixture.turn,"owner");
            }).when(fixture.dispatch).incomingTurn("0","client","owner","agent","turn");
            doAnswer(i->tx.execute(status->{
                assertTrue(fixture.fenced);
                jdbc.queryForObject("SELECT id FROM task_row WHERE id=1 FOR UPDATE",Long.class);
                jdbc.queryForObject("SELECT id FROM binding_row WHERE id=1 FOR UPDATE",Long.class);
                AgentTaskMutationTransaction.LockedTaskMutation<?> callback=i.getArgument(4);
                return callback.apply(new AgentTaskMetaEntity().setAssignedAgentId("agent"));
            })).when(fixture.tasks).executeWithLockedTaskRootInOwnerScope(
                    eq("0"),eq("client"),eq("owner"),eq("task"),any());
            doAnswer(i->{
                assertFalse(fixture.fenced);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"I/O must be outside both transactions");
                return fixture.content;
            }).when(fixture.sources).read(any(),anyString(),anyString(),anyLong(),any());
            var nativeMutation=executor.submit(()->tx.execute(status->{
                jdbc.queryForObject("SELECT id FROM binding_row WHERE id=1 FOR UPDATE",Long.class);
                nativeHasBinding.countDown();
                try{assertTrue(materialAttemptedBinding.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                jdbc.queryForObject("SELECT id FROM task_row WHERE id=1 FOR UPDATE",Long.class);
                return true;
            }));
            assertTrue(nativeHasBinding.await(5,TimeUnit.SECONDS));
            var material=executor.submit(fixture::read);
            assertTrue(nativeMutation.get(10,TimeUnit.SECONDS));
            assertArrayEquals(fixture.content.bytes(),material.get(10,TimeUnit.SECONDS));
            assertEquals(2,fixture.fences);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(15,TimeUnit.SECONDS));
            admin.execute("DROP DATABASE "+database);
        }
    }
}
