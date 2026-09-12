package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SkillInstallRecoveryServiceTest {
    @Test void reconnectRestoresSameInboxMessageAttemptAndWireNeverMintsAnotherInstallation() {
        var mapper=mock(AgentSkillInstallRecoveryMapper.class);var dao=mock(AgentCommandRecoveryDao.class);
        var inbox=mock(AgentCommandInboxService.class);var dispatch=mock(SkillInstallDispatchService.class);
        var service=new SkillInstallRecoveryService(mapper,dao,SkillMarketplaceRealTransactionTest.provider(inbox),
                SkillMarketplaceRealTransactionTest.provider(mock(AgentRabbitSafetyGate.class)),dispatch,mock(PlatformTransactionManager.class));
        long now=System.currentTimeMillis();
        var draft=new AgentCommandDraft(1,"cmd_skill_i","o","i","t","c","o",null,"a","SKILL_INSTALL",now-120000,now+3480000,
                new AgentSkillInstallPayload("o","i","pv","repo-test","1.0.0","100","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/i/package"));
        var business=AgentCommandCanonicalCodec.businessBytes(draft);var wire=AgentCommandCanonicalCodec.wireBytes(draft,"message",1);
        var d=new AgentCommandDeliveryEntity().setCommandId(draft.commandId()).setTaskId("o").setTargetAgentId("a").setCommandType("SKILL_INSTALL")
                .setCommandPayload(business).setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business)).setStatus("WAITING_AGENT")
                .setActiveMessageId("message").setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setVersion(7L);
        d.setId(1L);d.setTenantId("t");d.setClientId("c");d.setUpdateTime(now-70000);
        var o=new AgentOutboxEventEntity().setEventId("event").setMessageId("message").setCommandId(draft.commandId()).setDeliveryId(1L)
                .setActiveAttempt(1).setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("PUBLISHED")
                .setPublisherConfirmStatus("ACK").setMandatoryReturnStatus("NOT_RETURNED").setConfirmedAt(now-80000).setPublishedAt(now-80000).setExpiresAt(draft.expiresAt());
        var i=new AgentConsumerInboxEntity().setEventId("event").setMessageId("message").setCommandId(draft.commandId()).setDeliveryId(1L)
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("WAITING_AGENT").setResultStatus("WAITING_AGENT").setExpiresAt(draft.expiresAt());
        when(dao.lockDelivery("t","c",1L)).thenReturn(d);when(dao.lockActiveOutboxes("t","c",1L,"message")).thenReturn(List.of(o));
        when(dao.lockInbox("t","c",AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,"message")).thenReturn(i);
        when(mapper.retryDelivery(d,now,now+1)).thenReturn(1);when(mapper.retryInbox(i,now,now+1)).thenReturn(1);
        when(inbox.claim(any(),anyString(),anyLong(),eq(60000L))).thenAnswer(x->{
            AgentInboxMessage message=x.getArgument(0);assertEquals("message",message.messageId());assertEquals("event",message.eventId());
            assertArrayEquals(wire,message.rawWireBytes());return AgentInboxClaim.inFlight(1);
        });
        service.recover(d,now);verifyNoInteractions(dispatch);assertEquals(1,d.getActiveAttempt());assertEquals("message",d.getActiveMessageId());
        verify(mapper).retryDelivery(d,now,now+1);verify(mapper).retryInbox(i,now,now+1);verify(dao,never()).insertOutbox(any());
        d.setExpiresAt(now);assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        verify(inbox,times(1)).claim(any(),anyString(),anyLong(),anyLong());
    }
}
