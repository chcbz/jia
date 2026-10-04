package cn.jia.chat.api;

import cn.jia.chat.service.ChatSelectedOutputFinalizationService;
import cn.jia.chat.service.DisplayNameSource;
import cn.jia.chat.service.HumanSenderIdentityResolver;
import cn.jia.chat.service.ServerResolvedSender;
import cn.jia.chat.service.TenantScopeResolver;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskSelectedOutputFinalizationControllerTest {
    private final ChatSelectedOutputFinalizationService service=mock(ChatSelectedOutputFinalizationService.class);
    private final HumanSenderIdentityResolver identities=mock(HumanSenderIdentityResolver.class);
    private final TenantScopeResolver tenants=mock(TenantScopeResolver.class);
    private final Authentication authentication=mock(Authentication.class);
    private final AgentTaskSelectedOutputFinalizationController controller=
            new AgentTaskSelectedOutputFinalizationController(service,identities,tenants);

    @BeforeEach
    void ready(){
        when(tenants.resolve(authentication)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender(
                "user","Owner","owner-1","client-1",DisplayNameSource.JIACN));
        when(service.submit(any(),eq("task-1"),anyString(),any())).thenAnswer(invocation->{
            var command=(ChatSelectedOutputFinalizationService.Command)invocation.getArgument(3);
            return new ChatSelectedOutputFinalizationService.Receipt("fin-1","task-1",command.conversationId(),
                    "pending","1","PROMOTING",Long.toString(command.expectedTaskVersion()),
                    Long.toString(command.expectedAssignmentRevision()),command.selectedOutputs(),null,null,
                    "assigned",Long.toString(command.expectedTaskVersion()),null,false);
        });
    }

    @Test
    void derivedBodyBoundAcceptsNinetyNineMaximumEscapedUnicodeSelections(){
        String body=maxEscapedBody("9007199254740991");
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length<AgentTaskSelectedOutputFinalizationController.MAX_BODY);
        var response=controller.submit("task-1","12345678",request(body),authentication);
        assertEquals(HttpStatus.ACCEPTED,response.getStatusCode());
        verify(service).submit(any(),eq("task-1"),eq("12345678"),argThat(command ->
                command.selectedOutputs().size()==99 && command.summary().length()==4000
                        && command.selectedOutputs().getFirst().title().length()==255));
    }


    @Test
    void insignificantWhitespaceIsNotSubjectToAnInventedRawBodyLimit(){
        String body=" ".repeat(AgentTaskSelectedOutputFinalizationController.MAX_BODY+1)+minimalBody("0");
        assertEquals(HttpStatus.ACCEPTED,controller.submit("task-1","12345678",request(body),authentication).getStatusCode());
    }

    @Test
    void truthfulChangesRequestedReceiptUsesConflictInsteadOfInternalError(){
        when(service.submit(any(),eq("task-1"),anyString(),any())).thenReturn(
                new ChatSelectedOutputFinalizationService.Receipt("fin-1","task-1","100","failed","2",
                        "SUBMITTED","7","7",List.of(),"delivery-1","changes_requested","running","10",
                        "FINALIZATION_DELIVERY_CHANGES_REQUESTED",false));
        assertEquals(HttpStatus.CONFLICT,controller.submit("task-1","12345678",
                request(minimalBody("0")),authentication).getStatusCode());
    }

    @Test
    void versionsAboveJavascriptSafeIntegerAreRejectedBeforeService(){
        assertThrows(RuntimeException.class,()->controller.submit("task-1","12345678",
                request(minimalBody("9007199254740992")),authentication));
        verifyNoInteractions(service);
    }

    @Test
    void idempotencyKeyUsesExactEightThroughOneHundredSixtyBoundary(){
        String body=minimalBody("0");
        assertThrows(RuntimeException.class,()->controller.submit("task-1","1234567",request(body),authentication));
        assertEquals(HttpStatus.ACCEPTED,controller.submit("task-1","12345678",request(body),authentication).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED,controller.submit("task-1","k".repeat(160),request(body),authentication).getStatusCode());
        assertThrows(RuntimeException.class,()->controller.submit("task-1","k".repeat(161),request(body),authentication));
        assertThrows(RuntimeException.class,()->controller.submit("task-1","unsafe/key",request(body),authentication));
    }

    @Test
    void unsafePathAndBodyIdentifiersAreRejected(){
        assertThrows(RuntimeException.class,()->controller.submit("task/1","12345678",
                request(minimalBody("0")),authentication));
        String forged=minimalBody("0").replace("req-1","req/1");
        assertThrows(RuntimeException.class,()->controller.submit("task-1","12345678",request(forged),authentication));
    }

    @Test
    void everyBodyIdentifierIsValidatedBeforeServiceWhileHumanTextMayContainSlashes(){
        for(String[] pair:List.of(new String[]{"100","conv/1"},new String[]{"req-1","req/1"},
                new String[]{"step-1","step/1"},new String[]{"out-1","out/1"})){
            assertThrows(RuntimeException.class,()->controller.submit("task-1","12345678",
                    request(minimalBody("0").replace("\""+pair[0]+"\"","\""+pair[1]+"\"")),authentication));
        }
        verifyNoInteractions(service);
        assertEquals(HttpStatus.ACCEPTED,controller.submit("task-1","12345678",
                request(minimalBody("0").replace("\"title\":\"title\"","\"title\":\"title/a\"")),authentication).getStatusCode());
    }

    private static String textBody(String id){
        return "{\"expectedTaskVersion\":7,\"expectedAssignmentRevision\":7,\"conversationId\":\"100\",\"summary\":\"accept\",\"selectedOutputs\":[{"
                +"\"requestId\":\"req-1\",\"sha256\":\""+"a".repeat(64)+"\",\"title\":\"Text\",\"purpose\":\"final\","
                +"\"messageSource\":{\"turnId\":\"turn-1\",\"messageId\":\""+id+"\",\"snapshotId\":\"snapshot-1\",\"finalDigest\":\"sha256:"+"b".repeat(64)+"\"}}]}";
    }
    @Test void completedTextHasExactRealMessageSourceUnionAndStringLongId() throws Exception {
        var response=controller.submit("task-1","12345678",request(textBody("9223372036854775807")),authentication);
        var output=response.getBody().getData().selectedOutputs().getFirst();
        assertNull(output.stepId());assertNull(output.outputId());assertEquals("9223372036854775807",output.messageSource().messageId());
        var mapper=new tools.jackson.databind.ObjectMapper();var json=mapper.valueToTree(output);
        assertEquals(5,json.size());assertFalse(json.has("stepId"));assertFalse(json.has("outputId"));assertEquals(4,json.get("messageSource").size());
        var original=response.getBody().getData().selectedOutputs().getFirst();
        var media=new ChatSelectedOutputFinalizationService.Selection(original.requestId(),"step-1","out-1",original.sha256(),original.title(),original.purpose());
        assertEquals(6,mapper.valueToTree(media).size());assertFalse(mapper.valueToTree(media).has("messageSource"));
    }
    @Test void textUnionRejectsMixedAuthorityUnknownFieldsNullAndNoncanonicalOrOverflowMessageIds(){
        for(String id:List.of("0","09","-1","9223372036854775808"))assertThrows(RuntimeException.class,
                ()->controller.submit("task-1","12345678",request(textBody(id)),authentication));
        for(String body:List.of(textBody("9").replace("\"messageSource\":{","\"outputId\":\"fake\",\"messageSource\":{") ,
                textBody("9").replace("\"turnId\":\"turn-1\"","\"turnId\":\"turn-1\",\"executionId\":\"fake\""),
                textBody("9").replace("\"messageId\":\"9\"","\"messageId\":9"),
                textBody("9").replace("\"finalDigest\":\"sha256:","\"finalDigest\":\"sha255:"),
                textBody("9").replace("\"turnId\":\"turn-1\"","\"turnId\":null"),
                textBody("9").replace("\"messageId\":\"9\"","\"messageId\":\"9\",\"messageId\":\"10\"")))
            assertThrows(RuntimeException.class,()->controller.submit("task-1","12345678",request(body),authentication));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequest request(String json){
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/agent/tasks/task-1/finalizations");
        request.setContentType("application/json");request.setContent(json.getBytes(StandardCharsets.UTF_8));return request;
    }
    private static String minimalBody(String version){
        return "{\"expectedTaskVersion\":"+version+",\"expectedAssignmentRevision\":0,"
                +"\"conversationId\":\"100\",\"summary\":\"accept\",\"selectedOutputs\":[{"
                +"\"requestId\":\"req-1\",\"stepId\":\"step-1\",\"outputId\":\"out-1\","
                +"\"sha256\":\""+"a".repeat(64)+"\",\"title\":\"title\",\"purpose\":\"final\"}]}";
    }
    private static String maxEscapedBody(String version){
        String escaped="\\u4e2d";
        StringBuilder body=new StringBuilder(AgentTaskSelectedOutputFinalizationController.MAX_BODY);
        body.append("{\"expectedTaskVersion\":").append(version)
                .append(",\"expectedAssignmentRevision\":9007199254740991,\"conversationId\":\"")
                .append("c".repeat(100)).append("\",\"summary\":\"").append(escaped.repeat(4000))
                .append("\",\"selectedOutputs\":[");
        for(int i=0;i<99;i++){
            if(i>0)body.append(',');
            String suffix=String.format("%02d",i);
            body.append("{\"requestId\":\"").append("r".repeat(98)).append(suffix)
                    .append("\",\"stepId\":\"").append("s".repeat(98)).append(suffix)
                    .append("\",\"outputId\":\"").append("o".repeat(98)).append(suffix)
                    .append("\",\"sha256\":\"").append("a".repeat(64))
                    .append("\",\"title\":\"").append(escaped.repeat(255))
                    .append("\",\"purpose\":\"").append(escaped.repeat(255)).append("\"}");
        }
        return body.append("]}").toString();
    }
}
