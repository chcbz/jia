package cn.jia.agent.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.pattern.PathPatternParser;

import java.net.URI;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentTaskWorkspaceCacheControlFilterTest {
    private static final String NO_STORE = "private, no-store";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mvcReachableCanonicalAndPerSegmentMatrixPathsMapWithNoStore() throws Exception {
        MockMvc mvc = probeMvc();

        for (String path : List.of(
                "/agent/tasks/task-1/events",
                "/agent/tasks/task-1/events;foo=bar",
                "/agent/tasks/task-1/events;jsessionid=x;a=b",
                "/agent;v=1/tasks;x=2/task-1;y=3/events;foo=bar",
                "/agent/tasks/task-1/workspace",
                "/agent/tasks/task-1/workspace;foo=bar",
                "/agent/tasks/task-1/workspace;jsessionid=x;a=b",
                "/agent;v=1/tasks;x=2/task-1;y=3/workspace;foo=bar")) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
        }

        mvc.perform(contextGet("/ctx/agent/tasks/task-1/events;foo=bar"))
                .andExpect(status().isOk())
                .andExpect(content().string("events:task-1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
        mvc.perform(contextGet("/ctx/agent/tasks/task-1/workspace;jsessionid=x;a=b"))
                .andExpect(status().isOk())
                .andExpect(content().string("workspace:task-1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
    }

    @Test
    void canonicalControllerErrorStillHasNoStore() throws Exception {
        probeMvc().perform(get("/agent/tasks/error/events"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
    }

    @Test
    void matrixEquivalentTargetsKeepNoStoreBeforeSecurity401And403() throws Exception {
        MockMvc secured = securedProbeMvc();
        List<String> paths = List.of(
                "/agent/tasks/task-1/events;foo=bar",
                "/agent/tasks/task-1/events;jsessionid=x;a=b",
                "/agent/tasks/task-1/workspace;foo=bar",
                "/agent/tasks/task-1/workspace;jsessionid=x;a=b");

        SecurityContextHolder.clearContext();
        for (String path : paths) {
            secured.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
        }
        secured.perform(contextGet("/ctx/agent/tasks/task-1/events;foo=bar"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));

        for (String path : paths) {
            authenticateHuman();
            secured.perform(get(path))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
        }
        authenticateHuman();
        secured.perform(contextGet(
                        "/ctx/agent/tasks/task-1/workspace;jsessionid=x;a=b"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, NO_STORE));
    }

    @Test
    void suffixExtraSegmentAndEncodedSemicolonPathsAreNotOvermatched() throws Exception {
        MockMvc mvc = probeMvc();

        for (String path : List.of(
                "/agent/tasks/task-1/events-extra",
                "/agent/tasks/task-1/workspace-extra",
                "/agent/tasks/task-1/events;foo/bar",
                "/agent/tasks/task-1/workspace;foo/bar",
                "/agent/tasks/task-1/events/extra",
                "/agent/tasks/task-1/workspace/extra")) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
        }

        mvc.perform(get(URI.create("/agent/tasks/task-1/events%3Bfoo=bar")))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.CACHE_CONTROL));
    }

    private static MockMvc probeMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setPatternParser(PathPatternParser.defaultInstance)
                .addFilters(new AgentTaskWorkspaceCacheControlFilter())
                .build();
    }

    private static MockMvc securedProbeMvc() {
        ExceptionTranslationFilter exceptionTranslation = new ExceptionTranslationFilter(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
        exceptionTranslation.setAccessDeniedHandler(new AccessDeniedHandlerImpl());
        AuthorizationFilter denyAll = new AuthorizationFilter(
                (authentication, request) -> new AuthorizationDecision(false));
        DefaultSecurityFilterChain securityChain = new DefaultSecurityFilterChain(
                AnyRequestMatcher.INSTANCE,
                new AnonymousAuthenticationFilter("c05-r2-test-key"),
                exceptionTranslation, denyAll);
        FilterChainProxy securityProxy = new FilterChainProxy(securityChain);
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowSemicolon(true);
        securityProxy.setFirewall(firewall);
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setPatternParser(PathPatternParser.defaultInstance)
                .addFilters(new AgentTaskWorkspaceCacheControlFilter(), securityProxy)
                .build();
    }

    private static MockHttpServletRequestBuilder contextGet(String path) {
        return get(path).contextPath("/ctx");
    }

    private static void authenticateHuman() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "human", "n/a", List.of()));
    }

    @RestController
    @RequestMapping("/agent/tasks")
    private static final class ProbeController {
        @GetMapping("/{taskId}/events")
        String events(@PathVariable String taskId) {
            if ("error".equals(taskId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            return "events:" + taskId;
        }

        @GetMapping("/{taskId}/workspace")
        String workspace(@PathVariable String taskId) {
            return "workspace:" + taskId;
        }
    }
}
