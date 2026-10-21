package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskTargetSummaryDTO;
import cn.jia.agent.service.AgentExternalServicePolicyService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.config.ApiRequestEnvelopeAdvice;
import cn.jia.core.config.ExceptionHandlerAdvice;
import cn.jia.core.filter.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static cn.jia.agent.service.AgentTaskExecutionGrantException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real loopback HTTP -> verified JWT -> MVC -> production Q/ApiJsonData/R advice.
 * Only the business service is mocked here; persistence/concurrency live in the MySQL companion.
 * Reuses the embedded Tomcat pattern from ExecutionHistoryHttpContractTest, no external accounts.
 */
class AgentExternalServicePolicyControllerTest {
    private static final String ROUTE = "/agent/agt_provider/external-service-policy";
    private static final AgentTaskExecutionGrantService.Scope OWNER = scope("client-a", "provider");
    private final ObjectMapper json = JsonMapper.builder().build();
    @TempDir Path scratch;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext app;
    private HttpClient http;
    private String origin;
    private byte[] signingKey;
    private AgentExternalServicePolicyService service;

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    public static class HttpFixture {
        @Bean ObjectMapper json() { return JsonMapper.builder().build(); }
        @Bean AgentExternalServicePolicyController controller(AgentExternalServicePolicyService service, ObjectMapper json) {
            return new AgentExternalServicePolicyController(service, json);
        }
        @Bean ApiEnvelopeAdvice responseAdvice() { return new ApiEnvelopeAdvice(); }
        @Bean ApiRequestEnvelopeAdvice requestAdvice() { return new ApiRequestEnvelopeAdvice(); }
        @Bean ExceptionHandlerAdvice errors() { return new ExceptionHandlerAdvice(); }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {})).build();
        }
    }

    @BeforeEach void setUp() throws Exception {
        service = mock(AgentExternalServicePolicyService.class);
        signingKey = new byte[32]; new SecureRandom().nextBytes(signingKey);
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-external-policy-fixture"));
        tomcat = new Tomcat(); tomcat.setBaseDir(scratch.resolve("tomcat").toString()); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", Files.createDirectory(scratch.resolve("webroot")).toString());
        context.setParentClassLoader(getClass().getClassLoader());
        app = new AnnotationConfigWebApplicationContext(); app.setServletContext(context.getServletContext());
        app.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("policyService", service);
            factory.registerSingleton("jwtDecoder", decoder);
        });
        app.register(HttpFixture.class); app.refresh();
        var servlet = Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(app)); servlet.setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        filter(context, "requestId", new RequestIdFilter());
        filter(context, "security", new DelegatingFilterProxy("springSecurityFilterChain", app));
        tomcat.start(); origin = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
        http = HttpClient.newHttpClient();
    }

    @AfterEach void tearDown() throws Exception {
        try { if (http != null) http.close(); }
        finally {
            try { if (tomcat != null) { try { tomcat.stop(); } finally { tomcat.destroy(); } } }
            finally { if (app != null) app.close(); }
        }
    }

    @Test void qBooleanAndDecimalStringReachServiceAndRPreservesLargeRevisionAndOriginalReceipt() throws Exception {
        long expected = 9007199254740992L;
        var receipt = new AgentExternalServicePolicyService.Policy("agt_provider", true, "9007199254740993");
        when(service.write(OWNER, "agt_provider", "original-key", true, expected)).thenReturn(receipt);
        when(service.receipt(OWNER, "agt_provider", "original-key")).thenReturn(receipt);
        when(service.read(OWNER, "agt_provider")).thenReturn(
                new AgentExternalServicePolicyService.Policy("agt_provider", false, "9007199254740994"));
        String token = token("client-a", "provider", Map.of("tenant_id", "0", "tenantId", "0"));
        JsonNode first = envelope(send("PUT", ROUTE, token,
                q("{\"enabled\":true,\"expectedRevision\":\"9007199254740992\"}"), "original-key"), 200);
        assertEquals(Set.of("agentId", "enabled", "revision"), Set.copyOf(first.get("data").propertyNames()));
        assertTrue(first.at("/data/enabled").isBoolean()); assertTrue(first.at("/data/enabled").asBoolean());
        assertTrue(first.at("/data/revision").isTextual()); assertEquals("9007199254740993", first.at("/data/revision").asText());
        assertEquals(first.get("data"), envelope(send("GET", ROUTE + "/request", token, null, "original-key"), 200).get("data"));
        assertEquals(first.get("data"), envelope(send("PUT", ROUTE, token,
                q("{\"enabled\":true,\"expectedRevision\":\"9007199254740992\"}"), "original-key"), 200).get("data"));
        assertFalse(envelope(send("GET", ROUTE, token, null, null), 200).at("/data/enabled").asBoolean());
        verify(service, times(2)).write(OWNER, "agt_provider", "original-key", true, expected);
    }

    @Test void falseAndZeroAreTypedNotMissingAndDoNotCoerceStringEnabled() throws Exception {
        when(service.write(OWNER, "agt_provider", "key", false, 0L)).thenReturn(
                new AgentExternalServicePolicyService.Policy("agt_provider", false, "1"));
        var result = envelope(send("PUT", ROUTE, ownerToken(), q("{\"enabled\":false,\"expectedRevision\":\"0\"}"), "key"), 200);
        assertFalse(result.at("/data/enabled").asBoolean());
        verify(service).write(OWNER, "agt_provider", "key", false, 0L);
    }

    @Test void invalidDataAndUnwrappedOrDuplicateQAreRejectedBeforeService() throws Exception {
        String token = ownerToken();
        for (String data : List.of(
                "{\"enabled\":\"true\",\"expectedRevision\":\"0\"}",
                "{\"enabled\":1,\"expectedRevision\":\"0\"}",
                "{\"enabled\":true,\"expectedRevision\":0}",
                "{\"enabled\":true,\"expectedRevision\":\"01\"}",
                "{\"enabled\":true,\"expectedRevision\":\"-1\"}",
                "{\"enabled\":true,\"expectedRevision\":\"9223372036854775808\"}",
                "{\"enabled\":true,\"expectedRevision\":\" 0\"}",
                "{\"enabled\":true,\"expectedRevision\":null}",
                "{\"enabled\":null,\"expectedRevision\":\"0\"}",
                "{\"enabled\":true}", "null", "[]",
                "{\"enabled\":true,\"expectedRevision\":\"0\",\"owner\":\"victim\"}",
                "{\"enabled\":true,\"enabled\":false,\"expectedRevision\":\"0\"}")) {
            assertEquals(400, send("PUT", ROUTE, token, q(data), "key").statusCode(), data);
        }
        for (String body : List.of("{\"enabled\":true,\"expectedRevision\":\"0\"}",
                q("{}") + " {}", q("{}").replace("\"protocolVersion\":1", "\"protocolVersion\":2"))) {
            assertEquals(400, send("PUT", ROUTE, token, body, "key").statusCode());
        }
        assertEquals(400, send("PUT", ROUTE, token, q("{\"enabled\":true,\"expectedRevision\":\"0\"}"), null).statusCode());
        verifyNoInteractions(service);
    }

    @Test void qRequestIdMismatchAndMalformedCandidatePageSizeNeverReachService() throws Exception {
        String token = ownerToken();
        String mismatched = q("{\"enabled\":true,\"expectedRevision\":\"0\"}")
                .replace("external-policy-wire", "another-request");
        assertEquals(400, send("PUT", ROUTE, token, mismatched, "key").statusCode());
        assertEquals(400, send("GET", "/agent/tasks/task-a/assignee-candidates?pageSize=not-an-integer", token, null, null).statusCode());
        verifyNoInteractions(service);
    }

    @Test void jwtOwnerAndClientRemainDistinctAndDeniedScopeCannotEnumerateOrWrite() throws Exception {
        var denied = new AgentTaskExecutionGrantException(NOT_FOUND, "Unavailable");
        when(service.read(any(), eq("agt_provider"))).thenThrow(denied);
        when(service.write(any(), eq("agt_provider"), anyString(), anyBoolean(), anyLong())).thenThrow(denied);
        when(service.candidates(any(), eq("task-a"), isNull(), eq(50))).thenThrow(denied);
        for (var scope : List.of(scope("client-a", "other-owner"), scope("client-b", "provider"))) {
            String token = token(scope.clientId(), scope.ownerJiacn(), Map.of("tenant_id", "0"));
            envelope(send("GET", ROUTE, token, null, null), 404);
            envelope(send("PUT", ROUTE, token, q("{\"enabled\":true,\"expectedRevision\":\"0\"}"), "key"), 404);
            envelope(send("GET", "/agent/tasks/task-a/assignee-candidates", token, null, null), 404);
            verify(service).read(scope, "agt_provider");
            verify(service).write(scope, "agt_provider", "key", true, 0L);
            verify(service).candidates(scope, "task-a", null, 50);
        }
    }

    @Test void absentTamperedJwtAndConflictingTenantClaimsNeverReachBusinessService() throws Exception {
        assertEquals(401, send("GET", ROUTE, null, null, null).statusCode());
        String valid = ownerToken();
        String[] parts = valid.split("\\.");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"jiacn\":\"victim\"}".getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertEquals(401, send("GET", ROUTE, forged, null, null).statusCode());
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of("tenant_id", "0", "tenantId", "1"), Map.of("tenant_id", "1"),
                Map.of("tenant_id", 0), Map.of("tenant_id", "0", "tenant_claim_version", "2"))) {
            envelope(send("GET", ROUTE, token("client-a", "provider", claims), null, null), 401);
        }
        for (String owner : List.of("", "0", " provider")) {
            envelope(send("GET", ROUTE, token("client-a", owner, Map.of("tenant_id", "0")), null, null), 401);
        }
        verifyNoInteractions(service);
    }

    @Test void candidateGetUsesCanonicalCursorAndOnlyFiveSafeFieldsIncludingNullProvider() throws Exception {
        var mine = new AgentTaskTargetSummaryDTO("agt_a", "我的Agent", null, true, "offline");
        var external = new AgentTaskTargetSummaryDTO("agt_b", "承办Agent", null, false, "busy");
        when(service.candidates(OWNER, "task-a", null, 1)).thenReturn(new AgentExternalServicePolicyService.Candidates(List.of(mine), "agt_a"));
        when(service.candidates(OWNER, "task-a", "agt_a", 1)).thenReturn(new AgentExternalServicePolicyService.Candidates(List.of(external), null));
        String token = ownerToken();
        var first = envelope(send("GET", "/agent/tasks/task-a/assignee-candidates?pageSize=1", token, null, null), 200).get("data");
        var last = envelope(send("GET", "/agent/tasks/task-a/assignee-candidates?pageSize=1&afterAgentId=agt_a", token, null, null), 200).get("data");
        assertEquals(Set.of("items", "nextAfterAgentId"), Set.copyOf(first.propertyNames()));
        assertEquals("agt_a", first.get("nextAfterAgentId").asText());
        assertTrue(last.get("nextAfterAgentId").isNull());
        for (JsonNode page : List.of(first, last)) {
            var item = page.get("items").get(0);
            assertEquals(Set.of("agentId", "displayName", "providerDisplayName", "isMine", "availability"), Set.copyOf(item.propertyNames()));
            assertTrue(item.get("providerDisplayName").isNull()); assertTrue(item.get("isMine").isBoolean());
        }
        assertEquals("offline", first.at("/items/0/availability").asText());
        assertEquals("busy", last.at("/items/0/availability").asText());
        verify(service).candidates(OWNER, "task-a", "agt_a", 1);
    }

    @Test void notFoundReceiptAndConflictRemainDistinctRFailuresWithNoStore() throws Exception {
        when(service.receipt(OWNER, "agt_provider", "unknown")).thenThrow(new AgentTaskExecutionGrantException(NOT_FOUND, "Unknown original"));
        when(service.write(OWNER, "agt_provider", "stale", true, 0L)).thenThrow(new AgentTaskExecutionGrantException(CONFLICT, "Stale revision"));
        when(service.write(OWNER, "agt_provider", "changed", false, 0L)).thenThrow(new AgentTaskExecutionGrantException(IDEMPOTENCY_CONFLICT, "Changed original"));
        assertEquals(NOT_FOUND.code(), envelope(send("GET", ROUTE + "/request", ownerToken(), null, "unknown"), 404).get("code").asText());
        assertEquals(CONFLICT.code(), envelope(send("PUT", ROUTE, ownerToken(), q("{\"enabled\":true,\"expectedRevision\":\"0\"}"), "stale"), 409).get("code").asText());
        assertEquals(IDEMPOTENCY_CONFLICT.code(), envelope(send("PUT", ROUTE, ownerToken(), q("{\"enabled\":false,\"expectedRevision\":\"0\"}"), "changed"), 409).get("code").asText());
    }

    private JsonNode envelope(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var root = json.readTree(response.body());
        assertEquals(Set.of("protocolVersion", "requestId", "code", "msg", "data"), Set.copyOf(root.propertyNames()));
        assertEquals(1, root.get("protocolVersion").asInt());
        assertEquals("external-policy-wire", root.get("requestId").asText());
        assertEquals(root.get("requestId").asText(), response.headers().firstValue("X-Request-Id").orElseThrow());
        if (status >= 400) assertTrue(root.get("data").isNull());
        else assertEquals("E0", root.get("code").asText());
        return root;
    }
    private HttpResponse<String> send(String method, String route, String token, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + route)).header("X-Request-Id", "external-policy-wire")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private String ownerToken() throws Exception { return token("client-a", "provider", Map.of("tenant_id", "0")); }
    private String token(String client, String owner, Map<String, Object> tenantClaims) throws Exception {
        var claims = new HashMap<String, Object>(tenantClaims);
        claims.put("iss", "cyf-external-policy-fixture"); claims.put("sub", owner);
        claims.put("client_id", client); claims.put("jiacn", owner);
        claims.put("iat", Instant.now().getEpochSecond()); claims.put("exp", Instant.now().plusSeconds(600).getEpochSecond());
        var base64 = Base64.getUrlEncoder().withoutPadding();
        String unsigned = base64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + base64.encodeToString(json.writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return unsigned + "." + base64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
    }
    private static String q(String data) { return "{\"protocolVersion\":1,\"requestId\":\"external-policy-wire\",\"data\":" + data + "}"; }
    private static AgentTaskExecutionGrantService.Scope scope(String client, String owner) { return new AgentTaskExecutionGrantService.Scope("0", client, owner); }
    private static void filter(Context context, String name, Filter filter) {
        FilterDef definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); context.addFilterDef(definition);
        FilterMap mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*");
        mapping.setDispatcher(DispatcherType.REQUEST.name()); context.addFilterMap(mapping);
    }
}
