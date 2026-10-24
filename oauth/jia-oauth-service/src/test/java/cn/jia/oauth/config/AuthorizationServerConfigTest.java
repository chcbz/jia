package cn.jia.oauth.config;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.oauth.security.CentralSigningProperties;
import cn.jia.oauth.security.AccountSecurityTokenCustomizer;
import cn.jia.oauth.security.JdbcTenantAuthorizationRepository;
import cn.jia.oauth.security.TenantAuthorizationAttributes;
import cn.jia.oauth.security.FixedTenantAuthorizationPolicy;
import cn.jia.oauth.security.TenantIssuanceProperties;
import cn.jia.test.BaseMockTest;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServerConfigTest extends BaseMockTest {
    @Mock AccountSecurityService accountSecurityService;
    @Mock JdbcTenantAuthorizationRepository tenantAuthorizationRepository;

    private RegisteredClient client;
    private CustomUserDetails userDetails;
    private Authentication userAuthentication;

    @BeforeEach
    void setUp() {
        client = RegisteredClient.withId("client-row")
                .clientId("web-client")
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://client.example/callback")
                .scope("profile")
                .build();
        userDetails = new CustomUserDetails(17, "Jia-A", 4, "alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        userAuthentication = new UsernamePasswordAuthenticationToken(
                userDetails, "n/a", userDetails.getAuthorities());
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://issuer.example").build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
    }

    @AfterEach
    void tearDown() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void centralTenantIssuerMustBeFixedAndSigningExplicitlyEnabled() {
        var config = new AuthorizationServerConfig();
        var signing = new CentralSigningProperties();
        assertThrows(IllegalStateException.class,
                () -> config.authorizationServerSettings(signing, true, true));
        assertEquals(null, config.authorizationServerSettings(signing, false, true).getIssuer());
        signing.setEnabled(true);
        assertThrows(IllegalStateException.class,
                () -> config.authorizationServerSettings(signing, true, true));
        signing.setIssuer("http://external.example.test");
        assertThrows(IllegalStateException.class,
                () -> config.authorizationServerSettings(signing, true, true));
        signing.setIssuer("https://issuer.example");
        assertEquals("https://issuer.example",
                config.authorizationServerSettings(signing, true, true).getIssuer());
        assertEquals("https://issuer.example",
                config.authorizationServerSettings(signing, true, false).getIssuer());
    }

    @Test
    void fixedTenantIssuanceRequiresExactClientMappingAndCentralSigning() {
        TenantIssuanceProperties properties = new TenantIssuanceProperties();
        properties.setAdmissionMode(TenantIssuanceProperties.Mode.FIXED_TENANT);
        properties.setFixedTenantId("1");
        assertFalse(FixedTenantAuthorizationPolicy.from(properties, false).isFixedTenant());
        assertThrows(IllegalStateException.class, () -> FixedTenantAuthorizationPolicy.from(properties));
        properties.setFixedClientTenants(Map.of("web-client", "0"));
        assertThrows(IllegalStateException.class, () -> FixedTenantAuthorizationPolicy.from(properties));
        properties.setFixedClientTenants(Map.of("web-client", "1"));
        FixedTenantAuthorizationPolicy policy = FixedTenantAuthorizationPolicy.from(properties);
        assertEquals("1", policy.bind("web-client").tenantId().value());
        assertThrows(OAuth2AuthenticationException.class, () -> policy.bind("other-client"));
        var signing = new CentralSigningProperties();
        assertThrows(IllegalStateException.class,
                () -> new AuthorizationServerConfig().authorizationServerSettings(signing, false, true, policy));
        properties.setRequireBoundIdentity(true);
        assertThrows(IllegalStateException.class, () -> FixedTenantAuthorizationPolicy.from(properties));
        properties.setRequireBoundIdentity(false);
        properties.setAllowedTenants(List.of("1"));
        assertThrows(IllegalStateException.class, () -> FixedTenantAuthorizationPolicy.from(properties));
    }

    @Test
    void realAuthorizationCodeGrantRestoresOriginalUserPrincipalForTokenGeneration() {
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.example/oauth2/authorize")
                .clientId(client.getClientId())
                .redirectUri("https://client.example/callback")
                .scope("profile")
                .state("state")
                .build();
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(
                "code", Instant.now().minusSeconds(5), Instant.now().plusSeconds(300));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(userDetails.getUsername())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), userAuthentication)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .token(code)
                .build();
        InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService(authorization);
        AtomicReference<Authentication> accessPrincipal = new AtomicReference<>();
        OAuth2AuthorizationCodeAuthenticationProvider provider = new OAuth2AuthorizationCodeAuthenticationProvider(
                authorizations, generator(accessPrincipal));

        Authentication result = provider.authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                "code", clientPrincipal(), "https://client.example/callback", Map.of()));

        assertInstanceOf(OAuth2AccessTokenAuthenticationToken.class, result);
        assertNotNull(accessPrincipal.get());
        assertSame(userDetails, accessPrincipal.get().getPrincipal());
    }

    @Test
    void realRefreshGrantRestoresOriginalUserPrincipalForTokenGeneration() {
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(
                "refresh", Instant.now().minusSeconds(5), Instant.now().plusSeconds(600));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName(userDetails.getUsername())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), userAuthentication)
                .refreshToken(refreshToken)
                .build();
        InMemoryOAuth2AuthorizationService authorizations = new InMemoryOAuth2AuthorizationService(authorization);
        AtomicReference<Authentication> accessPrincipal = new AtomicReference<>();
        OAuth2RefreshTokenAuthenticationProvider provider = new OAuth2RefreshTokenAuthenticationProvider(
                authorizations, generator(accessPrincipal));

        Authentication result = provider.authenticate(new OAuth2RefreshTokenAuthenticationToken(
                "refresh", clientPrincipal(), Set.of(), Map.of()));

        assertInstanceOf(OAuth2AccessTokenAuthenticationToken.class, result);
        assertNotNull(accessPrincipal.get());
        assertSame(userDetails, accessPrincipal.get().getPrincipal());
    }

    @Test
    void userClaimsUseFrozenEpochAndStalePrincipalCannotUpgradeItself() {
        when(accountSecurityService.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(encodingContext(userAuthentication, AuthorizationGrantType.AUTHORIZATION_CODE)))
                .getError().getErrorCode());
        OAuth2Authorization unselected = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build();
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(encodingContext(userAuthentication, AuthorizationGrantType.REFRESH_TOKEN, unselected)))
                .getError().getErrorCode());
        TenantAuthorizationAttributes selected = new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "3", "8", "4");
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(TenantAuthorizationAttributes.attributeName(), selected).build();
        when(tenantAuthorizationRepository.isEligibleForIssuance(selected, 17, "web-client")).thenReturn(true);
        JwtEncodingContext valid = encodingContext(userAuthentication, AuthorizationGrantType.AUTHORIZATION_CODE, authorization);
        new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository).customize(valid);
        Map<String, Object> claims = valid.getClaims().build().getClaims();
        assertEquals("user", claims.get("token_kind"));
        assertEquals("17", claims.get("uid"));
        assertEquals("Jia-A", claims.get("jiacn"));
        assertEquals(4L, claims.get("auth_epoch"));
        assertEquals("1", claims.get("tenant_id"));
        assertEquals("1", claims.get("tenant_claim_version"));

        when(accountSecurityService.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 5)));
        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(encodingContext(userAuthentication, AuthorizationGrantType.REFRESH_TOKEN, authorization)));
        assertEquals("invalid_grant", failure.getError().getErrorCode());
    }

    @Test
    void tenantClaimsComeOnlyFromBoundAuthorizationAttributes() {
        TenantAuthorizationAttributes selected = new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "3", "8", "4");
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(TenantAuthorizationAttributes.attributeName(), selected)
                .build();
        JwtEncodingContext.Builder builder = JwtEncodingContext.with(
                JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("alice"));
        builder.registeredClient(client);
        builder.principal(userAuthentication);
        builder.authorization(authorization);
        builder.tokenType(OAuth2TokenType.ACCESS_TOKEN);
        builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);
        JwtEncodingContext context = builder.build();
        when(accountSecurityService.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        when(tenantAuthorizationRepository.isEligibleForIssuance(selected, 17, "web-client")).thenReturn(true);
        new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository).customize(context);
        Map<String, Object> claims = context.getClaims().build().getClaims();
        assertEquals("1", claims.get("tenant_id"));
        assertEquals("cyf", claims.get("data_domain_id"));
        assertEquals("华南测试区", claims.get("tenant_name"));
        assertEquals("TEST", claims.get("tenant_kind"));
        assertEquals("3", claims.get("tenant_session_epoch"));
        assertEquals("8", claims.get("membership_revision"));
        assertEquals("4", claims.get("client_tenant_grant_revision"));
        JwtEncodingContext.Builder refreshBuilder = JwtEncodingContext.with(
                JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("alice"));
        refreshBuilder.registeredClient(client);
        refreshBuilder.principal(userAuthentication);
        refreshBuilder.authorization(authorization);
        refreshBuilder.tokenType(OAuth2TokenType.ACCESS_TOKEN);
        refreshBuilder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
        JwtEncodingContext refreshed = refreshBuilder.build();
        new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository).customize(refreshed);
        assertEquals("1", refreshed.getClaims().build().getClaim("tenant_id"));
        when(tenantAuthorizationRepository.isEligibleForIssuance(selected, 17, "web-client")).thenReturn(false);
        OAuth2AuthenticationException revoked = assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository).customize(context));
        assertEquals("invalid_grant", revoked.getError().getErrorCode());
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(refreshed)).getError().getErrorCode());
        when(tenantAuthorizationRepository.isEligibleForIssuance(selected, 17, "web-client")).thenReturn(false);
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(context)).getError().getErrorCode());
        when(tenantAuthorizationRepository.isEligibleForIssuance(selected, 17, "web-client"))
                .thenThrow(new IllegalStateException("control data unavailable"));
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository)
                        .customize(context)).getError().getErrorCode());
        assertThrows(NullPointerException.class, () -> new TenantAuthorizationAttributes(
                TenantId.of("1"), null, "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "3", "8", "4"));
        assertThrows(IllegalArgumentException.class, () -> new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "03", "8", "4"));
        assertThrows(IllegalArgumentException.class, () -> new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "9223372036854775808", "8", "4"));
    }

    @Test
    void clientCredentialsTokenIsExplicitMachineAndCarriesNoUserClaims() {
        JwtEncodingContext context = encodingContext(clientPrincipal(), AuthorizationGrantType.CLIENT_CREDENTIALS);
        context.getClaims().claim("uid", "17").claim("jiacn", "stale").claim("username", "stale")
                .claim("auth_epoch", 3L);
        new AccountSecurityTokenCustomizer(accountSecurityService, tenantAuthorizationRepository).customize(context);
        Map<String, Object> claims = context.getClaims().build().getClaims();
        assertEquals("machine", claims.get("token_kind"));
        assertFalse(claims.containsKey("uid"));
        assertFalse(claims.containsKey("jiacn"));
        assertFalse(claims.containsKey("username"));
        assertFalse(claims.containsKey("auth_epoch"));
        verifyNoInteractions(accountSecurityService);
    }

    private OAuth2TokenGenerator<OAuth2Token> generator(AtomicReference<Authentication> accessPrincipal) {
        return (OAuth2TokenContext context) -> {
            Instant now = Instant.now();
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                accessPrincipal.set(context.getPrincipal());
                return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        "access", now, now.plusSeconds(300), context.getAuthorizedScopes());
            }
            if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                return new OAuth2RefreshToken("new-refresh", now, now.plusSeconds(600));
            }
            return null;
        };
    }

    private OAuth2ClientAuthenticationToken clientPrincipal() {
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
    }

    private JwtEncodingContext encodingContext(Authentication principal, AuthorizationGrantType grantType) {
        return encodingContext(principal, grantType, null);
    }

    private JwtEncodingContext encodingContext(Authentication principal, AuthorizationGrantType grantType,
                                               OAuth2Authorization authorization) {
        JwtEncodingContext.Builder builder = JwtEncodingContext.with(
                JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject(principal.getName()));
        builder.registeredClient(client);
        builder.principal(principal);
        builder.authorizationServerContext(AuthorizationServerContextHolder.getContext());
        builder.tokenType(OAuth2TokenType.ACCESS_TOKEN);
        builder.authorizationGrantType(grantType);
        if (authorization != null) builder.authorization(authorization);
        return builder.build();
    }
}
