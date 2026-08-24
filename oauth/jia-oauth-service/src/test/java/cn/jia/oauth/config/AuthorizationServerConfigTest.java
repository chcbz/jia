package cn.jia.oauth.config;

import cn.jia.oauth.security.AccountSecurityTokenCustomizer;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServerConfigTest extends BaseMockTest {
    @Mock AccountSecurityService accountSecurityService;

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
        JwtEncodingContext valid = encodingContext(userAuthentication, AuthorizationGrantType.AUTHORIZATION_CODE);
        new AccountSecurityTokenCustomizer(accountSecurityService).customize(valid);
        Map<String, Object> claims = valid.getClaims().build().getClaims();
        assertEquals("user", claims.get("token_kind"));
        assertEquals("17", claims.get("uid"));
        assertEquals("Jia-A", claims.get("jiacn"));
        assertEquals(4L, claims.get("auth_epoch"));

        when(accountSecurityService.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 5)));
        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> new AccountSecurityTokenCustomizer(accountSecurityService)
                        .customize(encodingContext(userAuthentication, AuthorizationGrantType.REFRESH_TOKEN)));
        assertEquals("invalid_grant", failure.getError().getErrorCode());
    }

    @Test
    void clientCredentialsTokenIsExplicitMachineAndCarriesNoUserClaims() {
        JwtEncodingContext context = encodingContext(clientPrincipal(), AuthorizationGrantType.CLIENT_CREDENTIALS);
        context.getClaims().claim("uid", "17").claim("jiacn", "stale").claim("username", "stale")
                .claim("auth_epoch", 3L);
        new AccountSecurityTokenCustomizer(accountSecurityService).customize(context);
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
        JwtEncodingContext.Builder builder = JwtEncodingContext.with(
                JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject(principal.getName()));
        builder.registeredClient(client);
        builder.principal(principal);
        builder.authorizationServerContext(AuthorizationServerContextHolder.getContext());
        builder.tokenType(OAuth2TokenType.ACCESS_TOKEN);
        builder.authorizationGrantType(grantType);
        return builder.build();
    }
}
