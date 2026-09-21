package cn.jia.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HallRequestDraftCacheControlFilterTest {
    @Test
    void rootAndDescendantsReceiveNoStoreBeforeSecurity401() throws Exception {
        ExceptionTranslationFilter translation = new ExceptionTranslationFilter(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        AuthorizationFilter deny = new AuthorizationFilter(
                (authentication, request) -> new AuthorizationDecision(false));
        FilterChainProxy security = new FilterChainProxy(new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE, new AnonymousAuthenticationFilter("hall-test"),
                translation, deny));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new HallRequestDraftCacheControlFilter(), security).build();

        for (String path : java.util.List.of(
                "/agent/hall/drafts", "/agent/hall/drafts/hdr_1",
                "/agent/hall/drafts/hdr_1/discard")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }
        mvc.perform(get("/agent/hall/drafts-extra"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }

    @RestController
    @RequestMapping("/agent/hall/drafts")
    private static final class Probe {
        @GetMapping String root() { return "ok"; }
    }
}
