package cn.jia.agent.api;

import cn.jia.agent.service.HallPrivateMarkService;
import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallRequestDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HallPrivateMarkControllerTest {
    private HallPrivateMarkService service;
    private MockMvc mvc;
    @BeforeEach void setUp() {
        service=mock(HallPrivateMarkService.class);
        mvc=MockMvcBuilders.standaloneSetup(new HallPrivateMarkController(service))
                .addFilters(new HallRequestDraftCacheControlFilter()).build();
    }
    @Test void strictMarkBodyHeaderAndJwtScopeAreForwardedWithoutTaskAuthority() throws Exception {
        when(service.mark(any(),eq("LEGACY_EXECUTION"),eq("e"),any(),eq("key")))
                .thenReturn(new HallPrivateMarkService.View(new HallReadService.Ref("LEGACY_EXECUTION","e"),1,false,
                        new HallPrivateMarkService.ResultRef("e","m"),1000));
        mvc.perform(patch("/agent/hall/items/LEGACY_EXECUTION/e/mark").principal(jwt()).header("Idempotency-Key","key")
                .contentType("application/json").content("{\"expectedRevision\":0,\"archived\":false,\"viewedResultRef\":{\"executionId\":\"e\",\"manifestId\":\"m\"}}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.ref.sourceType").value("LEGACY_EXECUTION"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.viewedResultRef.manifestId").value("m"));
        verify(service).mark(new HallRequestDraftService.OwnerScope("0","c","o"),"LEGACY_EXECUTION","e",
                new HallPrivateMarkService.Command(0,false,new HallPrivateMarkService.ResultRef("e","m")),"key");
        when(service.mark(any(),eq("TASK"),any(),any(),any())).thenThrow(new HallRequestDraftService.Failure(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE));
        mvc.perform(patch("/agent/hall/items/TASK/task/mark").principal(jwt()).header("Idempotency-Key","task")
                .contentType("application/json").content("{\"expectedRevision\":0,\"archived\":true}"))
                .andExpect(status().isUnprocessableEntity());
    }
    @Test void missingIdentityDuplicateFieldsAndClientAuthorityInjectionAreRejected() throws Exception {
        mvc.perform(get("/agent/hall/items/PRIVATE_CASE/case/mark")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control","private, no-store"));
        for(String body:new String[]{"{\"expectedRevision\":0}","{\"expectedRevision\":0,\"archived\":false,\"archived\":true}",
                "{\"expectedRevision\":0,\"archived\":true,\"ownerJiacn\":\"foreign\"}","{\"expectedRevision\":\"0\",\"archived\":true}"}) {
            mvc.perform(patch("/agent/hall/items/PRIVATE_CASE/case/mark").principal(jwt()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/agent/hall/items/PRIVATE_CASE/case/mark?ownerJiacn=foreign").principal(jwt())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    private static JwtAuthenticationToken jwt() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg","none").subject("o")
                .claim("jiacn","o").claim("client_id","c").issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build());
    }
}
