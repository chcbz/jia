package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import static cn.jia.chat.handler.AgentRuntimeV2WebSocketAdapterTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRuntimeV2SocketGatewayTest {
    @Test void boundedTimeoutFencesRouteEvenWhenUnderlyingSendAndCloseBothBlock() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var socket=session("r2c-blocked-owner");adapter.established(socket);byte[] registration=createWire();var request=AgentRuntimeV2RegistrationCodec.decodeCreate(registration);var tuple=new AgentRuntimeV2SocketTuple(UUID.randomUUID().toString(),0,request.runtimeInstanceId(),UUID.randomUUID().toString(),adapter.ownerInstanceId(),0);when(service.create(any(),anyString(),anyString(),anyLong(),any())).thenReturn(new AgentRuntimeV2RegistrationService.Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",request.registrationRequestId(),null,"NOT_AUTHORIZED")),tuple,null));adapter.intercept(socket,registration,response->{});var entered=new CountDownLatch(1);var closing=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();awaitIgnoringInterrupt(release);return null;}).when(socket).sendMessage(any());doAnswer(call->{closing.countDown();awaitIgnoringInterrupt(release);return null;}).when(socket).close(any(CloseStatus.class));var clock=Clock.systemUTC();var gateway=new AgentRuntimeV2SocketGatewayImpl(adapter,clock);byte[] outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(UUID.randomUUID().toString(),new byte[]{1}));long start=System.nanoTime();try{assertFalse(gateway.send(tuple,outer,clock.millis()+150));assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(closing.await(1,TimeUnit.SECONDS));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1000);assertFalse(gateway.current(tuple));assertFalse(gateway.send(tuple,outer,clock.millis()+150));}finally{release.countDown();gateway.close();}
    }
    private static void awaitIgnoringInterrupt(CountDownLatch release){boolean interrupted=false;while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    @Test void onlyCommittedOriginalOwnerAndExactTupleReceiveOriginalOuterAfterCleanup() throws Exception {
        var service=mock(AgentRuntimeV2RegistrationService.class);var adapter=new AgentRuntimeV2WebSocketAdapter(provider(service),provider(new AgentRuntimeV2Readiness(ready())));var socket=session("r2c-owner-y");adapter.established(socket);byte[] registration=createWire();var request=AgentRuntimeV2RegistrationCodec.decodeCreate(registration);
        var tuple=new AgentRuntimeV2SocketTuple(UUID.randomUUID().toString(),0,request.runtimeInstanceId(),UUID.randomUUID().toString(),adapter.ownerInstanceId(),0);
        when(service.create(any(),anyString(),anyString(),anyLong(),any())).thenReturn(new AgentRuntimeV2RegistrationService.Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",request.registrationRequestId(),null,"NOT_AUTHORIZED")),tuple,null));adapter.intercept(socket,registration,response->{});
        var clock=Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC);var gateway=new AgentRuntimeV2SocketGatewayImpl(adapter,clock);
        byte[] outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(UUID.randomUUID().toString(),new byte[131072]));var sent=new AtomicInteger();doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());assertArrayEquals(outer,((TextMessage)call.getArgument(0)).asBytes());sent.incrementAndGet();return null;}).when(socket).sendMessage(any());
        try{assertTrue(gateway.send(tuple,outer,1100));assertEquals(1,sent.get());var wrong=new AgentRuntimeV2SocketTuple(tuple.runtimeBindingId(),1,tuple.runtimeInstanceId(),tuple.sessionFence(),tuple.socketOwnerInstanceId(),tuple.socketOwnerEpoch());assertFalse(gateway.send(wrong,outer,1100));assertFalse(gateway.send(tuple,outer,1000));adapter.closed(socket);assertFalse(gateway.send(tuple,outer,1100));assertEquals(1,sent.get());}finally{gateway.close();}
    }
    @Test void rejectsCallerAmbientResourcesEvenWhenAsyncSendThreadWouldHaveNoTransaction() throws Exception {
        var adapter=new AgentRuntimeV2WebSocketAdapter(provider(null),provider(null));var gateway=new AgentRuntimeV2SocketGatewayImpl(adapter,Clock.systemUTC());byte[] outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(UUID.randomUUID().toString(),new byte[]{1}));var tuple=new AgentRuntimeV2SocketTuple(UUID.randomUUID().toString(),0,UUID.randomUUID().toString(),UUID.randomUUID().toString(),adapter.ownerInstanceId(),0);Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());try{assertThrows(IllegalStateException.class,()->gateway.send(tuple,outer,Long.MAX_VALUE));}finally{TransactionSynchronizationManager.unbindResource(key);gateway.close();}
    }
}
