package cn.jia.chat.handler;

import cn.jia.agent.skill.SkillManagementTransactionTest;
import cn.jia.agent.api.SkillMarketplaceController;
import cn.jia.agent.skill.SkillMarketplaceService;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.*;
import cn.jia.agent.entity.*;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.skill.SkillManagementCodec.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Java WS ingress and directory consumer with real SQL/money/outbox state.
 * The Runtime peer and socket IO remain explicit test inputs: this is NOT Node/three-client C01. */
public class SkillManagementWireMySqlTest extends SkillManagementTransactionTest {
    static final class Wire extends AgentWebSocketHandler {
        Wire(ObjectProvider<AgentService> agents){super(mock(ChatClient.class),agents,mock(ChatMessageDao.class),mock(ChatConversationEventBroker.class));}
        void accept(WebSocketSession session,Map<String,Object> value){handleTextMessage(session,new TextMessage(json(value)));}
        void raw(WebSocketSession session,String value){handleTextMessage(session,new TextMessage(value));}
    }
    @SuppressWarnings("unchecked") Wire wire() {
        var agents=mock(AgentService.class);when(agents.register(any())).thenReturn(new AgentRegisterResultDTO(AGENT,"synthetic-token","online"));
        ObjectProvider<AgentService> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(agents);
        // The SQL fixture already executes non-registration fences. Registration has its own
        // real consumer path and must also execute the supplied operation, not return mock null.
        if(mockingDetails(authentication).isMock())doAnswer(call->{
            assertEquals(proof(),call.getArgument(0));Supplier<?> operation=call.getArgument(2);
            return tx.execute(status->operation.get());
        }).when(authentication).withFence(any(),eq(true),any());
        var handler=new Wire(provider);handler.setRuntimeAuthentication(authentication);handler.setSkillManagement(service);return handler;
    }
    WebSocketSession socket(List<String> frames) throws Exception {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("sm-socket");when(session.isOpen()).thenReturn(true);
        Map<String,Object> attrs=new HashMap<>();attrs.put("agentId",AGENT);attrs.put("jiacn",ACTOR.ownerJiacn());attrs.put("clientId",ACTOR.clientId());attrs.put("tenantId","0");attrs.put("runtimeInstanceId","runtime-1");
        attrs.put(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,proof());when(session.getAttributes()).thenReturn(attrs);
        when(session.getHandshakeHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        doAnswer(call->{frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        if(mockingDetails(authentication).isMock()) {
            when(authentication.bind(eq("sm-socket"),any(),any())).thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1","0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT,"runtime-1",runtime.getRuntimeInstallationId(),"host-1",1L,true));
            when(authentication.isCurrentBinding(any(),any(),any(),any(),any(),any())).thenReturn(true);
        }
        return session;
    }
    Map<String,Object> declaration(Map<String,Object> c,String revision) {
        var target=object(c.get("target"));return map("schemaVersion",2,"catalogRevision",revision,"skills",List.of(map("skillInstallationId",target.get("skillInstallationId"),
            "productVersionId",target.get("productVersionId"),"skillKey",c.get("skillKey"),"skillVersion",target.get("skillVersion"),"packageSha256",target.get("packageSha256"),
            "manifestSha256",null,"kind","INSTRUCTION","policyRevision",c.get("skillRevision"),"enabled",c.get("desiredEnabled"),"runner",null,"readiness","NOT_EXECUTABLE", "reasonCode",null,"toolchainDigest",null,"bindingRevision",null,"observedAt",System.currentTimeMillis())));
    }
    void register(Wire handler,WebSocketSession session,Object report,List<String> frames) {
        var data=map("agentId",AGENT,"runtimeInstanceId","runtime-1","runtimeInstallationId",runtime.getRuntimeInstallationId(),"hostId","host-1","sessionGeneration","1",
            "name","SM fixture","durableStateHealthy",true,"readyCommandTypes",List.of());if(report!=null)data.put("skillExecution",report);
        var envelope=map("protocolVersion",1,"requestId","req-register","messageType","agent.register","messageId","reg-sm","data",data);
        int before=frames.size();handler.accept(session,envelope);
        var replies=frames.subList(before,frames.size());
        assertTrue(replies.stream().anyMatch(f->"agent.registered".equals(read(f.getBytes(java.nio.charset.StandardCharsets.UTF_8),null).get("messageType"))),
            ()->"Registration must succeed before further traffic; sent="+json(envelope)+" replies="+replies);
        assertTrue(replies.stream().noneMatch(f->"protocol.error".equals(read(f.getBytes(java.nio.charset.StandardCharsets.UTF_8),null).get("messageType"))),
            ()->"Registration produced an error; sent="+json(envelope)+" replies="+replies);
    }
    Map<String,Object> installOverHttp() throws Exception {
        // MockMvc substitutes servlet transport only; the management service and all SQL,
        // posting, outbox and result consumers are the real fixture objects, not mocks.
        var retired=mock(SkillMarketplaceService.class);
        var controller=new SkillMarketplaceController(retired);controller.setManagement(service);
        List<HttpMessageConverter<?>> converters=new ArrayList<>();converters.add(new ByteArrayHttpMessageConverter());converters.add(new JacksonJsonHttpMessageConverter());
        new ApiEnvelopeJsonConfig().extendMessageConverters(converters);
        var http=MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ApiEnvelopeAdvice(),new ApiRequestEnvelopeAdvice(),new ExceptionHandlerAdvice())
            .setMessageConverters(converters.toArray(HttpMessageConverter[]::new)).addFilters(new RequestIdFilter()).build();
        var user=new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").claim("tenant_id", ACTOR.tenantId()).header("alg","none").subject(ACTOR.actorId())
            .claim("client_id",ACTOR.clientId()).claim("jiacn",ACTOR.ownerJiacn()).build(),List.of());
        var request=quoteData();
        var quote=http.perform(post("/agent/skill-orders/quotes").principal(user).contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id","http-quote").header("Idempotency-Key","http-quote-key")
            .content(bytes(map("protocolVersion",1,"requestId","http-quote","data",request))))
            .andExpect(status().isOk()).andExpect(header().string("X-Request-Id","http-quote")).andReturn();
        var q=object(read(quote.getResponse().getContentAsByteArray(),"QuoteResponse").get("data"));
        var accepted=http.perform(post("/agent/"+AGENT+"/skill-changes").principal(user).contentType(MediaType.APPLICATION_JSON)
            .header("X-Request-Id","http-create").header("Idempotency-Key","http-create-key")
            .content(bytes(map("protocolVersion",1,"requestId","http-create","data",creation(q,request)))))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","private, no-store")).andReturn();
        verifyNoInteractions(retired);
        return object(read(accepted.getResponse().getContentAsByteArray(),"ChangeResponse").get("data"));
    }
    @Test void firstInstallResultsAndRegistrationDriveRealCommittedClosedLoop() throws Exception {
        var handler=wire();var registry=new SkillExecutionSessionRegistry();handler.setSkillExecutionSessions(registry);
        when(capabilities.lookup(any(),any())).thenAnswer(call->registry.lookup(call.getArgument(0),call.getArgument(1)));
        var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        assertTrue(frames.stream().anyMatch(f->f.contains("agent.registered")),frames.toString());
        var c=installOverHttp();recovery.recover();verify(sessions,atLeastOnce()).dispatch(anyString(),anyString(),anyString(),any(),any());
        handler.accept(socket,result(c,evidence(c,"PREPARED",null)));c=current(c);assertEquals("ACTIVATING",c.get("status"));
        assertTrue(frames.stream().anyMatch(f->f.contains("work.result.receipt")&&f.contains("ACCEPTED")),frames.toString());
        var applied=result(c,evidence(c,"APPLIED",null));handler.accept(socket,applied);c=current(c);assertEquals("VERIFYING",c.get("status"));
        register(handler,socket,declaration(c,"2"),frames);c=current(c);assertEquals("SUCCEEDED",c.get("status"));assertEquals(true,c.get("runtimeSynced"));
        assertEquals("CAPTURED",c.get("fundingStatus"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event",Integer.class));
        handler.accept(socket,applied);assertEquals("SUCCEEDED",current(c).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order",Integer.class));
        // A tampered digest is rejected; a separately tested validly rehashed message-ID conflict is distinct.
        var conflicting=new LinkedHashMap<>(applied);var d=new LinkedHashMap<>(object(applied.get("data")));d.put("resultSha256","f".repeat(64));conflicting.put("data",d);
        handler.accept(socket,conflicting);assertTrue(frames.getLast().contains("SKILL_RESULT_CONFLICT"));
        handler.afterConnectionClosed(socket,CloseStatus.NORMAL);
        assertEquals(SkillCapabilityLookup.State.DISCONNECTED,registry.lookup(new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT),new AgentManagedSessionLookup.SessionFence(runtime.getRuntimeInstallationId(),"host-1","runtime-1",1L)).state());
    }
    private void assertWireRejected(Wire handler,WebSocketSession socket,List<String> frames,Map<String,Object> envelope,String expectedCode) {
        var before=businessSnapshot();int count=frames.size();handler.accept(socket,envelope);
        assertEquals(count+1,frames.size(),()->"Rejected wire must produce one diagnostic reply: sent="+json(envelope)+" frames="+frames);
        var reply=read(frames.getLast().getBytes(java.nio.charset.StandardCharsets.UTF_8),null);
        assertEquals(expectedCode,reply.get("code"),()->"Unexpected rejection: sent="+json(envelope)+" reply="+frames.getLast());
        assertEquals(envelope.get("messageId"),reply.get("correlationId"));
        assertEquals(before,businessSnapshot(),"Rejected wire changed SQL business facts");
    }
    private static Map<String,Object> resultWithValidDigest(Map<String,Object> envelope) {
        var data=object(envelope.get("data"));data.remove("resultSha256");data.put("resultSha256",hash("cyf.skill.result.v2",data));
        validate("Result",envelope);return envelope;
    }
    @Test void persistentProofRevocationAndRuntimeIdentityDriftRejectAckAndResultWithoutSqlMutation() throws Exception {
        usePersistentAuthentication();var handler=wire();var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        var c=installOverHttp();var ack=acknowledgement(c);var result=result(c,evidence(c,"PREPARED",null));
        String verifier=jdbc.queryForObject("SELECT token_hash FROM agent_runtime",String.class);
        var drifts=List.of(
            new String[]{"UPDATE agent_runtime SET runtime_session_generation=2","UPDATE agent_runtime SET runtime_session_generation=1"},
            new String[]{"UPDATE agent_runtime SET runtime_instance_id='runtime-2'","UPDATE agent_runtime SET runtime_instance_id='runtime-1'"},
            new String[]{"UPDATE agent_runtime SET runtime_host_id='foreign-host'","UPDATE agent_runtime SET runtime_host_id='host-1'"},
            new String[]{"UPDATE agent_runtime SET runtime_installation_id='rti_"+"c".repeat(32)+"'","UPDATE agent_runtime SET runtime_installation_id='"+runtime.getRuntimeInstallationId()+"'"},
            new String[]{"UPDATE agent_runtime SET owner_jiacn='foreign-owner'","UPDATE agent_runtime SET owner_jiacn='"+ACTOR.ownerJiacn()+"'"},
            new String[]{"UPDATE agent_runtime SET client_id='foreign-client'","UPDATE agent_runtime SET client_id='"+ACTOR.clientId()+"'"},
            new String[]{"UPDATE agent_runtime SET agent_id='foreign-agent'","UPDATE agent_runtime SET agent_id='"+AGENT+"'"},
            new String[]{"UPDATE agent_runtime SET token_hash='urs1:"+"c".repeat(64)+":1:1'","UPDATE agent_runtime SET token_hash='"+verifier+"'"},
            new String[]{"UPDATE agent_runtime SET token_hash='urs1:"+proof().tokenDigest()+":1:2'","UPDATE agent_runtime SET token_hash='"+verifier+"'"},
            new String[]{"UPDATE agent_runtime SET token_hash='urs1:"+proof().tokenDigest()+":2:1'","UPDATE agent_runtime SET token_hash='"+verifier+"'"},
            new String[]{"UPDATE agent_runtime SET binding_id=NULL","UPDATE agent_runtime SET binding_id=7"},
            new String[]{"UPDATE agent_runtime_v1_installation SET status='REVOKED'","UPDATE agent_runtime_v1_installation SET status='ACTIVE'"}
        );
        for(var drift:drifts) {
            jdbc.update(drift[0]);
            try {
                assertWireRejected(handler,socket,frames,ack,"UNAUTHENTICATED");
                assertWireRejected(handler,socket,frames,result,"UNAUTHENTICATED");
                // Exercise the commit fence too, rather than relying only on WS preflight validation.
                var before=businessSnapshot();
                assertThrows(RuntimeException.class,()->service.acknowledge(proof(),ack),drift[0]);
                assertThrows(RuntimeException.class,()->service.result(proof(),result),drift[0]);
                assertEquals(before,businessSnapshot(),drift[0]);
            } finally {jdbc.update(drift[1]);}
        }
        // The same original frames remain valid after restoring this fixture's authority.
        int before=frames.size();handler.accept(socket,ack);assertEquals(before,frames.size(),frames.toString());
        handler.accept(socket,result);assertEquals("E0",read(frames.getLast().getBytes(java.nio.charset.StandardCharsets.UTF_8),"ResultReceipt").get("code"));
        assertEquals("ACTIVATING",current(c).get("status"));
    }
    @Test void currentProtocolRejectsWrongIdentityAttemptFenceEpochAndCorrelationWithNoSqlEffects() throws Exception {
        usePersistentAuthentication();var handler=wire();var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        var c=installOverHttp();
        for(String field:List.of("attempt","fencingToken","deliveryEpoch","commandId","skillChangeId")) {
            var ack=acknowledgement(c);var data=object(ack.get("data"));
            data.put(field,field.equals("attempt")?2:Set.of("fencingToken","deliveryEpoch").contains(field)?"2":"foreign-id");validate("CommandAck",ack);
            assertWireRejected(handler,socket,frames,ack,field.equals("commandId")?"SKILL_RESULT_CONFLICT":"SKILL_DELIVERY_FENCED");
        }
        var wrongCorrelation=acknowledgement(c);wrongCorrelation.put("correlationId","foreign-message");validate("CommandAck",wrongCorrelation);
        assertWireRejected(handler,socket,frames,wrongCorrelation,"SKILL_RESULT_CONFLICT");
        for(String field:List.of("attempt","fencingToken","deliveryEpoch","skillRevision","agentId","runtimeInstallationId","commandId","skillChangeId")) {
            var invalid=result(c,evidence(c,"PREPARED",null));var data=object(invalid.get("data"));
            data.put(field,field.equals("attempt")?2:Set.of("fencingToken","deliveryEpoch","skillRevision").contains(field)?"2":"foreign-id");
            resultWithValidDigest(invalid);
            assertWireRejected(handler,socket,frames,invalid,field.equals("commandId")?"SKILL_RESULT_CONFLICT":"SKILL_DELIVERY_FENCED");
        }
        handler.accept(socket,result(c,evidence(c,"PREPARED",null)));assertEquals("ACTIVATING",current(c).get("status"));
    }
    @Test void validlyRehashedContradictorySameMessageIdCannotReplaceCommittedReceiptOrFinancialFacts() throws Exception {
        usePersistentAuthentication();var handler=wire();var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        var c=installOverHttp();var original=result(c,evidence(c,"PREPARED",null));handler.accept(socket,original);
        var accepted=frames.getLast();validate("ResultReceipt",read(accepted.getBytes(java.nio.charset.StandardCharsets.UTF_8),null));
        var conflicting=read(bytes(original),"Result");var evidence=object(object(conflicting.get("data")).get("evidence"));
        evidence.put("outcome","FAILED");evidence.put("preparedSha256",null);evidence.put("reasonCode","DEPENDENCY_MISSING");
        resultWithValidDigest(conflicting);
        assertNotEquals(object(original.get("data")).get("resultSha256"),object(conflicting.get("data")).get("resultSha256"));
        assertEquals(original.get("messageId"),conflicting.get("messageId"));
        assertWireRejected(handler,socket,frames,conflicting,"SKILL_RESULT_CONFLICT");
        var before=businessSnapshot();handler.accept(socket,original);assertEquals(accepted,frames.getLast());assertEquals(before,businessSnapshot());
        assertEquals("ACTIVATING",current(c).get("status"));assertEquals("HELD",current(c).get("fundingStatus"));
    }
    @Test void persistentSqlFenceStaysLockedThroughResultCommitAndWholeTransactionRollback() throws Exception {
        usePersistentAuthentication();var handler=wire();var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        var c=installOverHttp();var result=result(c,evidence(c,"PREPARED",null));var before=businessSnapshot();
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            assertThrows(IllegalStateException.class,()->tx.execute(status->{
                service.result(proof(),result);
                try {pool.submit(()->{
                    var separate=new org.springframework.jdbc.core.JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
                    var otherTx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
                    otherTx.executeWithoutResult(ignored->{
                    for(String table:List.of("agent_runtime","agent_runtime_v1_installation")) {
                        var denied=assertThrows(org.springframework.dao.DataAccessException.class,()->separate.queryForList("SELECT * FROM "+table+" FOR UPDATE NOWAIT"));
                        assertInstanceOf(java.sql.SQLException.class,denied.getMostSpecificCause());
                        assertEquals(3572,((java.sql.SQLException)denied.getMostSpecificCause()).getErrorCode(),"Must be MySQL NOWAIT lock refusal, not a fixture SQL error");
                    }
                    });
                }).get();} catch(Exception failure){throw new AssertionError("Persistent fence did not retain real SQL locks",failure);}
                throw new IllegalStateException("rollback after accepted result inside outer transaction");
            }));
        } finally {pool.shutdownNow();}
        assertEquals(before,businessSnapshot());handler.accept(socket,result);assertEquals("ACTIVATING",current(c).get("status"));
    }
    @Test void currentReconnectProofMaySubmitOriginalAuthorizedEpochWithoutRewritingExecutingBoot() throws Exception {
        usePersistentAuthentication();var handler=wire();var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null,frames);
        var c=installOverHttp();var original=result(c,evidence(c,"PREPARED",null));var oldProof=proof();
        jdbc.update("UPDATE agent_runtime SET runtime_instance_id='runtime-2',runtime_session_generation=2");
        var before=businessSnapshot();assertThrows(RuntimeException.class,()->service.result(oldProof,original));assertEquals(before,businessSnapshot());
        var scope=new AgentRuntimeAuthentication.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT,"runtime-2");
        var currentProof=new AgentRuntimeAuthenticationService.Proof(scope,oldProof.installationId(),oldProof.hostId(),2L,oldProof.tokenDigest(),oldProof.userId(),oldProof.authEpoch());
        var receipt=service.result(currentProof,original);assertEquals("ACCEPTED",object(receipt.get("data")).get("receiptStatus"));
        assertEquals("runtime-1",object(original.get("data")).get("executionRuntimeInstanceId"));assertEquals("1",object(original.get("data")).get("deliveryEpoch"));
        assertEquals(receipt,service.result(currentProof,original));assertEquals("ACTIVATING",current(c).get("status"));
    }

}
