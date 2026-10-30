package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.api.AgentMaterialsInputController;
import org.springframework.mock.web.MockHttpServletRequest;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real material authorization path; DB transaction semantics are tested separately. */
class ChatMaterialsFenceTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final TypedDeliberationSessionRegistry sessions=mock(TypedDeliberationSessionRegistry.class);
    final ChatSkillSourceAccessService sources=mock(ChatSkillSourceAccessService.class);
    final AgentRuntimeAuthenticationService authentication=mock(AgentRuntimeAuthenticationService.class);
    final ChatDispatchAuthorityService dispatch=mock(ChatDispatchAuthorityService.class);
    final ChatMaterialsInputService service=new ChatMaterialsInputService(dao,store,tasks,sessions,sources,
            mock(AgentTaskExecutionGrantService.class));
    final AgentMaterialsInputController controller=new AgentMaterialsInputController(service,authentication);
    final AgentRuntimeAuthentication runtime=mock(AgentRuntimeAuthentication.class);
    final AgentRuntimeAuthenticationService.Proof proof=new AgentRuntimeAuthenticationService.Proof(
            new AgentRuntimeAuthentication.Scope("0","client","owner","agent","runtime"),
            "rti_"+"a".repeat(32),"host",1L,"a".repeat(64),1L,1L);
    final ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setClientId("client").setOwnerJiacn("owner")
            .setConversationId("123").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
            .setTurnId("turn").setTargetAgentId("agent").setRoute("CHAT").setDispatchId("dispatch")
            .setSnapshotId("snapshot").setContextDigest("context").setState("STREAMING");
    final PersonalWorkspaceExecutionService.RuntimeContent content=
            new PersonalWorkspaceExecutionService.RuntimeContent("image.png","image/png",new byte[]{1,2,3});
    final List<String> events=new ArrayList<>();
    String digest;
    boolean fenced;
    int fences;
    boolean revoked;

    @BeforeEach void setup() {
        service.setDispatchAuthority(dispatch);
        when(runtime.isAuthenticated()).thenReturn(true);
        when(runtime.getPrincipal()).thenReturn(proof.scope());
        var source=Map.<String,Object>of("sourceRefId","source","sourceKind","TASK_WORKSPACE_FILE",
                "sourceIdentity",Map.of("workspaceFileId","file","version",1),
                "contentMimeType","image/png","contentHash","a".repeat(64),"byteLength","3");
        var declaration=Map.<String,Object>of("materialsInput",Map.of("schemaVersion",1,"supportedInputs",List.of(
                Map.of("mediaKind","image","mimeType","image/png","carrier","LOCAL_IMAGE",
                        "carrierContractDigest","sha256:"+"b".repeat(64)))));
        var material=ChatMaterialsInputContract.bind(ChatMaterialsInputContract.candidates(declaration,List.of(source)),
                "0","owner","client","123",1,"task","request","turn","agent");
        digest=(String)material.get("manifestDigest");
        var scope=new ChatTypedDeliberationStore.Scope("0","owner","client","123",1);
        var admission=new ChatTypedDeliberationStore.Admission("admission",scope,"key","digest","body","CHAT","task",1,
                null,null,"request",1,100,"[\"turn\"]",CanonicalContextJson.write(List.of(source)),"ADMITTED",0,1,1);
        when(store.findAdmissionByRequest(scope,"request")).thenReturn(admission);
        when(dao.findRequest("0","owner","client","request")).thenReturn(new ChatRequestEntity()
                .setRequestRevision(1L).setConversationId("123").setConversationGeneration(1L));
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(new ChatContextSnapshotEntity()
                .setRequestId("request").setTargetAgentId("agent").setConversationId("123").setConversationGeneration(1L)
                .setContextDigest("context").setFactsManifestJson(CanonicalContextJson.write(Map.of("materialsInput",material))));
        var binding=new ChatDispatchAuthorityService.Binding(null,turn,"owner");
        when(dispatch.incomingTurn("0","client","owner","agent","turn")).thenAnswer(i->{
            assertTrue(fenced,"incomingTurn must not acquire task before runtime identity");events.add("incoming");return binding;
        });
        when(dispatch.forTurn(turn)).thenAnswer(i->{assertTrue(fenced);return binding;});
        when(sessions.requireSingleReady(any(),eq("agent"))).thenReturn(
                new TypedDeliberationSessionRegistry.Ready("session","agent",List.of("CHAT"),declaration));
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any()))
                .thenAnswer(i->{
                    assertTrue(fenced,"task root must be acquired after runtime identity");events.add("task");
                    AgentTaskMutationTransaction.LockedTaskMutation<?> callback=i.getArgument(4);
                    return callback.apply(new AgentTaskMetaEntity().setAssignedAgentId("agent"));
                });
        when(authentication.withNativeFence(eq(runtime),any())).thenAnswer(i->{
            assertFalse(fenced);fences++;if(revoked)throw new SecurityException("revoked");
            fenced=true;events.add("fence");
            try{return ((Supplier<?>)i.getArgument(1)).get();}finally{fenced=false;events.add("release");}
        });
        when(sources.read(any(),eq("task"),eq("123"),eq(1L),any())).thenAnswer(i->{
            assertFalse(fenced,"storage I/O must not hold runtime/task transaction");events.add("storage");return content;
        });
    }

    @Test void bothAuthorizationPassesUseIdentityBeforeTaskAndStorageOutsideFence() {
        assertArrayEquals(content.bytes(),read());
        assertEquals(List.of("fence","incoming","task","release","storage","fence","task","release"),events);
        assertEquals(2,fences);
    }
    @Test void revokedRuntimeDuringStorageDoesNotDeliverBytes() {
        when(sources.read(any(),anyString(),anyString(),anyLong(),any())).thenAnswer(i->{
            assertFalse(fenced);revoked=true;return content;
        });
        assertThrows(SecurityException.class,this::read);
        assertEquals(2,fences);
    }
    @Test void changedTurnDuringStorageDoesNotDeliverBytes() {
        when(sources.read(any(),anyString(),anyString(),anyLong(),any())).thenAnswer(i->{
            assertFalse(fenced);turn.setState("CANCELLED");return content;
        });
        assertThrows(ChatDeliberationException.class,this::read);
        assertEquals(2,fences);
    }
    @Test void materialPermissionRevokedDuringStorageDoesNotDeliverBytes() {
        when(sources.read(any(),anyString(),anyString(),anyLong(),any())).thenAnswer(i->{
            assertFalse(fenced);
            doThrow(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    "Material input is unavailable")).when(sources).requireAuthorized(any(),anyString(),anyString(),anyLong(),any());
            return content;
        });
        assertThrows(ChatDeliberationException.class,this::read);
        assertEquals(2,fences);
    }
    @Test void storageFailureDoesNotReturnBytesOrRetryStorage() {
        var failure=new IllegalStateException("storage unavailable");
        when(sources.read(any(),anyString(),anyString(),anyLong(),any())).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,this::read));
        assertEquals(1,fences);
        verify(sources,times(1)).read(any(),anyString(),anyString(),anyLong(),any());
    }
    @Test void initialRevocationNeverReadsTaskOrStorage() {
        revoked=true;
        assertThrows(SecurityException.class,this::read);
        verifyNoInteractions(dispatch,tasks,sources);
    }
    byte[] read() {
        var request=new MockHttpServletRequest();
        request.addHeader("X-Materials-Manifest-Digest",digest);
        return controller.content("request","turn","source",runtime,request).getBody();
    }
}
