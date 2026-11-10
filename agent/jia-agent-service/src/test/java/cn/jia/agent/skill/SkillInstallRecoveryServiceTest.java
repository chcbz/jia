package cn.jia.agent.skill;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.*;
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
    private final ScheduleService schedules=mock(ScheduleService.class);
    private org.springframework.beans.factory.ObjectProvider<ScheduleService> scheduleProvider() {
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("schedules",schedules);return beans.getBeanProvider(ScheduleService.class);
    }
    private final long now=System.currentTimeMillis();
    private final AgentSkillInstallRecoveryMapper mapper=mock(AgentSkillInstallRecoveryMapper.class);
    private final AgentCommandRecoveryDao dao=mock(AgentCommandRecoveryDao.class);
    private final SkillInstallDispatchService dispatch=mock(SkillInstallDispatchService.class);
    private final EconomyPreviewGate preview=new EconomyPreviewGate(new EconomyPreviewProperties(true,List.of(new EconomyPreviewProperties.AllowedScope("0","c"))));
    private final SkillInstallRecoveryService service=new SkillInstallRecoveryService(mapper,dao,dispatch,mock(PlatformTransactionManager.class),new FixedTenantAdmissionPolicy("0", null),scheduleProvider(),"node-1");
    private AgentCommandDeliveryEntity d;
    private AgentOutboxEventEntity o;
    void fixture() { fixture("0"); }
    void fixture(String tenant) {
        when(dispatch.canDispatch(anyString(),anyString(),anyString())).thenReturn(true);
        // Schema-2 PREPARE persisted by the real SM-C1 producer, not the retired task wire.
        var data=SkillManagementCodec.map("schemaVersion",2,"commandId","cmd_prepare","skillChangeId","o","action","INSTALL",
                "tenantId",tenant,"clientId","c","ownerJiacn","owner-a","agentId","a","runtimeInstallationId","rti_fixture",
                "skillKey","repo-test","attempt",1,"fencingToken","1","deliveryEpoch","1","skillRevision","1","source",null,
                "target",SkillManagementCodec.map("skillInstallationId","i","productVersionId","pv","skillVersion","1.0.0",
                        "packageSha256","a".repeat(64),"manifestSha256",null,"byteLength","100","kind","INSTRUCTION"),
                "approvedPermissionsSha256",SkillManagementCodec.hash("cyf.skill.permissions.v1",List.of()),"desiredEnabled",true,
                "issuedAt",now,"phase","PREPARE","commandType","SKILL_INSTALL","downloadPath","/internal/agent/skill-installations/i/package");
        data.put("commandSha256",SkillManagementCodec.hash("cyf.skill.command.v1",data));
        var business=SkillManagementCodec.bytes(data);
        var wire=SkillManagementCodec.bytes(SkillManagementCodec.map("protocolVersion",1,"requestId","request","messageType","command.dispatch","messageId","message","data",data));
        d=new AgentCommandDeliveryEntity().setOwnerJiacn("owner-a").setCommandId("cmd_prepare").setTaskId("o").setTargetAgentId("a").setCommandType("SKILL_INSTALL")
                .setCommandPayload(business).setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business)).setStatus("PENDING")
                .setActiveMessageId("message").setActiveAttempt(1).setExpiresAt(Long.MAX_VALUE).setVersion(7L);
        d.setId(1L);d.setTenantId(tenant);d.setClientId("c");d.setUpdateTime(now-70000);
        o=new AgentOutboxEventEntity().setEventId("event").setMessageId("message").setCommandId("cmd_prepare").setDeliveryId(1L)
                .setAggregateType("skill-change").setAggregateId("o").setDestination(SkillInstallCommandWriter.DESTINATION).setRoutingKey("a")
                .setActiveAttempt(1).setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("PENDING")
                .setPublisherConfirmStatus("NONE").setMandatoryReturnStatus("NONE").setExpiresAt(Long.MAX_VALUE).setVersion(1L);
        o.setId(2L);o.setTenantId(tenant);o.setClientId("c");
        when(dao.lockDelivery(tenant,"c",1L)).thenReturn(d);when(dao.lockActiveOutboxes(tenant,"c",1L,"message")).thenReturn(List.of(o));
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
    @Test void scheduledCallbackRequiresConfiguredProcessScope() {
        assertThrows(RuntimeException.class,()->service.executeScheduled(new ScheduledInvocation("foreign",
                new ScheduleKey("c",SkillInstallRecoveryService.HANDLER,"1","node-1"),1)));
        verifyNoInteractions(mapper,dao,dispatch,schedules);
    }
    @Test void configuredPartitionIsPassedToDiscoveryBeforeLimit() {
        var scoped=new SkillInstallRecoveryService(mapper,dao,dispatch,mock(PlatformTransactionManager.class),
                new FixedTenantAdmissionPolicy("tenant-blue", "c"),scheduleProvider(),"node-1");
        when(mapper.pendingForAgent("tenant-blue","c","a",0)).thenReturn(List.of());
        scoped.sessionReady("tenant-blue","c","a");
        verify(mapper).pendingForAgent("tenant-blue","c","a",0);
        verifyNoMoreInteractions(mapper);verifyNoInteractions(dao,dispatch,schedules);
    }
    @Test void reconnectIncludesFutureClaimsAndPagesWithoutRedispatching() {
        fixture();var page=new ArrayList<AgentCommandDeliveryEntity>();
        for(long id=1;id<=100;id++) {
            var row=new AgentCommandDeliveryEntity().setStatus("PENDING").setLeaseUntil(now+60000);
            row.setId(id);row.setClientId("c");page.add(row);
        }
        when(mapper.pendingForAgent("0","c","a",0)).thenReturn(page);
        when(mapper.pendingForAgent("0","c","a",100)).thenReturn(List.of());
        service.sessionReady("0","c","a");
        verify(schedules,times(100)).signal(any(),eq(now+60000));
        verify(mapper).pendingForAgent("0","c","a",100);verifyNoInteractions(dao,dispatch);
    }
    @Test void terminalCallbackDoesNotScanOrReschedule() {
        service.executeScheduled(invocation());
        verify(mapper).findPending("0","c",1L);verifyNoMoreInteractions(mapper);
        verifyNoInteractions(dao,dispatch,schedules);
    }
    @Test void futureRetryReschedulesItsDeadlineWithoutClaiming() {
        fixture();d.setNextRetryAt(now+60000);
        when(mapper.findPending("0","c",1L)).thenReturn(d);
        service.executeScheduled(invocation());
        verify(schedules).reschedule(SkillInstallRecoveryService.key(d,"node-1"),now+60000);
        verifyNoInteractions(dao);verify(dispatch,never()).dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void liveOutboxLeaseDoesNotBecomeAnImmediateRetryLoop() {
        fixture();o.setLeaseUntil(now+60000);
        when(mapper.findPending("0","c",1L)).thenReturn(d);
        service.executeScheduled(invocation());
        verify(schedules).reschedule(SkillInstallRecoveryService.key(d,"node-1"),now+60000);
        verify(dispatch,never()).dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void callbackWithoutLocalSocketRetiresRatherThanPollingOtherNodes() {
        fixture();when(mapper.findPending("0","c",1L)).thenReturn(d);
        when(dispatch.canDispatch("0","c","a")).thenReturn(false);
        service.executeScheduled(invocation());
        verifyNoInteractions(dao,schedules);
        verify(dispatch,never()).dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void callbackCannotRunOnAnotherTransportNode() {
        assertThrows(IllegalArgumentException.class,()->service.executeScheduled(new ScheduledInvocation("0",
                new ScheduleKey("c",SkillInstallRecoveryService.HANDLER,"1","node-2"),1)));
        verifyNoInteractions(mapper,dao,schedules,dispatch);
    }
    private ScheduledInvocation invocation() {
        return new ScheduledInvocation("0",new ScheduleKey("c",SkillInstallRecoveryService.HANDLER,"1","node-1"),1);
    }
    @Test void nonNumericTenantRecoversOriginalWireWithoutTenantZeroGate() {
        fixture("tenant-blue");
        var gate=new EconomyPreviewGate(new EconomyPreviewProperties(true,
                List.of(new EconomyPreviewProperties.AllowedScope("tenant-blue","c"))));
        var scoped=new SkillInstallRecoveryService(mapper,dao,dispatch,mock(PlatformTransactionManager.class),
                new FixedTenantAdmissionPolicy("tenant-blue", "c"),scheduleProvider(),"node-1");
        when(mapper.claimDelivery(eq(d),anyString(),anyLong(),anyLong())).thenReturn(1);
        when(mapper.claimOutbox(eq(o),anyString(),anyLong(),anyLong())).thenReturn(1);
        when(dispatch.dispatch(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{
            assertArrayEquals(o.getWirePayload(),call.getArgument(6));
            d.setLeaseOwner("superseded");return AgentRawCommandDispatchResult.sent(1,1);
        });
        scoped.recover(d,now);
        verify(dispatch).dispatch(eq("tenant-blue"),eq("c"),eq("owner-a"),eq("o"),eq("a"),eq(d.getCommandId()),any());
        verify(mapper,never()).finishDelivery(any(),anyString(),anyString(),any(),anyString(),anyLong());
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
