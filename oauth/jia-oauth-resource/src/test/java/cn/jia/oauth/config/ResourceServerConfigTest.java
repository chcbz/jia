package cn.jia.oauth.config;

import cn.jia.test.BaseMockTest;
import cn.jia.oauth.security.TenantAuthorizationAttributes;
import cn.jia.oauth.security.TenantAuthorizationVerifier;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import com.sun.net.httpserver.HttpServer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.time.Instant;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ResourceServerConfigTest extends BaseMockTest {
    @Mock AccountSecurityService accountSecurityService;
    @Mock ObjectProvider<AccountSecurityService> accountSecurityServiceProvider;
    @Mock ObjectProvider<TenantAuthorizationVerifier> tenantAuthorizationVerifierProvider;
    private static final String ISSUER = "https://oauth.example.test";
    @Mock TenantAuthorizationVerifier tenantAuthorizationVerifier;

    @Test
    void decoderComposesStandardTimeValidationWithAccountValidation() {
        when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        TenantAuthorizationAttributes binding = new TenantAuthorizationAttributes(
                cn.jia.core.tenant.TenantId.of("1"), cn.jia.core.tenant.DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
        when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(tenantAuthorizationVerifier);
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(true);

        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> keys = config.jwkSource(config.centralSigningProperties());
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keys);
        JwtDecoder decoder = config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, config.tenantDeploymentProperties(), config.centralJwtTrustProperties(), config.centralSigningProperties());

        Jwt valid = decoder.decode(token(encoder, Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 4, true));
        assertEquals("17", valid.getClaimAsString("uid"));

        assertThrows(JwtValidationException.class,
                () -> decoder.decode(token(encoder, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), 4, true)));
        assertThrows(JwtValidationException.class,
                () -> decoder.decode(token(encoder, Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 3, true)));
        verify(accountSecurityService, atLeast(2)).findByUserId(17);
    }

    @Test
    void signedRegionTokenIsRecheckedOnEveryDecodeAndMissingVerifierFailsClosed() {
        when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        TenantAuthorizationAttributes binding = new TenantAuthorizationAttributes(
                cn.jia.core.tenant.TenantId.of("1"), cn.jia.core.tenant.DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
        when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(tenantAuthorizationVerifier);
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(true);
        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> keys = config.jwkSource(config.centralSigningProperties());
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keys);
        String signed = token(encoder, Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 4, true);
        JwtDecoder decoder = config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, config.tenantDeploymentProperties(), config.centralJwtTrustProperties(), config.centralSigningProperties());
        assertEquals("1", decoder.decode(signed).getClaimAsString("tenant_id"));
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(false);
        assertThrows(JwtValidationException.class, () -> decoder.decode(signed));
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web"))
                .thenThrow(new IllegalStateException("authoritative store unavailable"));
        assertThrows(JwtValidationException.class, () -> decoder.decode(signed));
        when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(null);
        JwtDecoder missing = config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, config.tenantDeploymentProperties(), config.centralJwtTrustProperties(), config.centralSigningProperties());
        assertThrows(JwtValidationException.class, () -> missing.decode(signed));
    }

    @Test
    void isolatedLocalDeploymentDeniesUnboundOrOtherAreaTokensAndInvalidConfiguration() throws Exception {
        when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        TenantAuthorizationAttributes binding = new TenantAuthorizationAttributes(
                cn.jia.core.tenant.TenantId.of("1"), cn.jia.core.tenant.DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
        when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(tenantAuthorizationVerifier);
        when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(true);
        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> keys = config.jwkSource(config.centralSigningProperties());
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keys);
        Instant now = Instant.now();
        String signed = token(encoder, now.minusSeconds(5), now.plusSeconds(60), 4, true);
        var properties = config.tenantDeploymentProperties();
        properties.setRequireBoundIdentity(true);
        properties.setAllowedTenants(List.of("1"));
        var trust = config.centralJwtTrustProperties();
        assertThrows(IllegalStateException.class,
                () -> config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties()));
        try (var jwks = new PublicJwkServer(keys)) {
            trust.setIssuerUri(ISSUER);
            trust.setJwkSetUri(jwks.uri());
            JwtDecoder local = config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties());
            assertEquals("1", local.decode(signed).getClaimAsString("tenant_id"));
            assertThrows(JwtValidationException.class,
                    () -> local.decode(token(encoder, now.minusSeconds(5), now.plusSeconds(60), 4)));
            assertThrows(JwtValidationException.class,
                    () -> local.decode(token(encoder, now.minusSeconds(5), now.plusSeconds(60), 4, true, "https://imposter.test")));
            NimbusJwtEncoder unrelatedKey = new NimbusJwtEncoder(config.jwkSource(config.centralSigningProperties()));
            assertThrows(JwtException.class,
                    () -> local.decode(token(unrelatedKey, now.minusSeconds(5), now.plusSeconds(60), 4, true)));
            properties.setAllowedTenants(List.of("0"));
            JwtDecoder wrongArea = config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties());
            assertThrows(JwtValidationException.class, () -> wrongArea.decode(signed));
            properties.setAllowedTenants(List.of("1"));
            trust.setJwkSetUri("http://non-loopback.example.test/jwks");
            assertThrows(IllegalStateException.class,
                    () -> config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties()));
            trust.setJwkSetUri(jwks.uri());
            when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(null);
            assertThrows(IllegalStateException.class,
                    () -> config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties()));
            properties.setAllowedTenants(List.of());
            assertThrows(IllegalStateException.class,
                    () -> config.jwtDecoder(keys, accountSecurityServiceProvider, tenantAuthorizationVerifierProvider, properties, trust, config.centralSigningProperties()));
        }
    }

    @Test
    void fixedTenantUsesCentralTrustAndCurrentAccountWithoutPlatformVerifier() throws Exception {
        when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
        when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(null);
        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> keys = config.jwkSource(config.centralSigningProperties());
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(keys);
        var deployment = config.tenantDeploymentProperties();
        deployment.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        deployment.setFixedTenantId("1");
        var trust = config.centralJwtTrustProperties();

        assertThrows(IllegalStateException.class, () -> config.jwtDecoder(keys,
                accountSecurityServiceProvider, tenantAuthorizationVerifierProvider,
                deployment, trust, config.centralSigningProperties()));
        try (var jwks = new PublicJwkServer(keys)) {
            trust.setIssuerUri(ISSUER);
            trust.setJwkSetUri(jwks.uri());
            JwtDecoder decoder = config.jwtDecoder(keys, accountSecurityServiceProvider,
                    tenantAuthorizationVerifierProvider, deployment, trust,
                    config.centralSigningProperties());
            Instant now = Instant.now();
            assertEquals("1", decoder.decode(fixedTenantToken(encoder, now, "1", false, false))
                    .getClaimAsString("tenant_id"));
            for (String tenant : List.of("0", "01")) {
                assertThrows(JwtValidationException.class,
                        () -> decoder.decode(fixedTenantToken(encoder, now, tenant, false, false)));
            }
            assertThrows(JwtValidationException.class,
                    () -> decoder.decode(fixedTenantToken(encoder, now, null, false, false)));
            assertThrows(JwtValidationException.class,
                    () -> decoder.decode(fixedTenantToken(encoder, now, "1", true, false)));
            assertThrows(JwtValidationException.class,
                    () -> decoder.decode(fixedTenantToken(encoder, now, null, false, true)));
            verifyNoInteractions(tenantAuthorizationVerifier);
        }
    }

    @Test
    void fixedTenantConfigurationRejectsAmbiguousPlatformSwitches() {
        ResourceServerConfig config = new ResourceServerConfig();
        var deployment = config.tenantDeploymentProperties();
        deployment.setAdmissionMode(TenantAdmissionPolicy.Mode.FIXED_TENANT);
        assertThrows(IllegalStateException.class, () -> new cn.jia.oauth.security.TenantDeploymentTokenValidator(deployment));
        deployment.setFixedTenantId("1");
        deployment.setAllowedTenants(List.of("1"));
        assertThrows(IllegalStateException.class, () -> new cn.jia.oauth.security.TenantDeploymentTokenValidator(deployment));
        deployment.setAllowedTenants(List.of());
        deployment.setRequireBoundIdentity(true);
        assertThrows(IllegalStateException.class, () -> new cn.jia.oauth.security.TenantDeploymentTokenValidator(deployment));
    }

    @Test
    void centralSigningKeySurvivesRestartAndOnlyItsPublicJwksVerifies(@TempDir Path directory) throws Exception {
        ResourceServerConfig config = new ResourceServerConfig();
        JWKSource<SecurityContext> generated = config.jwkSource(config.centralSigningProperties());
        RSAKey privateKey = (RSAKey) generated.get(new JWKSelector(
                new JWKMatcher.Builder().keyType(KeyType.RSA).build()), null).getFirst();
        Path file = directory.resolve("signing.jwk");
        Files.writeString(file, privateKey.toJSONString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        var signing = config.centralSigningProperties();
        signing.setEnabled(true);
        signing.setIssuer(ISSUER);
        signing.setPrivateJwkPath(file.toString());
        JWKSource<SecurityContext> first = config.jwkSource(signing);
        JWKSource<SecurityContext> restarted = new ResourceServerConfig().jwkSource(signing);
        var matcher = new JWKMatcher.Builder().keyType(KeyType.RSA).build();
        assertEquals(first.get(new JWKSelector(matcher), null), restarted.get(new JWKSelector(matcher), null));
        assertNotEquals(privateKey.toJSONString(), privateKey.toPublicJWK().toJSONString());
        try (var jwks = new PublicJwkServer(restarted)) {
            when(accountSecurityServiceProvider.getIfAvailable()).thenReturn(accountSecurityService);
            when(accountSecurityService.findByUserId(17)).thenReturn(Optional.of(
                    new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
            when(tenantAuthorizationVerifierProvider.getIfAvailable()).thenReturn(tenantAuthorizationVerifier);
            TenantAuthorizationAttributes binding = new TenantAuthorizationAttributes(
                    cn.jia.core.tenant.TenantId.of("1"), cn.jia.core.tenant.DataDomainId.of("cyf"), "华南测试区",
                    TenantAuthorizationAttributes.TenantKind.TEST, "2", "3", "4");
            when(tenantAuthorizationVerifier.isCurrent(binding, 17, "web")).thenReturn(true);
            var deployment = config.tenantDeploymentProperties();
            deployment.setRequireBoundIdentity(true);
            deployment.setAllowedTenants(List.of("1"));
            var trust = config.centralJwtTrustProperties();
            trust.setIssuerUri(ISSUER);
            trust.setJwkSetUri(jwks.uri());
            JwtDecoder local = config.jwtDecoder(restarted, accountSecurityServiceProvider,
                    tenantAuthorizationVerifierProvider, deployment, trust, signing);
            String signed = token(new NimbusJwtEncoder(first), Instant.now().minusSeconds(5),
                    Instant.now().plusSeconds(60), 4, true);
            assertEquals("1", local.decode(signed).getClaimAsString("tenant_id"));
            JwtDecoder central = config.jwtDecoder(first, accountSecurityServiceProvider,
                    tenantAuthorizationVerifierProvider, config.tenantDeploymentProperties(),
                    config.centralJwtTrustProperties(), signing);
            assertThrows(JwtValidationException.class, () -> central.decode(token(new NimbusJwtEncoder(first),
                    Instant.now().minusSeconds(5), Instant.now().plusSeconds(60), 4, true, "https://imposter.test")));
        }
        signing.setPrivateJwkPath(directory.resolve("missing.jwk").toString());
        assertThrows(IllegalStateException.class, () -> config.jwkSource(signing));
        signing.setPrivateJwkPath(file.toString());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalStateException.class, () -> config.jwkSource(signing));
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        signing.setPrivateJwkPath(file.toString());
        signing.setIssuer("http://external.example.com");
        assertThrows(IllegalStateException.class, () -> config.jwkSource(signing));
    }


    private static String token(NimbusJwtEncoder encoder, Instant issuedAt, Instant expiresAt, long epoch) {
        return token(encoder, issuedAt, expiresAt, epoch, false);
    }

    private static String token(NimbusJwtEncoder encoder, Instant issuedAt, Instant expiresAt, long epoch, boolean bound) {
        return token(encoder, issuedAt, expiresAt, epoch, bound, ISSUER);
    }

    private static String token(NimbusJwtEncoder encoder, Instant issuedAt, Instant expiresAt, long epoch,
                                boolean bound, String issuer) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject("alice")
                .claim("client_id", "web")
                .claim("token_kind", "user")
                .claim("uid", "17")
                .claim("jiacn", "Jia-A")
                .claim("username", "alice")
                .claim("auth_epoch", epoch);
        if (bound) {
            claims.claim("tenant_id", "1")
                    .claim("data_domain_id", "cyf")
                    .claim("tenant_name", "华南测试区")
                    .claim("tenant_kind", "TEST")
                    .claim("tenant_session_epoch", "2")
                    .claim("membership_revision", "3")
                    .claim("client_tenant_grant_revision", "4");
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static String fixedTenantToken(NimbusJwtEncoder encoder, Instant now, String tenantId,
                                           boolean partialPlatformBundle, boolean machine) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(60));
        if (machine) {
            claims.subject("agent-client")
                    .claim("client_id", "agent-client")
                    .claim("token_kind", "machine");
        } else {
            claims.subject("alice")
                    .claim("client_id", "web")
                    .claim("token_kind", "user")
                    .claim("uid", "17")
                    .claim("jiacn", "Jia-A")
                    .claim("username", "alice")
                    .claim("auth_epoch", 4L);
        }
        if (tenantId != null) claims.claim("tenant_id", tenantId);
        if (partialPlatformBundle) claims.claim("tenant_name", "华南测试区");
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
    private static final class PublicJwkServer implements AutoCloseable {
        private final HttpServer server;

        PublicJwkServer(JWKSource<SecurityContext> keys) throws Exception {
            var matcher = new JWKMatcher.Builder().keyType(KeyType.RSA).build();
            String publicKeys = new JWKSet(keys.get(new JWKSelector(matcher), null))
                    .toPublicJWKSet().toString();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = publicKeys.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
        }

        String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        @Override public void close() { server.stop(0); }
    }
}
