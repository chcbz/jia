package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.skill.SkillNativeAckMySqlTest;
import cn.jia.chat.config.AgentRuntimeHandshakeInterceptor;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real MySQL order/ACK/package/result/ACTIVE with actual production WS ingress and receipt.
 * Native persistence auth adapter is the same explicit trusted fixture as the order suite;
 * native persistent generation/security regressions run independently in this batch. */
public class SkillNativeResultMySqlTest extends SkillNativeAckMySqlTest {
    @Override protected Map<String,Object> acceptNativeResult(AgentRuntimeAuthenticationService.Proof proof,Map<String,Object> body) {
        var service=mock(AgentService.class);
        when(service.register(any())).thenAnswer(call->new AgentRegisterResultDTO(
                ((AgentRegisterDTO)call.getArgument(0)).getAgentId(),null,"online"));
        @SuppressWarnings("unchecked") ObjectProvider<AgentService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(2)).get()).when(authentication).withFence(eq(proof),eq(true),any());
        var handler=new AgentWebSocketHandler(mock(ChatClient.class),provider,mock(ChatMessageDao.class),mock(ChatConversationEventBroker.class));
        handler.setRuntimeAuthentication(authentication);handler.setSkillResults(results);
        var session=mock(WebSocketSession.class);when(session.getId()).thenReturn("native-result-fixture");when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of("agentId",proof.scope().agentId(),"tenantId",proof.scope().tenantId(),
                "jiacn",proof.scope().ownerJiacn(),"clientId",proof.scope().clientId(),"runtimeInstanceId",proof.scope().runtimeInstanceId(),
                AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,proof)));
        when(authentication.isCurrentBinding(any(),any(),any(),any(),any(),any())).thenReturn(true);
        var json=new tools.jackson.databind.ObjectMapper();var sent=new ArrayList<Map<String,Object>>();
        try {
            doAnswer(call->{sent.add(json.readValue(((TextMessage)call.getArgument(0)).getPayload(),Map.class));return null;}).when(session).sendMessage(any());
            handler.afterConnectionEstablished(session);
            var register=new LinkedHashMap<String,Object>();register.put("messageType","agent.register");register.put("agentId",proof.scope().agentId());
            register.put("installationId",proof.installationId());register.put("hostId",proof.hostId());register.put("runtimeInstanceId",proof.scope().runtimeInstanceId());
            register.put("sessionGeneration",proof.sessionGeneration());register.put("readyCommandTypes",List.of("SKILL_INSTALL"));register.put("durableStateHealthy",true);
            handler.handleTextMessage(session,new TextMessage(json.writeValueAsString(register)));
            assertTrue(sent.stream().anyMatch(x->"agent_registered".equals(x.get("type"))),"real registration must precede result");sent.clear();
            var wire=new LinkedHashMap<>(body);wire.put("tenantId",proof.scope().tenantId());wire.put("clientId",proof.scope().clientId());
            wire.put("hostId",proof.hostId());wire.put("sessionGeneration",proof.sessionGeneration());
            handler.handleTextMessage(session,new TextMessage(json.writeValueAsString(wire)));
            var receipts=sent.stream().filter(x->"work.result.receipt".equals(x.get("type"))).toList();
            if(receipts.isEmpty()) throw new IllegalArgumentException("WS_RESULT_REJECTED");
            assertEquals(1,receipts.size(),"result must durably commit through actual WS branch");
            @SuppressWarnings("unchecked") Map<String,Object> receipt=(Map<String,Object>)receipts.getFirst().get("data");
            assertEquals(body.get("installationId"),receipt.get("installationId"));assertEquals("ACCEPTED",receipt.get("receiptStatus"));return receipt;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
