package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TenantScopeResolverTest {
    private final TenantScopeResolver resolver = new TenantScopeResolver("0");

    @Test
    void canonicalClaimWinsAndLegacyUserUsesOneCentralDefault() {
        assertEquals("tenant-a", resolver.resolve(token("tenant-a", true)));
        assertEquals("0", resolver.resolve(token(null, true)));
    }

    @Test
    void machineOrUnauthenticatedTokenWithoutTenantFailsClosed() {
        assertThrows(ChatDeliberationException.class, () -> resolver.resolve(token(null, false)));
        assertThrows(ChatDeliberationException.class, () -> resolver.resolve(null));
    }

    private JwtAuthenticationToken token(String tenant, boolean user) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .subject(user ? "user-1" : "client-a").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("client_id", "client-a").claim("token_kind", user ? "user" : "machine");
        if (user) builder.claim("jiacn", "owner-a");
        if (tenant != null) builder.claim("tenant_id", tenant);
        return new JwtAuthenticationToken(builder.build(),
                java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
