package cn.jia.chat.service;

import cn.jia.agent.entity.ControlledImageFollowupAuthorityDTO;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.api.ChatBountyInteractionV3Wire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatInteractionStepStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionV3AuthorityServiceTest {
    private final ChatBountyInteractionV3PreviewService previews=mock(ChatBountyInteractionV3PreviewService.class);
    private final ControlledImageFollowupAuthorityService authorities=mock(ControlledImageFollowupAuthorityService.class);
    private final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    private final ChatConversationDao conversations=mock(ChatConversationDao.class);
    private final ChatMessageDao messages=mock(ChatMessageDao.class);
    private final ChatDeliberationDao deliberation=mock(ChatDeliberationDao.class);
    private final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
    private final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    private final ChatConversationEventBroker broker=mock(ChatConversationEventBroker.class);
    private final ChatBountyInteractionV3AuthorityService service=new ChatBountyInteractionV3AuthorityService(
            previews,authorities,bindings,conversations,messages,deliberation,steps,scopes,broker);
    private final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner",
            "client",DisplayNameSource.JIACN);

    @Test void exactIssueReplayReturnsPersistedProjectionBeforeCurrentPreviewOrChatRead() {
        var persisted=projection(true);
        when(authorities.reconcileIssue(any(),eq("task"),eq("conversation"),eq("issue-key"),anyString()))
                .thenReturn(persisted);

        var replay=service.issue("0",sender,"conversation","issue-key",issue());

        assertSame(persisted,replay);
        verifyNoInteractions(previews,bindings,conversations,messages,deliberation,steps,broker);
        verify(authorities,never()).issue(any(),any(),any());
    }

    @Test void absentIssueKeyFallsThroughToFreshServerPreviewButNeverTrustsClientDigestsAsAuthority() {
        when(authorities.reconcileIssue(any(),eq("task"),eq("conversation"),eq("issue-key"),anyString()))
                .thenThrow(new ControlledImageFollowupAuthorityService.Failure(
                        ControlledImageFollowupAuthorityService.Reason.NOT_FOUND_OR_FORBIDDEN));
        var prepared=prepared();
        when(previews.prepare(eq("0"),same(sender),eq("conversation"),eq("interaction-key"),any(),eq(false)))
                .thenReturn(prepared);
        when(authorities.issue(any(),any(),any())).thenAnswer(invocation -> {
            var command=(ControlledImageFollowupAuthorityService.IssueCommand)invocation.getArgument(1);
            assertEquals(prepared.command(),command.preview());
            assertEquals("model",command.expectedPreview().modelId());
            assertEquals("binding",command.requestedProvider().bindingId());
            return projection(false);
        });

        var created=service.issue("0",sender,"conversation","issue-key",issue());

        assertFalse(created.replay());
        verify(previews).prepare(eq("0"),same(sender),eq("conversation"),eq("interaction-key"),any(),eq(false));
        verify(authorities).issue(any(),any(),any());
    }

    @Test void allOriginalKeyReadsAreDeclaredReadOnly() throws Exception {
        for (var signature:List.of(
                new Object[]{"preview",new Class<?>[]{String.class,ServerResolvedSender.class,String.class,
                        String.class,ChatBountyInteractionV3Wire.IntentValue.class}},
                new Object[]{"getIssue",new Class<?>[]{String.class,ServerResolvedSender.class,String.class,String.class}},
                new Object[]{"getInteraction",new Class<?>[]{String.class,ServerResolvedSender.class,String.class,String.class}})) {
            String name=(String)signature[0];Class<?>[] args=(Class<?>[])signature[1];
            Transactional annotation=ChatBountyInteractionV3AuthorityService.class.getMethod(name,args)
                    .getAnnotation(Transactional.class);
            assertNotNull(annotation,name);assertTrue(annotation.readOnly(),name);
        }
    }

    private ChatBountyInteractionV3Wire.Issue issue() {
        var intent=ChatBountyInteractionV3Wire.parseIntent("{"+
                "\"schemaVersion\":3,\"interactionKind\":\"EXECUTE\",\"taskId\":\"task\","+
                "\"expectedConversationGeneration\":\"1\",\"expectedTaskVersion\":\"0\","+
                "\"expectedAssignmentRevision\":\"0\",\"expectedGrantVersion\":\"1\","+
                "\"requirementRevision\":\"1\",\"targetAgentId\":\"agent\",\"content\":\"draw\","+
                "\"actionProposal\":{\"kind\":\"generate_image\"},\"inputRefs\":[],"+
                "\"replyTo\":null,\"continuationOf\":null}",false);
        var expected=new ChatBountyInteractionV3Wire.ExpectedPreview("1".repeat(64),"2".repeat(64),
                "3".repeat(64),"model","OPERATOR_TEMPLATE","policy-r1");
        return new ChatBountyInteractionV3Wire.Issue("interaction-key",intent,"binding",7,
                expected,"UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT",
                java.util.Map.of("schemaVersion",2,"intent",intent.canonicalOwner()));
    }

    private ChatBountyInteractionV3PreviewService.Prepared prepared() {
        var command=new ControlledImageFollowupAuthorityService.PreviewCommand("task","conversation",1,
                "interaction-key","request","step","intent",
                new ControlledImageFollowupAuthorityService.Baseline("baseline",1,0,0,1,
                        "4".repeat(64),"agent"),"GENERATE_IMAGE","draw","2".repeat(64),
                "1".repeat(64),"3".repeat(64),List.of());
        var preview=new ControlledImageFollowupAuthorityService.Preview("runtime",
                new ControlledImageFollowupAuthorityService.ProviderExpectation("binding",7,"model",
                        "OPERATOR_TEMPLATE","policy-r1"),"UNPRICED_EXTERNAL_ACCOUNT",1,999);
        return new ChatBountyInteractionV3PreviewService.Prepared(command,preview,"1".repeat(64),
                "2".repeat(64),"3".repeat(64));
    }

    private static ControlledImageFollowupAuthorityDTO projection(boolean replay) {
        return new ControlledImageFollowupAuthorityDTO(2,
                "consent_1234567890abcdef1234567890abcdef","ISSUED","1",
                "opgrant_1234567890abcdef1234567890abcdef","AUTHORIZED","1",
                "task","conversation","1","request","step","intent","agent",
                "GENERATE_IMAGE","1".repeat(64),"2".repeat(64),"3".repeat(64),List.of(),
                new ControlledImageFollowupAuthorityDTO.ProviderBinding("binding","7"),"model",
                "OPERATOR_TEMPLATE","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,"999",replay);
    }
}
