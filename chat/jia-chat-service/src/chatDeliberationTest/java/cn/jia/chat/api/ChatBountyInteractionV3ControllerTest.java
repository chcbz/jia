package cn.jia.chat.api;

import cn.jia.chat.service.ChatBountyInteractionV3AuthorityService;
import cn.jia.chat.service.ChatBountyInteractionV3PreviewService;
import cn.jia.chat.service.ChatBountyInteractionAdmissionService;
import cn.jia.chat.service.ChatBountyDiscussionAdmissionService;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.ObjectMapper;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyInteractionV3ControllerTest {
    @Test void strictGenerateAndEditWireKeepsCanonicalStringsAndNestedAssetRef() {
        var generate=ChatBountyInteractionV3Wire.parseIntent(generateBody(false),false);
        assertEquals("GENERATE_IMAGE",generate.operation());
        assertEquals(0,generate.taskVersion());
        assertEquals(0,generate.assignmentRevision());
        assertEquals(1,generate.grantVersion());
        assertEquals("TASK_LINKED_WORKSPACE_VERSION",generate.inputRefs().getFirst().kind());
        assertEquals("1",((java.util.Map<?,?>)((java.util.List<?>)generate.canonicalOwner()
                .get("inputRefs")).getFirst()).get("version"));

        var edit=ChatBountyInteractionV3Wire.parseIntent(editBody(false),false);
        assertEquals("EDIT_IMAGE",edit.operation());
        assertEquals("asset-1",edit.inputRefs().getFirst().assetRef().assetId());
        assertEquals(3,edit.inputRefs().getFirst().assetRef().revision());
        assertEquals("request-parent",edit.continuationOf().requestId());
        @SuppressWarnings("unchecked")
        var canonicalRef=(java.util.Map<String,Object>)((java.util.List<?>)edit.canonicalOwner()
                .get("inputRefs")).getFirst();
        assertEquals(Set.of("kind","assetRef"),canonicalRef.keySet());
    }

    @Test void admitRequiresExactServerAuthorityIdentifiersAndSafeDecimalVersions() {
        var admit=ChatBountyInteractionV3Wire.parseIntent(generateBody(true),true);
        assertEquals("consent_1234567890abcdef1234567890abcdef",admit.authority().consentId());
        assertEquals(1,admit.authority().expectedConsentVersion());
        var canonicalAdmit=ChatBountyInteractionV3Wire.canonicalAdmit(admit);
        assertTrue(canonicalAdmit.containsKey("authority"));
        assertNull(canonicalAdmit.get("replyTo"));
        assertNull(canonicalAdmit.get("continuationOf"));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                generateBody(true).replace("\"1\",\"requirementRevision\"",
                        "\"9007199254740992\",\"requirementRevision\""),true));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                generateBody(false).replace("\"expectedTaskVersion\":\"0\"",
                        "\"expectedTaskVersion\":\"00\""),false));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                generateBody(false).replace("\"replyTo\":null","\"replyTo\":null,\"replyTo\":null"),false));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                generateBody(false)+" {}",false));
    }

    @Test void allWireReadEntrypointsMapMalformedDuplicateAndTrailingJsonToInvalidRequest() {
        assertInvalidJsonAcrossWireEntrypoints("{");
        assertInvalidJsonAcrossWireEntrypoints("duplicate");
        assertInvalidJsonAcrossWireEntrypoints("trailing");
    }

    @Test void allWireReadEntrypointsAcceptValidStrictJson() {
        assertEquals(3,ChatBountyInteractionV3Wire.schemaVersion(generateBody(false)));
        assertEquals("GENERATE_IMAGE",ChatBountyInteractionV3Wire.parseIntent(generateBody(false),false).operation());
        assertEquals("interaction-key",ChatBountyInteractionV3Wire.parseIssue(issueBody()).interactionIdempotencyKey());
        assertEquals("opgrant_1234567890abcdef1234567890abcdef",
                ChatBountyInteractionV3Wire.parseRevoke(revokeBody()).operationGrantId());
    }

    @Test void strictSchemaDispatchRejectsDuplicateOrTrailingMarkersBeforeLegacyFallback() {
        var admissions=mock(ChatBountyInteractionAdmissionService.class);
        var discussion=mock(ChatBountyDiscussionAdmissionService.class);
        var identities=mock(HumanSenderIdentityResolver.class);
        var tenants=mock(TenantScopeResolver.class);
        var v3=mock(ChatBountyInteractionV3AuthorityService.class);
        var controller=new ChatBountyInteractionController(admissions,discussion,identities,tenants,
                v3,mock(ObjectMapper.class));
        var request=mock(HttpServletRequest.class);
        String duplicate=generateBody(true).replace("\"schemaVersion\":3",
                "\"schemaVersion\":3,\"schemaVersion\":2");

        for(String malformed:java.util.List.of(duplicate,generateBody(true)+" {}")) {
            var failure=assertThrows(ChatDeliberationException.class,()->controller.interact(
                    "conversation","interaction-key",malformed,null,request));
            assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,failure.reason());
        }
        verifyNoInteractions(admissions,discussion,identities,tenants,v3,request);
    }

    @Test void editRequiresExactParentAndOneCurrentConversationAsset() {
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                editBody(false).replace(",\"continuationOf\":{\"requestId\":\"request-parent\",\"stepId\":\"step-parent\"}",
                        ",\"continuationOf\":null"),false));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                editBody(false).replace("\"assetRef\":{\"assetId\":\"asset-1\",\"revision\":\"3\"}",
                        "\"assetId\":\"asset-1\",\"revision\":\"3\""),false));
        assertThrows(IllegalArgumentException.class,()->ChatBountyInteractionV3Wire.parseIntent(
                editBody(false).replace("\"inputRefs\":[{", "\"inputRefs\":[] ,\"ignored\":[{"),false));
    }

    @Test void contextProjectionHasExactNineFieldsAllVersionValuesAsStrings() {
        assertEquals(Set.of("schemaVersion","conversationId","conversationGeneration","taskId",
                        "targetAgentId","taskVersion","assignmentRevision","baselineGrantVersion",
                        "requirementRevision"),
                Arrays.stream(ChatBountyInteractionV3Wire.ContextData.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet()));
        var context=new ChatBountyInteractionV3Wire.ContextData(1,"conversation","1","task",
                "agent","0","0","1","1");
        assertEquals("0",context.taskVersion());
        assertEquals("0",context.assignmentRevision());
        assertEquals("1",context.conversationGeneration());
    }

    @Test void contextGetIsUniquePrivateNoStoreAndRequiresNeitherQueryBodyNorIdempotencyKey()
            throws Exception {
        var authority=mock(ChatBountyInteractionV3AuthorityService.class);
        var previews=mock(ChatBountyInteractionV3PreviewService.class);
        var identities=mock(HumanSenderIdentityResolver.class);
        var tenants=mock(TenantScopeResolver.class);
        var controller=new ChatBountyInteractionV3Controller(authority,previews,identities,tenants);
        var authentication=mock(Authentication.class);
        var request=mock(HttpServletRequest.class);
        var sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
        var expected=new ChatBountyInteractionV3Wire.ContextData(1,"conversation","1","task",
                "agent","0","0","1","1");
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(sender);
        when(previews.context("0",sender,"conversation")).thenReturn(expected);

        var response=controller.context("conversation",authentication,request);

        assertEquals("private, no-store",response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertNotNull(response.getBody());assertSame(expected,response.getBody().getData());
        var method=ChatBountyInteractionV3Controller.class.getMethod("context",String.class,
                Authentication.class,HttpServletRequest.class);
        assertArrayEquals(new String[]{"/{conversationId}/interactions/context"},
                method.getAnnotation(GetMapping.class).value());
        assertEquals(1,Arrays.stream(ChatBountyInteractionV3Controller.class.getMethods())
                .filter(candidate->candidate.getAnnotation(GetMapping.class)!=null)
                .filter(candidate->Arrays.asList(candidate.getAnnotation(GetMapping.class).value())
                        .contains("/{conversationId}/interactions/context")).count());
        assertFalse(Arrays.stream(method.getParameterAnnotations()).flatMap(Arrays::stream)
                .map(Annotation::annotationType).anyMatch(RequestHeader.class::equals));
    }

    @Test void contextGetRejectsQueryOrBodyBeforeResolvingIdentityOrStorage() {
        var authority=mock(ChatBountyInteractionV3AuthorityService.class);
        var previews=mock(ChatBountyInteractionV3PreviewService.class);
        var identities=mock(HumanSenderIdentityResolver.class);
        var tenants=mock(TenantScopeResolver.class);
        var controller=new ChatBountyInteractionV3Controller(authority,previews,identities,tenants);
        var query=mock(HttpServletRequest.class);when(query.getQueryString()).thenReturn("unexpected=1");
        var body=mock(HttpServletRequest.class);when(body.getContentLengthLong()).thenReturn(1L);

        var queryFailure=assertThrows(ChatDeliberationException.class,
                () -> controller.context("conversation",null,query));
        var bodyFailure=assertThrows(ChatDeliberationException.class,
                () -> controller.context("conversation",null,body));

        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,queryFailure.reason());
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,bodyFailure.reason());
        verifyNoInteractions(previews,identities,tenants);
    }

    private static void assertInvalidJsonAcrossWireEntrypoints(String kind) {
        String schema=invalidJson("{\"schemaVersion\":3}",kind);
        String intent=invalidJson(generateBody(false),kind);
        String issue=invalidJson(issueBody(),kind);
        String revoke=invalidJson(revokeBody(),kind);
        assertInvalidRequest(() -> ChatBountyInteractionV3Wire.schemaVersion(schema));
        assertInvalidRequest(() -> ChatBountyInteractionV3Wire.parseIntent(intent,false));
        assertInvalidRequest(() -> ChatBountyInteractionV3Wire.parseIssue(issue));
        assertInvalidRequest(() -> ChatBountyInteractionV3Wire.parseRevoke(revoke));
    }

    private static String invalidJson(String valid,String kind) {
        return switch(kind) {
            case "{" -> "{";
            case "duplicate" -> valid.replace("{", "{\"schemaVersion\":2,");
            case "trailing" -> valid+" {}";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static void assertInvalidRequest(org.junit.jupiter.api.function.Executable executable) {
        var failure=assertThrows(IllegalArgumentException.class,executable);
        assertEquals("BOUNTY_FOLLOWUP_V3_INVALID_REQUEST",failure.getMessage());
    }

    private static String issueBody() {
        String hash="a".repeat(64);
        return "{"+
                "\"schemaVersion\":2,\"interactionIdempotencyKey\":\"interaction-key\",\"intent\":"+
                generateBody(false)+",\"providerBinding\":{\"bindingId\":\"binding\",\"bindingEpoch\":\"1\"},"+
                "\"expectedPreview\":{\"ownerPayloadSha256\":\""+hash+"\",\"instructionSha256\":\""+hash+
                "\",\"sourceSnapshotSha256\":\""+hash+"\",\"modelId\":\"model\",\"custody\":\"custody\","+
                "\"operatorPolicyRevision\":\"policy\"},\"acknowledgement\":\"ack\"}";
    }

    private static String revokeBody() {
        return "{\"schemaVersion\":2,\"operationGrantId\":\"opgrant_1234567890abcdef1234567890abcdef\","+
                "\"expectedConsentVersion\":\"1\",\"expectedOperationGrantVersion\":\"1\"}";
    }

    private static String generateBody(boolean authority) {
        return "{"+
                "\"schemaVersion\":3,\"interactionKind\":\"EXECUTE\",\"taskId\":\"task\","+
                "\"expectedConversationGeneration\":\"1\",\"expectedTaskVersion\":\"0\","+
                "\"expectedAssignmentRevision\":\"0\",\"expectedGrantVersion\":\"1\","+
                "\"requirementRevision\":\"1\",\"targetAgentId\":\"agent\","+
                "\"content\":\"draw\",\"actionProposal\":{\"kind\":\"generate_image\"},"+
                "\"inputRefs\":[{\"kind\":\"TASK_LINKED_WORKSPACE_VERSION\",\"fileId\":\"file\","+
                "\"version\":\"1\",\"purpose\":\"REFERENCE\"}],\"replyTo\":null,"+
                "\"continuationOf\":null"+
                (authority?",\"authority\":{\"consentId\":\"consent_1234567890abcdef1234567890abcdef\","+
                        "\"expectedConsentVersion\":\"1\",\"operationGrantId\":"+
                        "\"opgrant_1234567890abcdef1234567890abcdef\","+
                        "\"expectedOperationGrantVersion\":\"1\"}":"")+"}";
    }

    private static String editBody(boolean authority) {
        return "{"+
                "\"schemaVersion\":3,\"interactionKind\":\"EXECUTE\",\"taskId\":\"task\","+
                "\"expectedConversationGeneration\":\"1\",\"expectedTaskVersion\":\"0\","+
                "\"expectedAssignmentRevision\":\"0\",\"expectedGrantVersion\":\"1\","+
                "\"requirementRevision\":\"1\",\"targetAgentId\":\"agent\","+
                "\"content\":\"edit\",\"actionProposal\":{\"kind\":\"edit_image\"},"+
                "\"inputRefs\":[{\"kind\":\"CURRENT_CONVERSATION_ASSET\",\"assetRef\":"+
                "{\"assetId\":\"asset-1\",\"revision\":\"3\"}}],\"replyTo\":null,"+
                "\"continuationOf\":{\"requestId\":\"request-parent\",\"stepId\":\"step-parent\"}"+
                (authority?",\"authority\":{\"consentId\":\"consent_1234567890abcdef1234567890abcdef\","+
                        "\"expectedConsentVersion\":\"1\",\"operationGrantId\":"+
                        "\"opgrant_1234567890abcdef1234567890abcdef\","+
                        "\"expectedOperationGrantVersion\":\"1\"}":"")+"}";
    }
}
