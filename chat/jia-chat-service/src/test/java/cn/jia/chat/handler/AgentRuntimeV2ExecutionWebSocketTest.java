package cn.jia.chat.handler;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.chat.config.AgentRuntimeV2WebSocketConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.socket.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.*;
import static cn.jia.chat.handler.AgentRuntimeV2WebSocketAdapterTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

class AgentRuntimeV2ExecutionWebSocketTest {
    static final String ID="11111111-1111-4111-8111-111111111111",HASH="1".repeat(64);
    static class Fixture {
        final AgentRuntimeV2RegistrationService registration=mock(AgentRuntimeV2RegistrationService.class);
        final AgentRuntimeV2ExecutionService service=mock(AgentRuntimeV2ExecutionService.class);
        final AgentRuntimeV2WebSocketAdapter adapter=new AgentRuntimeV2WebSocketAdapter(provider(registration),provider(new AgentRuntimeV2Readiness(ready())));
        final WebSocketSession socket=session("execution");final AgentRuntimeV2RegistrationContext identity=(AgentRuntimeV2RegistrationContext)socket.getAttributes().get(AgentRuntimeV2WebSocketAdapter.IDENTITY_ATTRIBUTE);
        final AgentRuntimeV2SocketTuple tuple=new AgentRuntimeV2SocketTuple(ID,0,ID,ID,adapter.ownerInstanceId(),0);
        Fixture() throws Exception {adapter.executionServices(provider(service));adapter.established(socket);byte[] create=createWire();var r=AgentRuntimeV2RegistrationCodec.decodeCreate(create);when(registration.create(any(),anyString(),anyString(),anyLong(),any())).thenReturn(new AgentRuntimeV2RegistrationService.Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",r.registrationRequestId(),null,"NOT_AUTHORIZED")),tuple,null));adapter.intercept(socket,create,response->{});assertTrue(adapter.hasDispatchRoute(tuple));clearInvocations(registration,socket);}
        byte[] renewal(){return encodeRenew(new Renew(ID,ID,ID,HASH,ID,HASH));}
        byte[] ack(){return encodeAck(new Ack("ack",ID,"source","STARTED",123));}
        byte[] continuation(){return AgentRuntimeV2WireCodec.encodeContinuationRequest(new AgentRuntimeV2WireCodec.ContinuationRequest(ID,ID,ID,HexFormat.of().parseHex(HASH)));}
    }
    @Test void threeRawRoutesRetainExactBytesOriginalHandshakeAndCommittedTuple() throws Exception {
        var f=new Fixture();byte[] response=rejected("RENEWAL",ID,ID,ID,"RENEWAL_NOT_AUTHORIZED");when(f.service.acknowledge(any(),any(),any())).thenReturn(new AgentRuntimeV2ExecutionService.Result(List.of(response),false));when(f.service.continueExecution(any(),any(),any())).thenReturn(new AgentRuntimeV2ExecutionService.Result(List.of(response),false));when(f.service.renewExecution(any(),any(),any())).thenReturn(new AgentRuntimeV2ExecutionService.Result(List.of(response),false));
        byte[] ack=f.ack(),continuation=f.continuation(),renew=f.renewal();var sent=new ArrayList<TextMessage>();for(byte[] raw:List.of(ack,continuation,renew))assertTrue(f.adapter.intercept(f.socket,raw,m->{AgentRuntimeV2ExecutionServiceImpl.outside();sent.add(m);}));assertEquals(3,sent.size());verify(f.service).acknowledge(same(f.identity),same(f.tuple),same(ack));verify(f.service).continueExecution(same(f.identity),same(f.tuple),same(continuation));verify(f.service).renewExecution(same(f.identity),same(f.tuple),same(renew));verifyNoInteractions(f.registration);
    }
    @Test void legacySchemaOneAndAbsentVersionAckRemainInUnchangedV1Lane() throws Exception {
        var f=new Fixture();for(String text:List.of("{\"schemaVersion\":1,\"messageType\":\"command.ack\",\"messageId\":\"x\",\"ackAt\":123}","{\"messageType\":\"command.ack\",\"messageId\":\"x\",\"ackAt\":123}","{\"type\":\"ping\"}"))assertFalse(f.adapter.intercept(f.socket,text.getBytes(StandardCharsets.UTF_8),m->fail()));verifyNoInteractions(f.service);verify(f.socket,never()).close(any());
    }
    static void assertV1MarkerIsOnlyData(String routing,String location) throws Exception {
        for(String marker:List.of("command.execution.renew","command.ack"))for(String version:List.of("","\"schemaVersion\":1,")){
            var f=new Fixture();String data=location.equals("content")?"\"content\":\"讨论 "+marker+"\"":"\"metadata\":{\"messageType\":\""+marker+"\",\"schemaVersion\":2,\"runtimeDispatchId\":\""+ID+"\"}";
            byte[] raw=AgentRuntimeV2WebSocketAdapter.originalText("{"+version+"\""+routing+"\":\"chat.message\","+data+"}");
            assertFalse(f.adapter.intercept(f.socket,raw,m->fail("V1 data must remain on legacy lane")));
            verifyNoInteractions(f.service,f.registration);verify(f.socket,never()).close(any());assertTrue(f.adapter.hasDispatchRoute(f.tuple));
        }
    }
    @Test void legacyTypeContentExecutionWordsAreNotRouting() throws Exception {assertV1MarkerIsOnlyData("type","content");}
    @Test void legacyTypeNestedMetadataExecutionWordsAreNotRouting() throws Exception {assertV1MarkerIsOnlyData("type","metadata");}
    @Test void v1MessageTypeContentExecutionWordsAreNotRouting() throws Exception {assertV1MarkerIsOnlyData("messageType","content");}
    @Test void v1MessageTypeNestedMetadataExecutionWordsAreNotRouting() throws Exception {assertV1MarkerIsOnlyData("messageType","metadata");}
    @Test void malformedV2AndAliasesNeverFallBackNormalizeEchoOrCallService() throws Exception {
        var f=new Fixture();String r=new String(f.renewal(),StandardCharsets.UTF_8),a=new String(f.ack(),StandardCharsets.UTF_8);for(String malformed:List.of(" "+r,"\ufeff"+r,r+"\n",r.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"),r.replace("messageType","message_type"),r.replace("messageType","type"),r.replace("messageType","message\\u0054ype"),r.replace("\"messageType\":\"command.execution.renew\"","\"messageType\":\"command.execution.renew\",\"messageType\":\"chat.message\""),r.replace("command.execution.renew","command.execution.unknown"),r.replace("\"messageType\":\"command.execution.renew\",",""),a.replace("\"123\"","123"),a.replace("\"schemaVersion\":2,",""),a.replace("\"schemaVersion\":2","\"schemaVersion\":1"),r.substring(0,r.length()-1))){assertTrue(f.adapter.intercept(f.socket,malformed.getBytes(StandardCharsets.UTF_8),m->fail("must not echo unvalidated correlation")));}verifyNoInteractions(f.service);
    }
    @Test void networkOccursOnlyAfterPhysicalCommitAndGrantHasFreshLateResultFence() throws Exception {
        var f=new Fixture();var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:execution_network_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa",""));jdbc.execute("CREATE TABLE network_observation(id INT PRIMARY KEY)");var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        byte[] receipt=receiptDelivery(AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(ID,ID,"ack","source","STARTED",123,124,2,2,ID)),false);
        byte[] inner=AgentRuntimeV2WireCodec.encodeExecutionGrant(new AgentRuntimeV2WireCodec.ExecutionGrant(ID,"START",ID,ID,new byte[32],ID,ID,0,0,ID,ID,0,60000,null,124));byte[] grant=grantDelivery(inner,"STARTED_ACK","ack",false);
        when(f.service.acknowledge(any(),any(),any())).thenAnswer(call->tx.execute(s->{jdbc.update("INSERT INTO network_observation VALUES(1)");return new AgentRuntimeV2ExecutionService.Result(List.of(receipt,grant),false);}));when(f.service.current(any(),any(),any())).thenAnswer(call->{AgentRuntimeV2ExecutionServiceImpl.outside();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM network_observation",Integer.class));return false;});var sent=new ArrayList<TextMessage>();f.adapter.intercept(f.socket,f.ack(),m->{AgentRuntimeV2ExecutionServiceImpl.outside();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM network_observation",Integer.class));sent.add(m);});assertEquals(1,sent.size());assertArrayEquals(receipt,sent.getFirst().asBytes());verify(f.service).current(same(f.identity),same(f.tuple),aryEq(inner));
    }
    @Test void ambientTransactionSynchronizationAndBoundResourceDenyBeforeServiceSendOrClose() throws Exception {
        var f=new Fixture();TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->f.adapter.intercept(f.socket,f.renewal(),m->fail()));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}TransactionSynchronizationManager.initSynchronization();try{assertThrows(IllegalStateException.class,()->f.adapter.intercept(f.socket,f.renewal(),m->fail()));}finally{TransactionSynchronizationManager.clearSynchronization();}Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());try{assertThrows(IllegalStateException.class,()->f.adapter.intercept(f.socket,f.renewal(),m->fail()));}finally{TransactionSynchronizationManager.unbindResource(key);}verifyNoInteractions(f.service);verify(f.socket,never()).close(any());
    }
    @Test void routeRetiredWhileServiceRunsCannotDeliverLateResponse() throws Exception {var f=new Fixture();when(f.service.renewExecution(any(),any(),any())).thenAnswer(call->{f.adapter.retireDispatchRoute(f.tuple);return new AgentRuntimeV2ExecutionService.Result(List.of(rejected("RENEWAL",ID,ID,ID,"RENEWAL_NOT_AUTHORIZED")),false);});f.adapter.intercept(f.socket,f.renewal(),m->fail("retired route"));assertFalse(f.adapter.hasDispatchRoute(f.tuple));}
    @Test void routeRetiredDuringCurrentCannotAdmitGrantEvenIfCurrentReturnsTrue() throws Exception {
        var f=new Fixture();byte[] inner=AgentRuntimeV2WireCodec.encodeExecutionGrant(new AgentRuntimeV2WireCodec.ExecutionGrant(ID,"RENEWAL",ID,ID,new byte[32],ID,ID,0,0,ID,ID,1,60000,ID,124));
        when(f.service.renewExecution(any(),any(),any())).thenReturn(new AgentRuntimeV2ExecutionService.Result(List.of(grantDelivery(inner,"RENEWAL",ID,false)),false));
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
            when(f.service.current(any(),any(),any())).thenAnswer(call->{
                AgentRuntimeV2ExecutionServiceImpl.outside();assertTrue(f.adapter.hasDispatchRoute(f.tuple));
                // current holds the route monitor: a different thread must retire without waiting for it.
                pool.submit(()->f.adapter.retireDispatchRoute(f.tuple)).get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(f.adapter.hasDispatchRoute(f.tuple));assertTrue(f.socket.isOpen());
                return true; // DB authority remains valid, and queued socket close has not run.
            });
            assertTrue(f.adapter.intercept(f.socket,f.renewal(),m->fail("retired route must not admit a grant after current")));
        }
        verify(f.service).current(same(f.identity),same(f.tuple),aryEq(inner));assertFalse(f.adapter.hasDispatchRoute(f.tuple));verify(f.socket,never()).close(any());
    }
    @Test void productionExecutionCapabilityReachesAdapterAndSchedulerButMissingNodeTopologyStillOnlyOne() throws Exception {
        var eg=mock(AgentRuntimeV2ExecutionSchemaGate.class);when(eg.verified()).thenReturn(true);var dg=mock(AgentRuntimeV2DispatchSchemaGate.class);when(dg.verified()).thenReturn(true);var gateway=mock(AgentRuntimeV2SocketGateway.class);var store=mock(AgentRuntimeV2RegistrationStore.class);var keys=mock(AgentRuntimeV2RegistrationKeyRing.class);var tx=mock(PlatformTransactionManager.class);
        try(var c=new AnnotationConfigApplicationContext()){
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated",Map.of("agent.runtime-v2-dispatch.enabled","true","agent.runtime-v2-dispatch.scheduler-enabled","true","agent.runtime-v2-registration.activation-enabled","true","agent.runtime-v2-execution.enabled","true")));
            c.registerBean(JdbcTemplate.class,()->mock(JdbcTemplate.class));c.registerBean(PlatformTransactionManager.class,()->tx);c.registerBean(AgentRuntimeV2RegistrationStore.class,()->store);c.registerBean(AgentRuntimeV2RegistrationKeyRing.class,()->keys);c.registerBean("agentRuntimeV2SocketGateway",AgentRuntimeV2SocketGateway.class,()->gateway);c.registerBean(AgentRuntimeV2WebSocketAdapter.class,()->new AgentRuntimeV2WebSocketAdapter(c.getBeanProvider(AgentRuntimeV2RegistrationService.class),c.getBeanProvider(AgentRuntimeV2Readiness.class)));
            c.register(AgentRuntimeV2DispatchConfiguration.class,AgentRuntimeV2ExecutionConfiguration.class,AgentRuntimeV2WebSocketConfiguration.class);c.addBeanFactoryPostProcessor(factory->{((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2DispatchSchemaGate")).setInstanceSupplier(()->dg);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2ExecutionSchemaGate")).setInstanceSupplier(()->eg);((AbstractBeanDefinition)factory.getBeanDefinition("agentRuntimeV2CompositeWorkerProof")).setInstanceSupplier(()->mock(AgentRuntimeV2CompositeWorkerProof.class));});c.refresh();
            var execution=c.getBeanProvider(AgentRuntimeV2ExecutionService.class).getIfAvailable();assertInstanceOf(AgentRuntimeV2ExecutionServiceImpl.class,execution);var adapter=c.getBean(AgentRuntimeV2WebSocketAdapter.class);assertSame(execution,((ObjectProvider<?>)ReflectionTestUtils.getField(adapter,"execution")).getIfAvailable());var cap=c.getBean("agentRuntimeV2ExecutionCapability",AgentRuntimeV2CapabilityReadiness.class);var caps=c.getBeanProvider(AgentRuntimeV2CapabilityReadiness.class).orderedStream().toList();assertTrue(caps.contains(cap));var scheduler=c.getBean(AgentRuntimeV2DispatchConfiguration.AgentRuntimeV2DispatchScheduler.class);assertEquals(caps,((ObjectProvider<?>)ReflectionTestUtils.getField(scheduler,"capabilities")).orderedStream().toList());
            var readiness=c.getBean(AgentRuntimeV2Readiness.class);@SuppressWarnings("unchecked") var evidence=(Map<AgentRuntimeV2Readiness.Dependency,java.util.function.BooleanSupplier>)ReflectionTestUtils.getField(readiness,"evidence");assertTrue(evidence.get(AgentRuntimeV2Readiness.Dependency.EXECUTION_HOLDER_TRANSFER).getAsBoolean());assertFalse(evidence.get(AgentRuntimeV2Readiness.Dependency.NODE_INTEROPERABILITY).getAsBoolean());assertFalse(evidence.get(AgentRuntimeV2Readiness.Dependency.CONSUMER_TOPOLOGY).getAsBoolean());assertFalse(readiness.ready());assertArrayEquals(new int[]{1},adapter.supportedProtocolVersions());scheduler.poll();adapter.maintainRouting();verifyNoInteractions(gateway,store,keys,tx);when(eg.verified()).thenReturn(false);assertFalse(evidence.get(AgentRuntimeV2Readiness.Dependency.EXECUTION_HOLDER_TRANSFER).getAsBoolean());
        }
    }
}
