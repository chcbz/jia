package cn.jia.chat.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;

class TenantScopeResolverTest {
    private final TenantScopeResolver resolver = new TenantScopeResolver();

    @Test void preservesSignedPartitionWithoutLegacyZeroFallback() {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            assertEquals(tenant, resolver.resolve(jwt(Map.of("tenant_id", tenant))));
        }
        for (Map<String, Object> claims : List.<Map<String, Object>>of(
                Map.of(), Map.of("tenantId", "0"), Map.of("tenant_id", " 1"),
                Map.of("tenant_id", "1", "tenantId", "0"))) {
            assertThrows(ChatDeliberationException.class, () -> resolver.resolve(jwt(claims)));
        }
    }

    @Test void noReflectiveTenantFromAnUnspecifiedPrincipal() {
        Object principal = new Object() { public String getTenantId() { return "1"; } };
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal, "unused", List.of());
        assertThrows(ChatDeliberationException.class, () -> resolver.resolve(auth));
        assertThrows(ChatDeliberationException.class, () -> resolver.resolve(null));
    }

    private static JwtAuthenticationToken jwt(Map<String, Object> claims) {
        var builder = Jwt.withTokenValue("synthetic").header("alg", "none")
                .claim("jiacn", "owner").claim("client_id", "client");
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), List.of(), "owner");
    }
}
