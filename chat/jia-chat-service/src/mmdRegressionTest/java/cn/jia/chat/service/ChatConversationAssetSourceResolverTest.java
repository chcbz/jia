package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationAssetSourceResolverTest {
    private final ChatConversationArchiveStore archive=mock(ChatConversationArchiveStore.class);
    private final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    private final ChatConversationAssetSourceResolver resolver=new ChatConversationAssetSourceResolver(
            archive,steps,conversations,scopes);

    @Test void generateUsesOnlyExactTaskLinkedVersionAndServerBytes() {
        var authorized=new AgentTaskExecutionGrantService.AuthorizedInput("file",2,"REFERENCE",
                "image/png",9,"a".repeat(64));
        var refs=List.of(new ChatConversationAssetSourceResolver.Ref(
                "TASK_LINKED_WORKSPACE_VERSION","file",2,"REFERENCE",null,null));

        var resolved=resolver.resolve("0","client","owner","conversation",1,"task","agent",
                "GENERATE_IMAGE",refs,null,List.of(authorized),false);

        assertEquals(1,resolved.size());
        assertEquals(9,resolved.getFirst().byteLength());
        assertEquals("a".repeat(64),resolved.getFirst().sha256());
        assertEquals("input_1",resolved.getFirst().inputRef());
        verifyNoInteractions(archive,steps);

        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class,()->resolver.resolve("0","client","owner",
                        "conversation",1,"task","agent","GENERATE_IMAGE",
                        List.of(new ChatConversationAssetSourceResolver.Ref(
                                "TASK_LINKED_WORKSPACE_VERSION","file",3,"REFERENCE",null,null)),
                        null,List.of(authorized),false)).reason());
    }

    @Test void editRequiresExactCurrentAssetParentAndPersistsFullProducerLineage() {
        var source=asset();
        when(archive.findAuthorizedSourceForUpdate(any(),eq("conversation"),eq("asset"),eq(3L),eq("agent")))
                .thenReturn(source);
        var resolved=resolver.resolve("0","client","owner","conversation",2,"task","agent","EDIT_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref(
                        "CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),
                new ChatConversationAssetSourceResolver.Parent("request-parent","step-parent"),
                List.of(),true);

        var value=resolved.getFirst();
        assertEquals("request-parent",value.producerRequestId());
        assertEquals("step-parent",value.producerStepId());
        assertEquals("execution-parent",value.producerExecutionId());
        assertEquals("run-parent",value.producerRunId());
        assertEquals("output-parent",value.producerOutputId());
        assertTrue(value.sourceJson().contains("\"producerRequestId\":\"request-parent\""));
        verify(archive).findAuthorizedSourceForUpdate(any(),eq("conversation"),eq("asset"),eq(3L),eq("agent"));
    }

    @Test void ordinaryWorkspaceInputCanGuideGenerationOrBeEditedWithoutChangingItsTaskRole() {
        var baseline=List.of(new AgentTaskExecutionGrantService.AuthorizedInput("file",2,"INPUT","image/png",9,"a".repeat(64)));
        var refs=List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",2,"INPUT",null,null));
        for(String operation:List.of("GENERATE_IMAGE","EDIT_IMAGE")){
            var selected=resolver.resolveAction("0","client","owner","conversation",2,"task","agent",operation,refs,baseline);
            assertEquals("INPUT",selected.getFirst().purpose());assertEquals("a".repeat(64),selected.getFirst().sha256());
            assertTrue(selected.getFirst().sourceJson().contains("\"purpose\":\"INPUT\""));
        }
        verifyNoInteractions(archive,steps);
        assertThrows(ChatDeliberationException.class,()->resolver.resolveAction("0","client","owner","conversation",2,"task","agent","EDIT_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",2,"REFERENCE",null,null)),baseline));
    }

    @Test void ordinaryGenerationUsesWorkspaceAndExactConversationAssetTogetherInSelectedOrder() {
        when(archive.findAuthorizedSourceForUpdate(any(),eq("conversation"),eq("asset"),eq(3L),eq("agent"))).thenReturn(asset());
        var selected=resolver.resolveAction("0","client","owner","conversation",2,"task","agent","GENERATE_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",2,"INPUT",null,null),
                        new ChatConversationAssetSourceResolver.Ref("CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),
                List.of(new AgentTaskExecutionGrantService.AuthorizedInput("file",2,"INPUT","image/jpeg",7,"a".repeat(64))));
        assertEquals(List.of("input_1","input_2"),selected.stream().map(ControlledImageFollowupAuthorityService.Source::inputRef).toList());
        assertEquals("INPUT",selected.getFirst().purpose());assertEquals("execution-parent",selected.getLast().producerExecutionId());
        assertNull(selected.getLast().purpose());assertEquals("b".repeat(64),selected.getLast().sha256());
    }

    @Test void unsupportedMaterialPurposeMimeAndForeignAssetNeverBecomeImageInputs() {
        for(String purpose:List.of("OUTPUT","DELIVERABLE"))assertThrows(ChatDeliberationException.class,()->resolver.resolveAction(
                "0","client","owner","conversation",2,"task","agent","GENERATE_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",2,purpose,null,null)),List.of()));
        assertThrows(ChatDeliberationException.class,()->resolver.resolveAction("0","client","owner","conversation",2,"task","agent","EDIT_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("TASK_LINKED_WORKSPACE_VERSION","file",2,"INPUT",null,null)),
                List.of(new AgentTaskExecutionGrantService.AuthorizedInput("file",2,"INPUT","audio/wav",7,"a".repeat(64)))));
        assertThrows(ChatDeliberationException.class,()->resolver.resolveAction("0","client","owner","foreign",2,"task","agent","GENERATE_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),List.of()));
    }

    @Test void ordinaryEditDerivesProducerFromAuthorizedAssetWithoutModelSuppliedParent() {
        when(archive.findAuthorizedSourceForUpdate(any(),eq("conversation"),eq("asset"),eq(3L),eq("agent"))).thenReturn(asset());
        var resolved=resolver.resolveAction("0","client","owner","conversation",2,"task","agent","EDIT_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),List.of());
        assertEquals("request-parent",resolved.getFirst().producerRequestId());
        assertEquals("step-parent",resolved.getFirst().producerStepId());
        verifyNoInteractions(steps);
        reset(archive);
        assertThrows(ChatDeliberationException.class,()->resolver.resolveAction("0","client","owner","conversation",2,"task","agent","EDIT_IMAGE",
                List.of(new ChatConversationAssetSourceResolver.Ref("CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),List.of()));
    }

    @Test void anotherParentOrAclRevocationFailsWithoutFallingBackToBrowserLineage() {
        when(archive.findAuthorizedSource(any(),eq("conversation"),eq("asset"),eq(3L),eq("agent")))
                .thenReturn(asset());
        var wrongParent=new ChatConversationAssetSourceResolver.Parent("another-request","step-parent");
        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class,()->resolver.resolve("0","client","owner",
                        "conversation",2,"task","agent","EDIT_IMAGE",List.of(new ChatConversationAssetSourceResolver.Ref(
                                "CURRENT_CONVERSATION_ASSET",null,null,null,"asset",3L)),wrongParent,
                        List.of(),false)).reason());

        var expected=new ControlledImageFollowupAuthorityService.Source("input_1",
                "CURRENT_CONVERSATION_ASSET",null,null,null,"10",2L,"asset",3L,
                "request-parent",1L,"step-parent","execution-parent","run-parent","output-parent",
                "image/png",9,"b".repeat(64),"{}");
        reset(archive);
        when(conversations.lockScopedById("owner","client","10"))
                .thenReturn(conversation());
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,
                assertThrows(ChatDeliberationException.class,()->resolver.verify(
                        new ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup.SourceAccessScope(
                                "0","client","owner","task","10",2,"agent"),
                        List.of(expected),true)).reason());
    }

    @Test void runtimeVerificationRejectsChangedConversationGenerationOrTargetBeforeAssetLookup() {
        var changed=conversation().setLifecycleGeneration(3L);
        when(conversations.findScopedById("owner","client","10")).thenReturn(changed);
        when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
        var expected=new ControlledImageFollowupAuthorityService.Source("input_1",
                "CURRENT_CONVERSATION_ASSET",null,null,null,"10",2L,"asset",3L,
                "request-parent",1L,"step-parent","execution-parent","run-parent","output-parent",
                "image/png",9,"b".repeat(64),"{}");

        var failure=assertThrows(ChatDeliberationException.class,()->resolver.verify(
                new ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup.SourceAccessScope(
                        "0","client","owner","task","10",2,"agent"),
                List.of(expected),false));

        assertEquals(ChatDeliberationException.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(archive);
    }

    private static ChatConversationEntity conversation() {
        var row=new ChatConversationEntity().setId(10L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task")
                .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(2L);
        row.setTenantId("0");row.setClientId("client");return row;
    }

    private static ChatConversationArchiveStore.Source asset() {
        return new ChatConversationArchiveStore.Source("asset",3,"conversation",2,
                "request-parent",1,"step-parent","task","execution-parent","run-parent",
                "output-parent","image/png","b".repeat(64),9);
    }
}
