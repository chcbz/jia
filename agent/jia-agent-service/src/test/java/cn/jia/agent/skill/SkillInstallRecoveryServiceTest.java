package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.mapper.AgentSkillInstallRecoveryMapper;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.economy.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class SkillInstallRecoveryServiceTest {
    private final long now=System.currentTimeMillis();
    private final AgentSkillInstallRecoveryMapper mapper=mock(AgentSkillInstallRecoveryMapper.class);
    private final AgentCommandRecoveryDao dao=mock(AgentCommandRecoveryDao.class);
    private final SkillInstallDispatchService dispatch=mock(SkillInstallDispatchService.class);
    private final EconomyPreviewGate preview=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","c"))));
    private final SkillInstallRecoveryService service=new SkillInstallRecoveryService(mapper,dao,preview,dispatch,mock(PlatformTransactionManager.class));
    private AgentCommandDeliveryEntity d;
    private AgentOutboxEventEntity o;
    void fixture() {
        var draft=new AgentCommandDraft(1,AgentCommandCanonicalCodec.skillInstallCommandId("0","c","owner-a","i"),"o","i","0","c","owner-a","o",null,"a","SKILL_INSTALL",now-120000,now+3480000,
                new AgentSkillInstallPayload("o","i","pv","repo-test","1.0.0","100","sha256:"+"a".repeat(64),"/internal/agent/skill-installations/i/package"));
        var business=AgentCommandCanonicalCodec.businessBytes(draft);var wire=AgentCommandCanonicalCodec.wireBytes(draft,"message",1);
        d=new AgentCommandDeliveryEntity().setOwnerJiacn("owner-a").setCommandId(draft.commandId()).setTaskId("o").setTargetAgentId("a").setCommandType("SKILL_INSTALL")
                .setCommandPayload(business).setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business)).setStatus("PENDING")
                .setActiveMessageId("message").setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setVersion(7L);
        d.setId(1L);d.setTenantId("0");d.setClientId("c");d.setUpdateTime(now-70000);
        o=new AgentOutboxEventEntity().setEventId("event").setMessageId("message").setCommandId(draft.commandId()).setDeliveryId(1L)
                .setAggregateType("skill-order").setAggregateId("o").setDestination(SkillInstallCommandWriter.DESTINATION).setRoutingKey("a")
                .setActiveAttempt(1).setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("PENDING")
                .setPublisherConfirmStatus("NONE").setMandatoryReturnStatus("NONE").setExpiresAt(draft.expiresAt()).setVersion(1L);
        o.setId(2L);o.setTenantId("0");o.setClientId("c");
        when(dao.lockDelivery("0","c",1L)).thenReturn(d);when(dao.lockActiveOutboxes("0","c",1L,"message")).thenReturn(List.of(o));
    }
    @Test void rejectsBrokerReceiptAndCrossScopeWithoutSending() {
        fixture();o.setPublisherConfirmStatus("ACK");assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        o.setPublisherConfirmStatus("NONE");o.setClientId("C");assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        verifyNoInteractions(dispatch);verify(mapper,never()).claimDelivery(any(),anyString(),anyLong(),anyLong());
    }
    @Test void rejectsWireDigestOrOwnerSubstitution() {
        fixture();o.setWirePayloadHash(new byte[32]);assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        o.setWirePayloadHash(AgentCommandCanonicalCodec.sha256(o.getWirePayload()));d.setOwnerJiacn("another-owner");
        assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));verifyNoInteractions(dispatch);
    }
    @Test void rejectsForeignDestinationAndModifiedExpiry() {
        fixture();o.setDestination("rabbit-command");assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        o.setDestination(SkillInstallCommandWriter.DESTINATION);d.setExpiresAt(now);assertThrows(SkillMarketplaceException.class,()->service.recover(d,now));
        verifyNoInteractions(dispatch);
    }
    @Test void invitationHintDoesNotCancelOriginalRecovery() {
        fixture();long later=now+7200000;
        when(mapper.claimDelivery(eq(d),anyString(),anyLong(),eq(later))).thenReturn(1);
        when(mapper.claimOutbox(eq(o),anyString(),anyLong(),eq(later))).thenReturn(1);
        when(dispatch.dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            assertArrayEquals(o.getWirePayload(),call.getArgument(6));d.setLeaseOwner("superseded");return AgentRawCommandDispatchResult.sent(1,1);
        });
        service.recover(d,later);verify(dispatch).dispatch(eq("0"),eq("c"),eq("owner-a"),eq("o"),eq("a"),eq(d.getCommandId()),any());
        assertEquals(1,d.getActiveAttempt());assertEquals("message",d.getActiveMessageId());
    }
    @Test void currentLeaseOrNotDueDoesNotClaimOrSend() {
        fixture();d.setLeaseOwner("other").setLeaseUntil(now+100);service.recover(d,now);
        d.setLeaseUntil(null).setNextRetryAt(now+100);service.recover(d,now);
        verifyNoInteractions(dispatch);verify(mapper,never()).claimDelivery(any(),anyString(),anyLong(),anyLong());
    }
    @Test void scanRequiresExactPreviewScope() {
        fixture();d.setClientId("C");when(mapper.candidates(anyLong(),anyLong())).thenReturn(List.of(d));service.recover();
        verifyNoInteractions(dao,dispatch);
    }
    @Test void supersededCompletionDoesNotClearAnotherOwnersLease() {
        fixture();when(mapper.claimDelivery(eq(d),anyString(),anyLong(),anyLong())).thenReturn(1);
        when(mapper.claimOutbox(eq(o),anyString(),anyLong(),anyLong())).thenReturn(1);
        when(dispatch.dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            assertArrayEquals(o.getWirePayload(),call.getArgument(6));d.setLeaseOwner("new-owner");return AgentRawCommandDispatchResult.sent(1,1);
        });
        service.recover(d,now);verify(mapper,never()).finishDelivery(any(),anyString(),anyString(),any(),anyString(),anyLong());
        verify(mapper,never()).finishOutbox(any(),anyString(),anyString(),any(),anyString(),anyLong());
        assertEquals("message",d.getActiveMessageId());assertEquals(1,d.getActiveAttempt());verify(dao,never()).insertOutbox(any());
    }
}
