package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationCodec;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationContext;
import cn.jia.agent.entity.AgentRuntimeV2SocketTuple;
import cn.jia.agent.entity.AgentRuntimeV2EffectCodec;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints;
import cn.jia.agent.service.AgentRuntimeV2EffectService;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import cn.jia.chat.config.AgentRuntimeV2WebSocketConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static cn.jia.chat.handler.AgentRuntimeV2WebSocketAdapterTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual adapter/readiness/route boundary tests, with mocked reducer ACL.
 * NOT actual reducer/resource acceptance or production activation evidence.
 */
class AgentRuntimeV2EffectWebSocketTest {
    private record Fixture(AgentRuntimeV2WebSocketAdapter adapter,AgentRuntimeV2RegistrationService registration,AgentRuntimeV2EffectService service,WebSocketSession socket,AgentRuntimeV2SocketTuple committed){}
    private static Fixture fixture(boolean committed) throws Exception {
        var registration=mock(AgentRuntimeV2RegistrationService.class);var service=mock(AgentRuntimeV2EffectService.class);
        var adapter=new AgentRuntimeV2WebSocketAdapter(provider(registration),provider(new AgentRuntimeV2Readiness(ready())));adapter.effectServices(provider(service));
        var socket=session("effect-"+UUID.randomUUID());adapter.established(socket);var tuple=new AtomicReference<AgentRuntimeV2SocketTuple>();
        if(committed){
            byte[] raw=createWire();var request=AgentRuntimeV2RegistrationCodec.decodeCreate(raw);
            when(registration.create(any(),anyString(),anyString(),anyLong(),any())).thenAnswer(call->{
                var actual=new AgentRuntimeV2SocketTuple(UUID.randomUUID().toString(),0,request.runtimeInstanceId(),call.getArgument(1),call.getArgument(2),call.getArgument(3));tuple.set(actual);
                return new AgentRuntimeV2RegistrationService.Result(AgentRuntimeV2RegistrationCodec.encodeRejected(new AgentRuntimeV2RegistrationCodec.Rejected("CREATE",request.registrationRequestId(),null,"NOT_AUTHORIZED")),actual,null);
            });adapter.intercept(socket,raw,message->{});
        }
        return new Fixture(adapter,registration,service,socket,tuple.get());
    }
    private static AgentRuntimeV2EffectCodec.GrantReference grant(){return new AgentRuntimeV2EffectCodec.GrantReference(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),UUID.randomUUID().toString(),"b".repeat(64));}
    private static AgentRuntimeV2EffectCodec.Reconcile reconcile(){String dispatch=UUID.randomUUID().toString();return new AgentRuntimeV2EffectCodec.Reconcile(UUID.randomUUID().toString(),dispatch,AgentRuntimeV2EffectCodec.Subject.EXECUTION,dispatch,null,null);}
    private static byte[] response(AgentRuntimeV2EffectCodec.Request request,byte[] raw){
        String type,id,dispatch;AgentRuntimeV2EffectCodec.Result result=null;String decision="UNAVAILABLE";
        if(request instanceof AgentRuntimeV2EffectCodec.Inspect r){type="command.execution.control.receipt";id=r.grant().requestId();dispatch=r.grant().runtimeDispatchId();}
        else if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest r){type="command.execution.effect.receipt";id=r.grant().requestId();dispatch=r.grant().runtimeDispatchId();}
        else{var r=(AgentRuntimeV2EffectCodec.Reconcile)request;type="command.execution.reconcile.receipt";id=r.requestId();dispatch=r.runtimeDispatchId();decision="UNKNOWN";result=new AgentRuntimeV2EffectCodec.Reconciliation(r.subjectKind(),r.subjectId(),"UNKNOWN",null,null);}
        return AgentRuntimeV2EffectCodec.encodeReceipt(new AgentRuntimeV2EffectCodec.Receipt(type,id,dispatch,UUID.randomUUID().toString(),AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(raw)),decision,1000,result));
    }
    private static void result(Fixture f,AgentRuntimeV2EffectCodec.Request request,byte[] raw,byte[] receipt){
        var result=new AgentRuntimeV2EffectService.Result(List.of(receipt),false);
        if(request instanceof AgentRuntimeV2EffectCodec.Inspect)when(f.service.inspect(any(),any(),any(),any())).thenReturn(result);
        else if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest)when(f.service.effect(any(),any(),any(),any())).thenReturn(result);
        else when(f.service.reconcile(any(),nullable(AgentRuntimeV2SocketTuple.class),any(),any())).thenReturn(result);
        when(f.service.current(any(),nullable(AgentRuntimeV2SocketTuple.class),any(),any())).thenReturn(true);
    }
    @Test void actualEpochZeroNoTupleNewProcessRecoveryPassesOriginalIdentityRawAndSameTrustedRouteToBothAclChecks() throws Exception {
        var f=fixture(false);var request=reconcile();byte[] raw=AgentRuntimeV2EffectCodec.encode(request),receipt=response(request,raw);result(f,request,raw,receipt);
        var route=new AtomicReference<AgentRuntimeV2EffectService.TrustedRoute>();
        when(f.service.reconcile(any(),isNull(),any(),any())).thenAnswer(call->{
            cn.jia.agent.service.impl.AgentRuntimeV2ExecutionServiceImpl.outside();
            assertSame(f.socket.getAttributes().get(AgentRuntimeV2WebSocketAdapter.IDENTITY_ATTRIBUTE),call.getArgument(0));assertNull(call.getArgument(1));assertArrayEquals(raw,call.getArgument(3));
            var actual=call.getArgument(2,AgentRuntimeV2EffectService.TrustedRoute.class);assertEquals(f.adapter.ownerInstanceId(),actual.socketOwnerInstanceId());assertEquals(0,actual.socketOwnerEpoch());route.set(actual);
            return new AgentRuntimeV2EffectService.Result(List.of(receipt),false);
        });
        when(f.service.current(any(),isNull(),any(),any())).thenAnswer(call->{cn.jia.agent.service.impl.AgentRuntimeV2ExecutionServiceImpl.outside();assertSame(route.get(),call.getArgument(2));assertArrayEquals(receipt,call.getArgument(3));return true;});
        var sent=new ArrayList<TextMessage>();assertTrue(f.adapter.intercept(f.socket,raw,sent::add));assertEquals(1,sent.size());assertArrayEquals(receipt,sent.getFirst().asBytes());
        verify(f.service).reconcile(any(),isNull(),any(),any());verify(f.service).current(any(),isNull(),any(),any());verifyNoMoreInteractions(f.service);verifyNoInteractions(f.registration);
    }
    @Test void inspectRequiresCommittedSocketAndUnregisteredEffectCannotSendWhenReceiptOnlyServiceRejects() throws Exception {
        for(var request:List.<AgentRuntimeV2EffectCodec.Request>of(new AgentRuntimeV2EffectCodec.Inspect(grant()),new AgentRuntimeV2EffectCodec.EffectRequest(grant(),UUID.randomUUID().toString(),"eff_"+"c".repeat(64),AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,new AgentRuntimeV2EffectCodec.ProcessStart("task-a",null,"legacy-codex","workspace-a"),null),reconcile())){
            byte[] raw=AgentRuntimeV2EffectCodec.encode(request);var f=fixture(true);byte[] receipt=response(request,raw);result(f,request,raw,receipt);
            var sent=new ArrayList<TextMessage>();assertTrue(f.adapter.intercept(f.socket,raw,sent::add));assertEquals(1,sent.size());assertArrayEquals(receipt,sent.getFirst().asBytes());
            verify(f.service).current(same((AgentRuntimeV2RegistrationContext)f.socket.getAttributes().get(AgentRuntimeV2WebSocketAdapter.IDENTITY_ATTRIBUTE)),eq(f.committed),argThat(route->route.sessionFence().equals(f.committed.sessionFence())&&route.socketOwnerInstanceId().equals(f.committed.socketOwnerInstanceId())&&route.socketOwnerEpoch()==f.committed.socketOwnerEpoch()),eq(receipt));
            if(!(request instanceof AgentRuntimeV2EffectCodec.Reconcile)){var absent=fixture(false);if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest)when(absent.service.effect(any(),isNull(),any(),eq(raw))).thenReturn(AgentRuntimeV2EffectService.Result.closed());assertTrue(absent.adapter.intercept(absent.socket,raw,message->fail("missing tuple cannot execute/send")));if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest){verify(absent.service).effect(any(),isNull(),any(),eq(raw));verifyNoMoreInteractions(absent.service);}else verifyNoInteractions(absent.service);verify(absent.socket).close(CloseStatus.PROTOCOL_ERROR);}
        }
    }
    @Test void unregisteredExactReceiptRouteRechecksCurrentAclAndRetirementBeforeSendWithoutRegistration() throws Exception {
        for(String fault:List.of("none","acl","retired")){
            var f=fixture(false);var g=grant();var q=new AgentRuntimeV2EffectCodec.EffectRequest(g,UUID.randomUUID().toString(),"eff_"+"c".repeat(64),AgentRuntimeV2EffectCodec.Kind.FILE_INPUT_READ,AgentRuntimeV2EffectCodec.Phase.READ,new AgentRuntimeV2EffectCodec.FileRead("exec-a","task-a","item-a","run-a","input-a",0,65536),null);byte[] raw=AgentRuntimeV2EffectCodec.encode(q),content=new byte[65536];var read=new AgentRuntimeV2EffectCodec.ReadResult(0,65536,65536,AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(content)),content);byte[] receipt=AgentRuntimeV2EffectCodec.encodeReceipt(new AgentRuntimeV2EffectCodec.Receipt("command.execution.effect.receipt",g.requestId(),g.runtimeDispatchId(),UUID.randomUUID().toString(),AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(raw)),"READ",1000,read));assertTrue(receipt.length>98304);
            var trusted=new AtomicReference<AgentRuntimeV2EffectService.TrustedRoute>();when(f.service.effect(any(),isNull(),any(),eq(raw))).thenAnswer(call->{trusted.set(call.getArgument(2));return new AgentRuntimeV2EffectService.Result(List.of(receipt),false);});when(f.service.current(any(),isNull(),any(),eq(receipt))).thenAnswer(call->{assertSame(trusted.get(),call.getArgument(2));if(fault.equals("retired"))f.adapter.closed(f.socket);return !fault.equals("acl");});var sent=new ArrayList<TextMessage>();assertTrue(f.adapter.intercept(f.socket,raw,sent::add));assertEquals(fault.equals("none")?1:0,sent.size());if(!sent.isEmpty())assertArrayEquals(receipt,sent.getFirst().asBytes());verifyNoInteractions(f.registration);verify(f.service).effect(any(),isNull(),any(),eq(raw));verify(f.service).current(any(),isNull(),any(),eq(receipt));verifyNoMoreInteractions(f.service);
        }
    }
    @Test void actualCurrentAclReadBlockedAcrossNonblockingRetirementSendsZeroForBothPresentAndAbsentSocket() throws Exception {
        for(boolean committed:new boolean[]{false,true}){
            var f=fixture(committed);var request=reconcile();byte[] raw=AgentRuntimeV2EffectCodec.encode(request),receipt=response(request,raw);result(f,request,raw,receipt);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var sent=new ArrayList<TextMessage>();
            when(f.service.current(any(),nullable(AgentRuntimeV2SocketTuple.class),any(),any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return true;});
            try(var executor=Executors.newSingleThreadExecutor()){
                var future=executor.submit(()->f.adapter.intercept(f.socket,raw,sent::add));
                try{assertTrue(entered.await(5,TimeUnit.SECONDS));if(committed)f.adapter.retireDispatchRoute(f.committed);else f.adapter.closed(f.socket);assertTrue(f.socket.isOpen(),"no physical close is needed to invalidate the route");}finally{release.countDown();}
                assertTrue(future.get(5,TimeUnit.SECONDS));assertTrue(sent.isEmpty());
            }
            verify(f.service).current(any(),nullable(AgentRuntimeV2SocketTuple.class),any(),any());
        }
    }
    @Test void postCleanupFalseAclAndMismatchedOriginalResponseFamilyIdDispatchOrShaCannotReachSender() throws Exception {
        for(String fault:List.of("acl","family","request","dispatch","sha","invalid")){
            var f=fixture(false);var request=reconcile();byte[] raw=AgentRuntimeV2EffectCodec.encode(request),receipt=response(request,raw);
            if(!fault.equals("acl")&&!fault.equals("invalid")){
                var good=AgentRuntimeV2EffectCodec.decodeReceipt(receipt);
                receipt=AgentRuntimeV2EffectCodec.encodeReceipt(new AgentRuntimeV2EffectCodec.Receipt(fault.equals("family")?"command.execution.effect.receipt":good.messageType(),fault.equals("request")?UUID.randomUUID().toString():good.requestId(),fault.equals("dispatch")?UUID.randomUUID().toString():good.runtimeDispatchId(),good.receiptId(),fault.equals("sha")?"d".repeat(64):good.requestSha256(),"UNAVAILABLE",good.serverAt(),null));
            }
            if(fault.equals("invalid"))receipt="{}".getBytes(StandardCharsets.UTF_8);
            result(f,request,raw,receipt);if(fault.equals("acl"))when(f.service.current(any(),isNull(),any(),any())).thenReturn(false);
            assertTrue(f.adapter.intercept(f.socket,raw,message->fail("invalid result cannot send")));
            if(!fault.equals("acl")){verify(f.service,never()).current(any(),nullable(AgentRuntimeV2SocketTuple.class),any(),any());verify(f.socket).close(CloseStatus.PROTOCOL_ERROR);}
        }
    }
    @Test void malformedR4FamiliesNeverEnterLegacyOrAnyReducerAndTopLevelClassificationIgnoresNestedPayload() throws Exception {
        var f=fixture(false);var request=reconcile();String valid=new String(AgentRuntimeV2EffectCodec.encode(request),StandardCharsets.UTF_8);
        for(String bad:List.of(" "+valid,"\ufeff"+valid,valid.replace("\"schemaVersion\":2,","\"schemaVersion\":2,\"schemaVersion\":2,"),valid.replace("\"schemaVersion\":2","\"schemaVersion\":\"2\""),valid.replace("\"subjectKind\":\"EXECUTION\"","\"subjectKind\":\"execute\""),valid.replace("\"messageType\":\"command.execution.reconcile.request\"","\"messageType\":\"command.execution.reconcile.other\"")))assertTrue(f.adapter.intercept(f.socket,bad.getBytes(StandardCharsets.UTF_8),message->fail("malformed original must close")));
        assertFalse(f.adapter.intercept(f.socket,"{\"messageType\":\"chat\",\"content\":{\"messageType\":\"command.execution.effect.request\"}}".getBytes(StandardCharsets.UTF_8),message->fail()));
        verifyNoInteractions(f.service,f.registration);
    }
    @Test void absentOrFalseEffectIsolationKeepsHelloLegacyAndBlocksRegistrationBeforeAnyServiceMutation() throws Exception {
        for (boolean absent : new boolean[]{true, false}) {
            var evidence = ready();
            if (absent) evidence.remove(AgentRuntimeV2Readiness.Dependency.EFFECT_ISOLATION);
            else evidence.put(AgentRuntimeV2Readiness.Dependency.EFFECT_ISOLATION, () -> false);
            var readiness = new AgentRuntimeV2Readiness(evidence);
            assertFalse(readiness.ready());
            var service = mock(AgentRuntimeV2RegistrationService.class);
            var adapter = new AgentRuntimeV2WebSocketAdapter(provider(service), provider(readiness));
            var socket = session(absent ? "effect-isolation-absent" : "effect-isolation-false");
            adapter.established(socket);
            assertArrayEquals(new int[]{1}, adapter.supportedProtocolVersions());
            var responses = new ArrayList<TextMessage>();
            assertTrue(adapter.intercept(socket, createWire(), responses::add));
            assertEquals(1, responses.size());
            assertEquals("UNAVAILABLE", AgentRuntimeV2RegistrationCodec.decodeRejected(responses.getFirst().asBytes()).reasonCode());
            adapter.maintainRouting();
            verifyNoInteractions(service);
        }
    }

    @Test void productionFactoryCannotInferEffectIsolationFromActivationOrOldCapabilityProviders() {
        new ApplicationContextRunner().withUserConfiguration(AgentRuntimeV2WebSocketConfiguration.class)
                .withPropertyValues("agent.runtime-v2-registration.activation-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var readiness = context.getBean(AgentRuntimeV2Readiness.class);
                    @SuppressWarnings("unchecked")
                    var evidence = (Map<AgentRuntimeV2Readiness.Dependency, BooleanSupplier>) ReflectionTestUtils.getField(readiness, "evidence");
                    assertNotNull(evidence);
                    assertFalse(evidence.containsKey(AgentRuntimeV2Readiness.Dependency.EFFECT_ISOLATION), "no production isolation provider has been authorized or activated");
                    assertFalse(readiness.ready());
                    assertArrayEquals(new int[]{1}, readiness.supportedProtocolVersions());
                });
    }
}
