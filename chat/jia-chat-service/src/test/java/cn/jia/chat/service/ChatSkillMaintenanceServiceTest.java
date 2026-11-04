package cn.jia.chat.service;

import cn.jia.agent.api.SkillConversationToolAdapter;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.position.PositionSkillService;
import cn.jia.agent.position.PositionSkillState;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.agent.skill.SkillMarketplaceException;
import cn.jia.agent.skill.SkillPackagePublicationService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Transport authority tests; not a substitute for native authentication/transaction or model E2E. */
class ChatSkillMaintenanceServiceTest {
    private final AgentRuntimeAuthentication principal=mock(AgentRuntimeAuthentication.class);
    private final AgentRuntimeAuthenticationService authentication=mock(AgentRuntimeAuthenticationService.class);
    private final ChatDispatchAuthorityService dispatches=mock(ChatDispatchAuthorityService.class);
    private final ChatDeliberationDao turns=mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final AgentIdentityService identities=mock(AgentIdentityService.class);
    private final PositionSkillService positions=mock(PositionSkillService.class);
    private final SkillPackagePublicationService publications=mock(SkillPackagePublicationService.class);
    private final PositionAuthority.Scope scope=new PositionAuthority.Scope("0","client","owner");
    private final SkillPackagePublicationService.UpdateMetadata command=
            new SkillPackagePublicationService.UpdateMetadata("product","技能标题","精简描述",4L);
    private final SkillConversationToolAdapter service=new SkillConversationToolAdapter(authentication,new PrivateConversationToolAccessService(dispatches,turns,conversations),identities,positions,publications);
    private ChatTurnEntity turn;
    private ChatConversationEntity conversation;

