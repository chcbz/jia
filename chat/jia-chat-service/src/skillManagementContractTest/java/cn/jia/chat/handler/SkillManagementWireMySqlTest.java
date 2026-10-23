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
        var handler=new Wire(provider);handler.setRuntimeAuthentication(authentication);handler.setSkillManagement(service);return handler;
    }
    WebSocketSession socket(List<String> frames) throws Exception {
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("sm-socket");when(session.isOpen()).thenReturn(true);
        Map<String,Object> attrs=new HashMap<>();attrs.put("agentId",AGENT);attrs.put("jiacn",ACTOR.ownerJiacn());attrs.put("clientId",ACTOR.clientId());attrs.put("tenantId","0");attrs.put("runtimeInstanceId","runtime-1");
        attrs.put(AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,proof());when(session.getAttributes()).thenReturn(attrs);
        when(session.getHandshakeHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        doAnswer(call->{frames.add(((TextMessage)call.getArgument(0)).getPayload());return null;}).when(session).sendMessage(any());
        when(authentication.bind(eq("sm-socket"),any(),any())).thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1","0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT,"runtime-1",runtime.getRuntimeInstallationId(),"host-1",1L,true));
        when(authentication.isCurrentBinding(any(),any(),any(),any(),any(),any())).thenReturn(true);return session;
    }
    Map<String,Object> declaration(Map<String,Object> c,String revision) {
        var target=object(c.get("target"));return map("schemaVersion",2,"catalogRevision",revision,"skills",List.of(map("skillInstallationId",target.get("skillInstallationId"),
            "productVersionId",target.get("productVersionId"),"skillKey",c.get("skillKey"),"skillVersion",target.get("skillVersion"),"packageSha256",target.get("packageSha256"),
            "manifestSha256",null,"kind","INSTRUCTION","policyRevision",c.get("skillRevision"),"enabled",c.get("desiredEnabled"),"runner",null,"readiness","NOT_EXECUTABLE", "reasonCode",null,"toolchainDigest",null,"bindingRevision",null,"observedAt",System.currentTimeMillis())));
    }
    void register(Wire handler,WebSocketSession session,Object report) {
        var data=map("agentId",AGENT,"runtimeInstanceId","runtime-1","installationId",runtime.getRuntimeInstallationId(),"hostId","host-1","sessionGeneration","1",
            "name","SM fixture","durableStateHealthy",true,"readyCommandTypes",List.of());if(report!=null)data.put("skillExecution",report);
        handler.accept(session,map("protocolVersion",1,"requestId","req-register","messageType","agent.register","messageId","reg-sm","data",data));
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
        var user=new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").header("alg","none").subject(ACTOR.actorId())
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
        var frames=new ArrayList<String>();var socket=socket(frames);register(handler,socket,null);
        assertTrue(frames.stream().anyMatch(f->f.contains("agent.registered")),frames.toString());
        var c=installOverHttp();recovery.recover();verify(sessions,atLeastOnce()).dispatch(anyString(),anyString(),anyString(),any(),any());
        handler.accept(socket,result(c,evidence(c,"PREPARED",null)));c=current(c);assertEquals("ACTIVATING",c.get("status"));
        assertTrue(frames.stream().anyMatch(f->f.contains("work.result.receipt")&&f.contains("ACCEPTED")),frames.toString());
        var applied=result(c,evidence(c,"APPLIED",null));handler.accept(socket,applied);c=current(c);assertEquals("VERIFYING",c.get("status"));
        register(handler,socket,declaration(c,"2"));c=current(c);assertEquals("SUCCEEDED",c.get("status"));assertEquals(true,c.get("runtimeSynced"));
        assertEquals("CAPTURED",c.get("fundingStatus"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_outbox_event",Integer.class));
        handler.accept(socket,applied);assertEquals("SUCCEEDED",current(c).get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM economy_skill_order",Integer.class));
        // A new frame with the same message ID but contradictory bytes cannot be accepted.
        var conflicting=new LinkedHashMap<>(applied);var d=new LinkedHashMap<>(object(applied.get("data")));d.put("resultSha256","f".repeat(64));conflicting.put("data",d);
        handler.accept(socket,conflicting);assertTrue(frames.getLast().contains("SKILL_RESULT_CONFLICT"));
        handler.afterConnectionClosed(socket,CloseStatus.NORMAL);
        assertEquals(SkillCapabilityLookup.State.DISCONNECTED,registry.lookup(new SkillCapabilityLookup.Scope("0",ACTOR.clientId(),ACTOR.ownerJiacn(),AGENT),new AgentManagedSessionLookup.SessionFence(runtime.getRuntimeInstallationId(),"host-1","runtime-1",1L)).state());
    }
}
