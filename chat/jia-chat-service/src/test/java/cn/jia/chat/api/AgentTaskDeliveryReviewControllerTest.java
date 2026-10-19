package cn.jia.chat.api;

import cn.jia.chat.service.*;
import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import cn.jia.core.context.EsContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskDeliveryReviewControllerTest {
    final ChatTaskDeliveryReviewService service = mock(ChatTaskDeliveryReviewService.class);
    final HumanSenderIdentityResolver identities = mock(HumanSenderIdentityResolver.class);
    final TenantScopeResolver tenants = mock(TenantScopeResolver.class);
    final Authentication auth = mock(Authentication.class);
    MockMvc mvc;
    @BeforeEach void ready() {
        when(tenants.resolve(auth)).thenReturn("0");
        when(identities.resolve(nullable(EsContext.class))).thenReturn(new ServerResolvedSender("user","Owner","owner","client",DisplayNameSource.JIACN));
        mvc=MockMvcBuilders.standaloneSetup(new AgentTaskDeliveryReviewController(service,identities,tenants,mock(ChatTaskDeliveryReviewReadService.class)))
                .setControllerAdvice(new cn.jia.core.config.ApiEnvelopeAdvice(),new cn.jia.core.config.ExceptionHandlerAdvice())
                .addFilters(new cn.jia.core.filter.RequestIdFilter()).build();
    }
    String body() { return "{\"protocolVersion\":1,\"requestId\":\"review-http\",\"data\":" + ChatDeliveryReviewContract.json(
            ChatDeliveryReviewContract.body(new Accept("delivery",Long.MAX_VALUE,0,1,"a".repeat(64)))) + "}"; }
    Receipt receipt(String state,String error) { return new Receipt(1,"review_1","task","delivery","ACCEPT",state,"2","COMPLETED","9223372036854775807","ACCEPTED","1",null,null,null,error,false); }
    @Test void explicitAcceptanceUsesSingleCanonicalEnvelopeAndExactStringVersions() throws Exception {
        when(service.accept(any(),eq("task"),eq("review-key"),any())).thenReturn(receipt("COMMITTED",null));
        mvc.perform(post("/agent/tasks/task/delivery-review-operations").principal(auth).header("Idempotency-Key","review-key").contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.protocolVersion").value(1)).andExpect(jsonPath("$.requestId").value("review-http"))
                .andExpect(jsonPath("$.code").value("E0")).andExpect(jsonPath("$.data.state").value("COMMITTED"))
                .andExpect(jsonPath("$.data.taskVersion").value("9223372036854775807"));
        verify(service).accept(eq(new Scope("0","client","owner")),eq("task"),eq("review-key"),argThat(v -> v.taskVersion()==Long.MAX_VALUE));
    }
    @Test void noBareBodiesHttpRevisionInjectedScopeOrDuplicateKeysReachBusinessService() throws Exception {
        for (String bad : java.util.List.of(body().replace("ACCEPT","REQUEST_CHANGES"),body().replace("\"instruction\":null","\"instruction\":\"change\""),
                body().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"owner\":\"other\""),body().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"))) {
            mvc.perform(post("/agent/tasks/task/delivery-review-operations").principal(auth).header("Idempotency-Key","review-key").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        }
        verifyNoInteractions(service);
    }
    @Test void businessRejectionReturnsErrorWithoutASuccessReceiptAndGetNeverDecides() throws Exception {
        when(service.accept(any(),any(),any(),any())).thenReturn(receipt("REJECTED","DELIVERY_REVIEW_CONFLICT"));
        mvc.perform(post("/agent/tasks/task/delivery-review-operations").principal(auth).header("Idempotency-Key","review-key").contentType("application/json").content(body()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_REVIEW_CONFLICT")).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        reset(service); when(service.byKey(any(),eq("task"),eq("review-key"))).thenReturn(receipt("REJECTED","DELIVERY_REVIEW_CONFLICT"));
        mvc.perform(get("/agent/tasks/task/delivery-review-operations/request").principal(auth).header("Idempotency-Key","review-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("REJECTED"));
        verify(service,never()).accept(any(),any(),any(),any());
    }
}