    @BeforeEach void setup() {
        var runtime=new AgentRuntimeAuthentication.Scope("0","client","owner","agent","instance");
        when(principal.isAuthenticated()).thenReturn(true);
        when(principal.getPrincipal()).thenReturn(runtime);
        when(principal.getDetails()).thenReturn(new AgentRuntimeAuthenticationService.Proof(
                runtime,"installation","host",1,"test-digest",1,1));
        when(authentication.withNativeFence(eq(principal),any())).thenAnswer(call->((Supplier<?>)call.getArgument(1)).get());
        turn=new ChatTurnEntity().setTurnId("turn").setRequestId("request").setRequestRevision(1L)
                .setConversationId("conversation").setConversationGeneration(1L).setTargetAgentId("agent")
                .setRoute("CHAT").setState("DISPATCHED");
        when(turns.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(turns.lockTurn("0","owner","client","turn")).thenReturn(turn);
        when(turns.findRequest("0","owner","client","request")).thenReturn(new ChatRequestEntity().setRequestRevision(1L));
        conversation=new ChatConversationEntity().setConversationScopeType("private").setLifecycleGeneration(1L);
        when(conversations.lockScopedById("0","owner","client","conversation")).thenReturn(conversation);
        when(identities.requireRegistrationIdentityInScope("0","client","owner","agent"))
                .thenReturn(new AgentIdentityRegistryEntity().setBindingId(1L).setCanonicalAgentId("agent"));
        var position=new PositionSkillState.Position("custom-position","任意岗位名称",1,"skills:platform",true,List.of(),3);
        var appointment=new PositionSkillState.Appointment("appointment",position.id(),"agent",1,true,2);
        var state=new PositionSkillState(1,Map.of(),Map.of(position.id(),position),Map.of(appointment.id(),appointment),Map.of());
        when(positions.read(scope)).thenReturn(new PositionSkillService.View(null,null,3,state));
    }

    @Test void metadataUsesAuthenticatedOwnerAndCurrentResourceGrantNotAPositionName() {
        var grant=new PositionAuthority.Grant("appointment",2,3,"agent",1,null,true);
        var receipt=new SkillPackagePublicationService.MetadataReceipt("product","技能标题","精简描述",5,"UPDATED");
        when(publications.updateMetadata(scope,grant,command)).thenReturn(receipt);
        assertSame(receipt,service.updateMetadata(principal,"request","turn",command));
        verify(authentication).withNativeFence(eq(principal),any());
        verify(dispatches).incomingTurn("0","client","owner","agent","turn");
        verify(publications).updateMetadata(scope,grant,command);
    }

    @Test void missingOwnerScopedTurnCannotUseExternalExecutorAuthority() {
        when(turns.findTurn("0","owner","client","turn")).thenReturn(null);
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        verifyNoInteractions(publications,positions,dispatches);
    }

    @Test void taskDiscussionDoesNotAuthorizePlatformMaintenance() {
        conversation.setConversationScopeType("bounty");
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        verifyNoInteractions(publications,positions,dispatches);
    }

    @Test void cancelledTurnAndDeletedOrRecreatedConversationCannotWrite() {
        for(String state:List.of("CANCELLED","PUBLISHED","RECOVERY_REQUIRED")) {
            turn.setState(state);
            assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        }
        turn.setState("DISPATCHED");conversation.setLifecycleGeneration(2L);
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        conversation.setLifecycleGeneration(1L).setDeletedAt(1L);
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        verifyNoInteractions(publications,positions);
    }

    @Test void wrongRequestOrTargetDoesNotBorrowAnotherTurn() {
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"different","turn",command));
        turn.setTargetAgentId("other-agent");
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.updateMetadata(principal,"request","turn",command));
        verifyNoInteractions(publications,positions);
    }

    @Test void missingAppointmentIsNotReplacedWithPlatformAdministratorAuthority() {
        when(positions.read(scope)).thenReturn(new PositionSkillService.View(null,null,4,PositionSkillState.empty()));
        assertThrows(SkillMarketplaceException.class,()->service.updateMetadata(principal,"request","turn",command));
        verifyNoInteractions(publications);
    }

    @Test void businessAuthorityRevocationAndCasErrorsAreNotConvertedToSuccess() {
        var failure=new SkillMarketplaceException(409,"SKILL_PRODUCT_CHANGED");
        when(publications.updateMetadata(eq(scope),any(),eq(command))).thenThrow(failure);
        assertSame(failure,assertThrows(SkillMarketplaceException.class,
                ()->service.updateMetadata(principal,"request","turn",command)));
    }
    @Test void catalogRequiresCurrentVerifiedInstallationAsWellAsAppointment() {
        var installed=mock(cn.jia.agent.service.InstalledSkillDescriptorLookup.class);
        service.setInstalled(installed);
        when(positions.withSkillManagementAuthority(eq(scope),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(2)).get());
        when(installed.readCurrent(any())).thenReturn(new cn.jia.agent.service.InstalledSkillDescriptorLookup.Snapshot(List.of()));
        assertTrue(service.catalog(principal,"request","turn").tools().isEmpty());
        var descriptor=new cn.jia.agent.service.InstalledSkillDescriptorLookup.Descriptor("skill-management","1.0.0","si","a".repeat(64),null,null,"runtime","host",null);
        when(installed.readCurrent(any())).thenReturn(new cn.jia.agent.service.InstalledSkillDescriptorLookup.Snapshot(List.of(descriptor)));
        var catalog=service.catalog(principal,"request","turn");
        assertEquals(6,catalog.tools().size());assertEquals("si",catalog.skills().getFirst().skillInstallationId());
        when(positions.read(scope)).thenReturn(new PositionSkillService.View(null,null,4,PositionSkillState.empty()));
        assertTrue(service.catalog(principal,"request","turn").tools().isEmpty());
    }

    @Test void packageReadCannotReturnContentAfterConversationCancellation() {
        when(positions.withSkillManagementAuthority(eq(scope),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(2)).get());
        when(publications.readPackage(eq(scope),any(),eq("v1"),eq("SKILL.md"))).thenAnswer(c->{
            turn.setState("CANCELLED");return Map.of("data","private bytes");
        });
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.readPackage(principal,"request","turn","v1","SKILL.md"));
    }

    @Test void cancelledDuringPackagePreparationCannotCommit() {
        when(positions.withSkillManagementAuthority(eq(scope),any(),any())).thenAnswer(c->((Supplier<?>)c.getArgument(2)).get());
        var prepared=mock(SkillPackagePublicationService.Prepared.class);
        when(publications.prepare(eq(scope),any(),any(),any())).thenAnswer(c->{turn.setState("CANCELLED");return prepared;});
        var command=new SkillPackagePublicationService.Publish("p","v","名称","描述","example","1.0.0",0,"NONE","[]",null,null,0,null);
        var files=List.of(new cn.jia.agent.skill.SkillPublicationToolArchive.File("SKILL.md","real file","utf8"));
        assertThrows(PrivateConversationToolAccess.Denied.class,()->service.publish(principal,"request","turn",command,files));
        verify(publications,never()).commitPrepared(any());
    }

}
