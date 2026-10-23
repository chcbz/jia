package cn.jia.chat.api;

import cn.jia.chat.service.*;
import cn.jia.chat.service.ChatBountyExecutionTerminationService.*;
import cn.jia.core.context.EsContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyExecutionTerminationControllerTest {
    private final ChatBountyExecutionTerminationService service=mock(ChatBountyExecutionTerminationService.class);
    private final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    private final Authentication auth=mock(Authentication.class);
    private final ChatBountyExecutionTerminationController controller=
            new ChatBountyExecutionTerminationController(service,identities,tenants);
    private static final String BODY="""
            {"stepId":"step","executionId":"exec","expectedRequestStateVersion":"0",
             "expectedStepStateVersion":"2","reason":"OWNER_ABANDONED_UNDELIVERED"}
            """;
    @AfterEach void clear(){EsContextHolder.clearContext();}
    private void human() {
        when(tenants.resolve(auth)).thenReturn("0");
        when(identities.resolve(any())).thenReturn(new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.JIACN));
    }
    @Test void exactBodyAndServerIdentityOnlyWithNoStoreReceipt() {
        human();
        var receipt=new Receipt("op","42","req","step","exec","CANCELLED","1","3",
                ChatBountyExecutionTerminationService.REASON,true,false,true);
        var scope=new Scope("0","owner","client","42");
        when(service.abandon(eq(scope),eq("req"),eq("key-0001"),any())).thenReturn(receipt);
        var response=controller.abandon("42","req","key-0001",BODY,auth);
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals("nosniff",response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(receipt,response.getBody().getData());
        verify(service).abandon(scope,"req","key-0001",new Command("step","exec","0","2",
                ChatBountyExecutionTerminationService.REASON));
    }
    @Test void rawParserRejectsUnknownDuplicateMissingNullNumericAndTrailingJson() {
        for(String invalid:new String[]{"null","[]","{}",BODY+"{}",
                BODY.replace("\"stepId\":\"step\"","\"stepId\":\"step\",\"stepId\":\"other\""),
                BODY.replace("\"stepId\":\"step\"","\"stepId\":null"),
                BODY.replace("\"expectedRequestStateVersion\":\"0\"","\"expectedRequestStateVersion\":0"),
                BODY.replace("\"stepId\":\"step\"","\"stepId\":\"step\",\"owner\":\"forged\"")}) {
            assertEquals(Reason.INVALID_REQUEST,assertThrows(Failure.class,
                    ()->ChatBountyExecutionTerminationController.parse(invalid)).reason());
        }
        verifyNoInteractions(service);
    }
    @Test void getOnlyReadsOriginalKeyAndNeverReplaysAbandonment() {
        human();controller.outcome("42","req","key-0001",auth);
        verify(service).get(new Scope("0","owner","client","42"),"req","key-0001");
        verify(service,never()).abandon(any(),anyString(),anyString(),any());
    }
    @Test void nonHumanOrMissingIdentityCannotMutate() {
        when(tenants.resolve(auth)).thenReturn("0");
        for(ServerResolvedSender sender:new ServerResolvedSender[]{null,
                new ServerResolvedSender("agent","Agent","owner","client",DisplayNameSource.JIACN)}) {
            when(identities.resolve(any())).thenReturn(sender);
            assertEquals(Reason.NOT_FOUND_OR_FORBIDDEN,assertThrows(Failure.class,
                    ()->controller.abandon("42","req","key-0001",BODY,auth)).reason());
        }
        verifyNoInteractions(service);
    }
    @Test void errorsAreOpaqueAndBounded() {
        assertEquals(HttpStatus.BAD_REQUEST,controller.error(new Failure(Reason.INVALID_REQUEST)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,controller.error(new Failure(Reason.NOT_FOUND_OR_FORBIDDEN)).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,controller.error(new Failure(Reason.CONFLICT)).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,controller.error(new Failure(Reason.UNAVAILABLE)).getStatusCode());
        var unexpected=controller.unavailable(new IllegalStateException("private db information"));
        assertEquals("Execution abandonment is unavailable",unexpected.getBody().getMsg());
        assertEquals("no-store",unexpected.getHeaders().getCacheControl());
    }
}
