package cn.jia.chat.api;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTypedDeliberationControllerTest {
    private final ChatTypedDiscussionAdmissionService admissions=mock(ChatTypedDiscussionAdmissionService.class);
    private final ChatTypedDeliberationService typed=mock(ChatTypedDeliberationService.class);
    private final ChatDeliberationService deliberation=mock(ChatDeliberationService.class);
    private final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    private final Authentication authentication=mock(Authentication.class);
    private final ServerResolvedSender sender=new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN);
    private final ChatTypedDeliberationController controller=new ChatTypedDeliberationController(admissions,typed,deliberation,identities,tenants);
    @AfterEach void clear(){cn.jia.core.context.EsContextHolder.clearContext();}

    @Test void postRequiresExactTenKeysAndReturnsNoStore202ForFreshOrReplay() {
        when(tenants.resolve(authentication)).thenReturn("0");when(identities.resolve(any())).thenReturn(sender);
        var accepted=new ChatTypedDeliberationWire.Accepted(1,"DISCUSSION","request","8",List.of("turn"),"RUNNING","0","7","/chat/requests/request","/chat/conversations/42/requests/request/typed-outcome",false,null);
        when(admissions.admit(eq("0"),eq(sender),eq("42"),eq("key"),any())).thenReturn(accepted);
        var response=controller.discuss("42","key",rawBody(),authentication);
        assertEquals(HttpStatus.ACCEPTED,response.getStatusCode());assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals(accepted,response.getBody().getData());
        String extra=rawBody().replace("\"sourceSelectors\":[]",
                "\"sourceSelectors\":[],\"agentId\":\"forged\"");
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,assertThrows(ChatDeliberationException.class,
                ()->controller.discuss("42","key",extra,authentication)).reason());
    }

    @Test void parserKeepsDecimalStringsNullsAndRejectsCoercionOrBadSelectors() {
        var parsed=ChatTypedDeliberationWire.parse(body());assertEquals(3,parsed.expectedAssignmentRevision());assertNull(parsed.parentOutcomeId());
        Map<String,Object> numeric=body();numeric.put("expectedAssignmentRevision",3);
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(numeric));
        Map<String,Object> tooLarge=body();tooLarge.put("sourceSelectors",List.of(selector("2147483648")));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(tooLarge));
        Map<String,Object> duplicate=body();duplicate.put("sourceSelectors",List.of(selector("1"),selector("1")));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(duplicate));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(
                rawBody().replace("\"intent\":\"DISCUSSION\"",
                        "\"intent\":\"DISCUSSION\",\"intent\":\"CLARIFICATION_REPLY\"")));
        assertThrows(ChatDeliberationException.class,()->ChatTypedDeliberationWire.parse(rawBody()+"{}"));
    }

    @Test void getIsReadOnlyAndReturnsPendingProjectionWithoutCapabilityLookup() {
        when(tenants.resolve(authentication)).thenReturn("0");when(identities.resolve(any())).thenReturn(sender);
        var request=new ChatDeliberationService.RequestView("request","1","42","5","8","RUNNING","0",List.of(
                new ChatDeliberationService.TurnView("turn","request","1","42","5","agent","snapshot","dispatch","CHAT","RECEIVED","0","0",null,null,"1","1")),List.of());
        when(deliberation.getRequest("0","owner","client","request")).thenReturn(request);
        var projection=new ChatTypedDeliberationWire.TypedProjection(1,"42","5","request","1","turn","PENDING",null);
        when(typed.read(new ChatTypedDeliberationStore.Scope("0","owner","client","42",5),"request","turn",1)).thenReturn(projection);
        var response=controller.outcome("42","request",authentication);assertEquals(projection,response.getBody().getData());assertEquals("no-store",response.getHeaders().getCacheControl());verifyNoInteractions(admissions);
    }

    @Test void errorMappingIsOpaqueAndBounded() {
        var missing=controller.error(new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"secret"));
        assertEquals(HttpStatus.NOT_FOUND,missing.getStatusCode());assertEquals("Typed discussion is unavailable",missing.getBody().getMsg());
        assertEquals(HttpStatus.CONFLICT,controller.error(new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,"changed")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.error(new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,"down")).getStatusCode());
        var unexpected=controller.unavailable(new IllegalStateException("database details"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,unexpected.getStatusCode());
        assertEquals("Typed discussion is temporarily unavailable",unexpected.getBody().getMsg());
        assertEquals("no-store",unexpected.getHeaders().getCacheControl());
    }

    private static String rawBody(){return "{\"schemaVersion\":1.0,\"intent\":\"DISCUSSION\",\"taskId\":\"task\",\"expectedAssignmentRevision\":\"3\",\"content\":\"继续讨论\",\"parentOutcomeId\":null,\"expectedParentStateVersion\":null,\"pendingQuestionId\":null,\"expectedPendingQuestionStateVersion\":null,\"sourceSelectors\":[]}";}
    private static Map<String,Object> body(){Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion",1.0);value.put("intent","DISCUSSION");value.put("taskId","task");value.put("expectedAssignmentRevision","3");value.put("content","继续讨论");value.put("parentOutcomeId",null);value.put("expectedParentStateVersion",null);value.put("pendingQuestionId",null);value.put("expectedPendingQuestionStateVersion",null);value.put("sourceSelectors",List.of());return value;}
    private static Map<String,Object> selector(String version){Map<String,Object> value=new LinkedHashMap<>();value.put("kind","TASK_LINKED_WORKSPACE_VERSION");value.put("fileId","file");value.put("version",version);value.put("purpose","REFERENCE");value.put("assetId",null);value.put("assetRevision",null);return value;}
}
