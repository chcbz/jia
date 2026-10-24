package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.oauth.config.AuthorizationServerConfig;
import cn.jia.user.entity.CustomUserDetails;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.PublicClientAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DataAccessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.lang.reflect.Modifier;
import java.util.UUID;
import java.security.MessageDigest;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
class JdbcTenantAuthorizationRepositoryTest {
    private JdbcTemplate jdbc;

    @Test
    void repositoryMustRemainProxyableForSpringPersistenceExceptionTranslation() {
        assertFalse(Modifier.isFinal(JdbcTenantAuthorizationRepository.class.getModifiers()));
    }
    private JdbcTenantAuthorizationRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:h2:mem:tenant_" + UUID.randomUUID() + ";MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcTenantAuthorizationRepository(jdbc);
        jdbc.execute("CREATE TABLE core_tenant (tenant_id VARCHAR(50) PRIMARY KEY, tenant_name VARCHAR(128), tenant_kind VARCHAR(6))");
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, state VARCHAR(32), tenant_session_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_membership (tenant_id VARCHAR(50), uid BIGINT, status VARCHAR(8), membership_revision BIGINT)");
        jdbc.execute("CREATE TABLE oauth_client_tenant_grant (tenant_id VARCHAR(50), client_id VARCHAR(100), status VARCHAR(8), grant_revision BIGINT)");
        jdbc.execute("CREATE TABLE oauth_client_tenant_domain_grant (tenant_id VARCHAR(50), client_id VARCHAR(100), data_domain_id VARCHAR(64), status VARCHAR(8))");
        jdbc.execute("CREATE TABLE oauth_client (id VARCHAR(100) PRIMARY KEY, client_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE user_info (id BIGINT PRIMARY KEY, jiacn VARCHAR(128), "
                + "account_state VARCHAR(20), auth_epoch BIGINT)");
        jdbc.update("INSERT INTO user_info VALUES (17, 'Jia-A', 'ACTIVE', 4)");
        jdbc.update("INSERT INTO oauth_client VALUES ('registered-web', 'web')");
    }

    @AfterEach
    void resetAuthorizationContext() {
        AuthorizationServerContextHolder.resetContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requiresTheExactRegisteredClientMemberAreaAndDataDomain() {
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
        seed("0", "NORMAL", "ENABLED");
        assertTrue(repository.findEligibleSelection(TenantId.of("0"), DataDomainId.of("cyf"), 17, "web").isPresent());
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
        seed("1", "TEST", "ENABLED");
        var selected = repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web");
        assertTrue(selected.isPresent());
        assertEquals("1", selected.get().tenantId().value());
        assertEquals("TEST", selected.get().tenantKind().name());
        assertEquals("3", selected.get().tenantSessionEpoch());
        assertEquals("8", selected.get().membershipRevision());
        assertEquals("4", selected.get().clientTenantGrantRevision());
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 18, "web").isPresent());
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "mobile").isPresent());
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("other"), 17, "web").isPresent());
    }

    @Test
    void closedRegionOrRevokedMembershipClientOrDomainGrantFailsClosed() {
        seed("1", "TEST", "DISABLED");
        TenantId tenant = TenantId.of("1");
        DataDomainId domain = DataDomainId.of("cyf");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE core_tenant_control SET state='ENABLED' WHERE tenant_id='1'");
        assertTrue(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE core_tenant_membership SET status='REVOKED' WHERE tenant_id='1'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE core_tenant_membership SET status='ACTIVE' WHERE tenant_id='1'");
        jdbc.update("UPDATE oauth_client_tenant_grant SET status='REVOKED' WHERE tenant_id='1'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE oauth_client_tenant_grant SET status='ACTIVE' WHERE tenant_id='1'");
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE tenant_id='1'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status='ACTIVE' WHERE tenant_id='1'");
        jdbc.update("DELETE FROM core_tenant_control WHERE tenant_id='1'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
    }

    @Test
    void unknownOrDuplicateRegisteredClientCannotBorrowAnExistingGrant() {
        seed("1", "TEST", "ENABLED");
        jdbc.update("DELETE FROM oauth_client WHERE id='registered-web'");
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
        jdbc.update("INSERT INTO oauth_client VALUES ('other-client', 'WEB')");
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
        jdbc.update("DELETE FROM oauth_client WHERE id='other-client'");
        jdbc.update("INSERT INTO oauth_client VALUES ('registered-web', 'web')");
        assertTrue(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
        jdbc.update("INSERT INTO oauth_client VALUES ('duplicate-web', 'web')");
        assertFalse(repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").isPresent());
    }

    @Test
    void missingControlSchemaAndInvalidInputCannotBecomePermissions() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 0, "web"));
        jdbc.execute("DROP TABLE core_tenant_control");
        assertThrows(DataAccessException.class,
                () -> repository.findEligibleSelection(TenantId.of("1"), DataDomainId.of("cyf"), 17, "web"));
    }


    @Test
    void caseInsensitiveSqlMatchesAreNotExactAuthorizationKeys() {
        seed("1", "TEST", "ENABLED");
        TenantId tenant = TenantId.of("1");
        DataDomainId domain = DataDomainId.of("cyf");
        assertTrue(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE oauth_client_tenant_grant SET client_id='WEB'");
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='WEB'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE oauth_client_tenant_grant SET client_id='web'");
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='web', data_domain_id='CYF'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET data_domain_id='cyf'");
        jdbc.update("UPDATE core_tenant_control SET state='enabled'");
        assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        jdbc.update("UPDATE core_tenant_control SET state='ENABLED', tenant_session_epoch=NULL");
        assertThrows(IllegalStateException.class,
                () -> repository.findEligibleSelection(tenant, domain, 17, "web"));
    }

    @Test
    void existingTokensKeepReadEligibilityWhileNewSelectionIsClosed() {
        seed("1", "TEST", "ENABLED");
        TenantId tenant = TenantId.of("1");
        DataDomainId domain = DataDomainId.of("cyf");
        TenantAuthorizationAttributes bound = repository.findEligibleSelection(tenant, domain, 17, "web").orElseThrow();
        for (String state : List.of("AUTH_CLOSED", "WRITE_CLOSED_CLAIM_CLOSED")) {
            jdbc.update("UPDATE core_tenant_control SET state=? WHERE tenant_id='1'", state);
            assertFalse(repository.findEligibleSelection(tenant, domain, 17, "web").isPresent());
            assertTrue(repository.listEligibleSelections(domain, 17, "web").isEmpty());
            assertFalse(repository.isEligibleForIssuance(bound, 17, "web"));
            assertTrue(repository.isCurrent(bound, 17, "web"));
        }
        jdbc.update("UPDATE core_tenant_control SET state='DRAINING' WHERE tenant_id='1'");
        assertFalse(repository.isCurrent(bound, 17, "web"));
    }

    @Test
    void frozenTenantTokenBindingIsDeniedAfterMembershipOrSessionRevisionChanges() {
        seed("1", "TEST", "ENABLED");
        TenantAuthorizationAttributes bound = repository.findEligibleSelection(
                TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").orElseThrow();
        assertTrue(repository.isCurrent(bound, 17, "web"));
        jdbc.update("UPDATE core_tenant_membership SET membership_revision=9 WHERE tenant_id='1'");
        assertFalse(repository.isCurrent(bound, 17, "web"));
        jdbc.update("UPDATE core_tenant_membership SET membership_revision=8 WHERE tenant_id='1'");
        jdbc.update("UPDATE core_tenant_control SET tenant_session_epoch=4 WHERE tenant_id='1'");
        assertFalse(repository.isCurrent(bound, 17, "web"));
    }

    @Test
    void authorizationCodeAndRefreshKeepTenantBindingWithoutRetainingPassword() throws Exception {
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://web.example/callback").scope("profile").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        CustomUserDetails login = new CustomUserDetails(17, "Jia-A", 4, "alice", "sensitive-login-password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        seed("1", "TEST", "ENABLED");
        TenantAuthorizationAttributes selected = repository.findEligibleSelection(
                TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").orElseThrow();
        var unexpected = new org.springframework.security.core.userdetails.User(
                "alice", "must-not-be-persisted", AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2Authorization unsafe = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        unexpected, null, unexpected.getAuthorities()))
                .attribute(TenantAuthorizationAttributes.attributeName(), selected)
                .token(new OAuth2AuthorizationCode("unsafe-code", Instant.now(), Instant.now().plusSeconds(300)))
                .build();
        assertThrows(IllegalArgumentException.class, () -> service.save(unsafe));
        assertNull(service.findById(unsafe.getId()));
        OAuth2AuthorizationCode code = new OAuth2AuthorizationCode("code-17",
                Instant.now().minusSeconds(1), Instant.now().plusSeconds(300));
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.example/oauth2/authorize")
                .clientId("web").redirectUri("https://web.example/callback")
                .scope("profile").state("state-17")
                .additionalParameters(params -> {
                    params.put("code_challenge", challenge);
                    params.put("code_challenge_method", "S256");
                }).build();
        OAuth2Authorization initial = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        login, null, login.getAuthorities()))
                .attribute(TenantAuthorizationAttributes.attributeName(), selected)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .token(code).build();
        assertThrows(IllegalArgumentException.class, () -> service.save(OAuth2Authorization.from(initial)
                .attributes(attrs -> attrs.remove(TenantAuthorizationAttributes.attributeName())).build()));
        assertNull(service.findByToken("code-17", new OAuth2TokenType("code")));
        service.save(initial);
        OAuth2Authorization persistedInitial = service.findById(initial.getId());
        TenantAuthorizationAttributes another = new TenantAuthorizationAttributes(
                TenantId.of("2"), DataDomainId.of("cyf"), "other region",
                TenantAuthorizationAttributes.TenantKind.NORMAL, "3", "8", "4");
        assertThrows(IllegalArgumentException.class, () -> service.save(OAuth2Authorization.from(persistedInitial)
                .attribute(TenantAuthorizationAttributes.attributeName(), another).build()));
        assertThrows(IllegalArgumentException.class, () -> service.save(OAuth2Authorization.from(persistedInitial)
                .attributes(attrs -> {
                    attrs.remove(TenantAuthorizationAttributes.attributeName());
                    attrs.remove("cyf.oauth.tenantVersion");
                }).build()));
        assertEquals(selected, service.findById(initial.getId())
                .getAttribute(TenantAuthorizationAttributes.attributeName()));
        OAuth2Authorization unbound = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        login, null, login.getAuthorities()))
                .token(new OAuth2AuthorizationCode("unbound-17", Instant.now(), Instant.now().plusSeconds(300)))
                .build();
        assertThrows(IllegalArgumentException.class, () -> service.save(unbound));
        assertNull(service.findById(unbound.getId()));
        OAuth2Authorization fromCode = service.findByToken("code-17", new OAuth2TokenType("code"));
        assertEquals(selected, fromCode.getAttribute(TenantAuthorizationAttributes.attributeName()));
        assertEquals(17, ((CustomUserDetails) ((UsernamePasswordAuthenticationToken)
                fromCode.getAttribute(Principal.class.getName())).getPrincipal()).getUserId());
        OAuth2AuthorizationRequest reloaded = fromCode.getAttribute(OAuth2AuthorizationRequest.class.getName());
        assertEquals(challenge, reloaded.getAdditionalParameters().get("code_challenge"));
        assertEquals("S256", reloaded.getAdditionalParameters().get("code_challenge_method"));
        assertEquals(4, ((CustomUserDetails) ((UsernamePasswordAuthenticationToken)
                fromCode.getAttribute(Principal.class.getName())).getPrincipal()).getLoginAuthEpoch());
        AccountSecurityService accounts = mock(AccountSecurityService.class);
        when(accounts.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        JwtEncodingContext context = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("alice"))
                .registeredClient(client)
                .principal(fromCode.getAttribute(Principal.class.getName()))
                .authorization(fromCode).tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).build();
        new AccountSecurityTokenCustomizer(accounts, repository).customize(context);
        assertEquals("1", context.getClaims().build().getClaim("tenant_id"));
        assertEquals("17", context.getClaims().build().getClaim("uid"));

        Authentication storedLogin = fromCode.getAttribute(Principal.class.getName());
        assertNull(storedLogin.getCredentials());
        assertEquals("<unavailable>", ((CustomUserDetails) storedLogin.getPrincipal()).getPassword());
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://issuer.example").build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });

        OAuth2AuthorizationCodeAuthenticationProvider provider = new OAuth2AuthorizationCodeAuthenticationProvider(
                service, tokenContext -> {
                    Instant issued = Instant.now();
                    if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenContext.getTokenType())) {
                        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-17",
                                issued, issued.plusSeconds(300), tokenContext.getAuthorizedScopes());
                    }
                    if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenContext.getTokenType())) {
                        return new OAuth2RefreshToken("new-refresh-17", issued, issued.plusSeconds(600));
                    }
                    return null;
                });
        PublicClientAuthenticationProvider publicClient = new PublicClientAuthenticationProvider(
                new InMemoryRegisteredClientRepository(client), service);
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> publicClient.authenticate(new OAuth2ClientAuthenticationToken(
                        "web", ClientAuthenticationMethod.NONE, null,
                        Map.of("grant_type", "authorization_code", "code", "code-17", "code_verifier", verifier + "X"))))
                .getError().getErrorCode());
        OAuth2ClientAuthenticationToken clientPrincipal = (OAuth2ClientAuthenticationToken)
                publicClient.authenticate(new OAuth2ClientAuthenticationToken(
                        "web", ClientAuthenticationMethod.NONE, null,
                        Map.of("grant_type", "authorization_code", "code", "code-17", "code_verifier", verifier)));


        OAuth2AccessTokenAuthenticationToken result = (OAuth2AccessTokenAuthenticationToken)
                new TenantTokenGrantProvider(provider, service, repository, jdbc).authenticate(
                new OAuth2AuthorizationCodeAuthenticationToken("code-17", clientPrincipal,
                        "https://web.example/callback", Map.of("code_verifier", verifier)));
        assertEquals("access-17", result.getAccessToken().getTokenValue());
        OAuth2Authorization exchanged = service.findByToken("access-17", OAuth2TokenType.ACCESS_TOKEN);
        assertEquals(selected, exchanged.getAttribute(TenantAuthorizationAttributes.attributeName()));
        assertEquals(17, ((CustomUserDetails) ((UsernamePasswordAuthenticationToken)
                exchanged.getAttribute(Principal.class.getName())).getPrincipal()).getUserId());
        OAuth2Authorization fromRefresh = service.findByToken(
                result.getRefreshToken().getTokenValue(), OAuth2TokenType.REFRESH_TOKEN);
        assertEquals(selected, fromRefresh.getAttribute(TenantAuthorizationAttributes.attributeName()));
        assertTrue(fromRefresh.getRefreshToken().isActive());
        OAuth2RefreshTokenAuthenticationProvider refresher = new OAuth2RefreshTokenAuthenticationProvider(
                service, tokenContext -> {
                    Instant issued = Instant.now();
                    if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenContext.getTokenType())) {
                        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-18",
                                issued, issued.plusSeconds(300), tokenContext.getAuthorizedScopes());
                    }
                    return null;
                });
        OAuth2AccessTokenAuthenticationToken refreshed = (OAuth2AccessTokenAuthenticationToken)
                new TenantTokenGrantProvider(refresher, service, repository, jdbc).authenticate(
                new OAuth2RefreshTokenAuthenticationToken(exchanged.getRefreshToken().getToken().getTokenValue(),
                        clientPrincipal, Set.of(), Map.of()));
        assertEquals("access-18", refreshed.getAccessToken().getTokenValue());
        for (String closed : List.of("AUTH_CLOSED", "WRITE_CLOSED_CLAIM_CLOSED")) {
            jdbc.update("UPDATE core_tenant_control SET state=? WHERE tenant_id='1'", closed);
            assertTrue(repository.isCurrent(selected, 17, "web"));
            assertFalse(repository.isEligibleForIssuance(selected, 17, "web"));
            assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                    () -> new TenantTokenGrantProvider(refresher, service, repository, jdbc)
                            .authenticate(new OAuth2RefreshTokenAuthenticationToken(
                                    result.getRefreshToken().getTokenValue(), clientPrincipal, Set.of(), Map.of())))
                    .getError().getErrorCode());
            assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                    () -> new AccountSecurityTokenCustomizer(accounts, repository).customize(context))
                    .getError().getErrorCode());
        }
        jdbc.update("UPDATE core_tenant_control SET state='ENABLED' WHERE tenant_id='1'");
        assertEquals(selected, service.findByToken("access-18", OAuth2TokenType.ACCESS_TOKEN)
                .getAttribute(TenantAuthorizationAttributes.attributeName()));
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new TenantTokenGrantProvider(provider, service, repository, jdbc)
                        .authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                                "code-17", clientPrincipal, "https://other.example/callback",
                                Map.of("code_verifier", verifier)))).getError().getErrorCode());
        assertTrue(service.findByToken(result.getRefreshToken().getTokenValue(),
                OAuth2TokenType.REFRESH_TOKEN).getRefreshToken().isActive(),
                "A request with the wrong redirect URI must not revoke a client's refresh token");
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new TenantTokenGrantProvider(provider, service, repository, jdbc)
                        .authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                        "code-17", clientPrincipal, "https://web.example/callback",
                        Map.of("code_verifier", verifier)))).getError().getErrorCode());
        assertFalse(service.findByToken(result.getRefreshToken().getTokenValue(),
                OAuth2TokenType.REFRESH_TOKEN).getRefreshToken().isActive(),
                "A replayed code must persist Spring's refresh-token revocation before invalid_grant escapes");
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new TenantTokenGrantProvider(refresher, service, repository, jdbc)
                        .authenticate(new OAuth2RefreshTokenAuthenticationToken(
                                result.getRefreshToken().getTokenValue(), clientPrincipal, Set.of(), Map.of())))
                .getError().getErrorCode());

    }

    @Test
    void simultaneousSelectionCannotReplaceCommittedTenant() throws Exception {
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://web.example/callback").scope("profile").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        CustomUserDetails login = new CustomUserDetails(17, "Jia-A", 4,
                "alice", "not-persisted", AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2Authorization stage = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        login, null, login.getAuthorities())).build();
        service.save(stage);
        OAuth2Authorization beforeA = service.findById(stage.getId());
        OAuth2Authorization beforeB = service.findById(stage.getId());
        TenantAuthorizationAttributes areaOne = new TenantAuthorizationAttributes(
                TenantId.of("1"), DataDomainId.of("cyf"), "华南测试区",
                TenantAuthorizationAttributes.TenantKind.TEST, "3", "8", "4");
        TenantAuthorizationAttributes areaTwo = new TenantAuthorizationAttributes(
                TenantId.of("2"), DataDomainId.of("cyf"), "华北区",
                TenantAuthorizationAttributes.TenantKind.NORMAL, "3", "8", "4");
        CyclicBarrier bothSelected = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(selectionAttempt(service, beforeA, areaOne, bothSelected));
            Future<Boolean> second = workers.submit(selectionAttempt(service, beforeB, areaTwo, bothSelected));
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            TenantAuthorizationAttributes chosen = service.findById(stage.getId())
                    .getAttribute(TenantAuthorizationAttributes.attributeName());
            assertTrue(List.of(areaOne, areaTwo).contains(chosen));
        } finally {
            workers.shutdownNow();
        }
    }

    private static Callable<Boolean> selectionAttempt(OAuth2AuthorizationService service,
            OAuth2Authorization source, TenantAuthorizationAttributes selected, CyclicBarrier barrier) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
                service.save(OAuth2Authorization.from(source)
                        .attribute(TenantAuthorizationAttributes.attributeName(), selected).build());
                return true;
            } catch (OAuth2AuthenticationException stale) {
                assertEquals("invalid_grant", stale.getError().getErrorCode());
                return false;
            }
        };
    }

    @Test
    void concurrentCodeExchangeCanOnlyReturnOneAccessToken() throws Exception {
        seed("1", "TEST", "ENABLED");
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web").clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://web.example/callback").scope("profile").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        TenantAuthorizationAttributes selected = repository.findEligibleSelection(
                TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").orElseThrow();
        CustomUserDetails login = new CustomUserDetails(17, "Jia-A", 4,
                "alice", "not-persisted", AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.example/oauth2/authorize")
                .clientId("web").redirectUri("https://web.example/callback")
                .scope("profile").state("state-parallel").build();
        OAuth2Authorization initial = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        login, null, login.getAuthorities()))
                .attribute(TenantAuthorizationAttributes.attributeName(), selected)
                .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                .token(new OAuth2AuthorizationCode("code-parallel", Instant.now(),
                        Instant.now().plusSeconds(300))).build();
        service.save(initial);

        AtomicInteger minted = new AtomicInteger();
        OAuth2AuthorizationCodeAuthenticationProvider provider = new OAuth2AuthorizationCodeAuthenticationProvider(
                service, tokenContext -> {
                    Instant issued = Instant.now();
                    if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenContext.getTokenType())) {
                        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                                "access-parallel-" + minted.incrementAndGet(),
                                issued, issued.plusSeconds(300), tokenContext.getAuthorizedScopes());
                    }
                    if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenContext.getTokenType())) {
                        return new OAuth2RefreshToken("refresh-parallel-" + minted.incrementAndGet(),
                                issued, issued.plusSeconds(600));
                    }
                    return null;
                });
        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://issuer.example").build();
        AuthorizationServerContext serverContext = new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        };
        Callable<String> redeem = () -> {
            AuthorizationServerContextHolder.setContext(serverContext);
            try {
                OAuth2AccessTokenAuthenticationToken result = (OAuth2AccessTokenAuthenticationToken)
                        new TenantTokenGrantProvider(provider, service, repository, jdbc)
                                .authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                                "code-parallel", clientPrincipal, "https://web.example/callback", Map.of()));
                return result.getAccessToken().getTokenValue();
            } catch (OAuth2AuthenticationException exception) {
                return exception.getError().getErrorCode();
            } finally {
                AuthorizationServerContextHolder.resetContext();
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(redeem);
            Future<String> second = workers.submit(redeem);
            List<String> results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(value -> value.startsWith("access-parallel-")).count());
            assertEquals(1L, results.stream().filter("invalid_grant"::equals).count());
            assertFalse(service.findByToken("code-parallel", new OAuth2TokenType("code"))
                    .getToken(OAuth2AuthorizationCode.class).isActive());
            assertNotNull(service.findById(initial.getId()));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void machineAuthenticationWithCredentialsCannotBePersisted() throws Exception {
        RegisteredClient machine = RegisteredClient.withId("registered-machine")
                .clientId("machine-client")
                .clientSecret("{noop}test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS).scope("probe").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        OAuth2Authorization unsafe = OAuth2Authorization.withRegisteredClient(machine)
                .principalName("machine-client").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        "machine-client", "machine-private-secret", AuthorityUtils.NO_AUTHORITIES))
                .build();
        assertThrows(IllegalArgumentException.class, () -> service.save(unsafe));
        assertNull(service.findById(unsafe.getId()));
        OAuth2Authorization ordinary = OAuth2Authorization.withRegisteredClient(machine)
                .principalName("machine-client").authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();
        service.save(ordinary);
        assertEquals("machine-client", service.findById(ordinary.getId()).getPrincipalName());
    }

    @Test
    void authorizationStateNeedsNoTableAndIsNotSharedAcrossInstances() {
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web").clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://web.example/callback").scope("profile").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        CustomUserDetails user = new CustomUserDetails(17, "Jia-A", 4, "alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities()))
                .attribute(TenantAuthorizationAttributes.attributeName(), new TenantAuthorizationAttributes(
                        TenantId.of("1"), DataDomainId.of("cyf"), "test",
                        TenantAuthorizationAttributes.TenantKind.TEST, "3", "8", "4"))
                .token(new OAuth2AuthorizationCode("code-missing-table",
                        Instant.now(), Instant.now().plusSeconds(300))).build();
        service.save(authorization);
        OAuth2Authorization saved = service.findByToken("code-missing-table", new OAuth2TokenType("code"));
        assertNotNull(saved);
        assertEquals("1", saved.<TenantAuthorizationAttributes>getAttribute(
                TenantAuthorizationAttributes.attributeName()).tenantId().value());
        assertNull(new AuthorizationServerConfig().authorizationService().findById(saved.getId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE LOWER(TABLE_NAME) = 'oauth2_authorization'", Integer.class));
        service.remove(saved);
        assertNull(service.findById(saved.getId()));
    }


    @Test
    void accountRevocationWaitsForIssuanceAccountLock() throws Exception {
        var user = new CustomUserDetails(17, "Jia-A", 4, "alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> issue = workers.submit(() -> new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
                assertTrue(UserIssuanceAccountLock.current(jdbc, user));
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            Future<?> revoke = workers.submit(() -> new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status ->
                    assertEquals(1, jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=17 AND auth_epoch=4"))));
            assertThrows(TimeoutException.class, () -> revoke.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            issue.get(10, TimeUnit.SECONDS);
            revoke.get(10, TimeUnit.SECONDS);
            assertFalse(UserIssuanceAccountLock.current(jdbc, user));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test
    void revocationThatCommitsFirstBlocksBothCodeAndRefreshGrant() throws Exception {
        exerciseRevocationBeforeGrant(false);
    }

    @Test
    void accountSessionRevocationThatCommitsFirstBlocksBothCodeAndRefreshGrant() throws Exception {
        exerciseRevocationBeforeGrant(true);
    }

    private void exerciseRevocationBeforeGrant(boolean accountRevocation) throws Exception {
        seed("1", "TEST", "ENABLED");
        RegisteredClient client = RegisteredClient.withId("registered-web").clientId("web")
                .clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://web.example/callback").scope("profile").build();
        OAuth2AuthorizationService service = new AuthorizationServerConfig().authorizationService();
        CustomUserDetails user = new CustomUserDetails(17, "Jia-A", 4, "alice", "private-password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        OAuth2Authorization initial = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), new UsernamePasswordAuthenticationToken(
                        user, null, user.getAuthorities()))
                .attribute(TenantAuthorizationAttributes.attributeName(), repository.findEligibleSelection(
                        TenantId.of("1"), DataDomainId.of("cyf"), 17, "web").orElseThrow())
                .token(new OAuth2AuthorizationCode("revoked-code", Instant.now(), Instant.now().plusSeconds(300)))
                .refreshToken(new OAuth2RefreshToken("revoked-refresh", Instant.now(), Instant.now().plusSeconds(600)))
                .build();
        service.save(initial);
        AuthenticationProvider delegate = mock(AuthenticationProvider.class);
        TenantTokenGrantProvider guarded = new TenantTokenGrantProvider(delegate, service, repository, jdbc);
        OAuth2ClientAuthenticationToken principal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        CountDownLatch changedUnderLock = new CountDownLatch(1);
        CountDownLatch releaseRevoker = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = workers.submit(() -> new TransactionTemplate(
                    new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
                if (accountRevocation) {
                    jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=17 AND auth_epoch=4");
                } else {
                    jdbc.update("UPDATE core_tenant_control SET state='DISABLED', tenant_session_epoch=4 "
                            + "WHERE tenant_id='1'");
                }
                changedUnderLock.countDown();
                try {
                    if (!releaseRevoker.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(changedUnderLock.await(10, TimeUnit.SECONDS));
            Future<String> redeem = workers.submit(() -> {
                try {
                    guarded.authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                            "revoked-code", principal, "https://web.example/callback", Map.of()));
                    return "minted";
                } catch (OAuth2AuthenticationException exception) {
                    return exception.getError().getErrorCode();
                }
            });
            assertThrows(TimeoutException.class, () -> redeem.get(150, TimeUnit.MILLISECONDS));
            releaseRevoker.countDown();
            revoke.get(10, TimeUnit.SECONDS);
            assertEquals("invalid_grant", redeem.get(10, TimeUnit.SECONDS));
            assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                    () -> guarded.authenticate(new OAuth2RefreshTokenAuthenticationToken(
                            "revoked-refresh", principal, Set.of(), Map.of()))).getError().getErrorCode());
            verifyNoInteractions(delegate);
        } finally {
            releaseRevoker.countDown();
            workers.shutdownNow();
        }
    }

    private void seed(String tenantId, String kind, String controlState) {
        jdbc.update("INSERT INTO core_tenant VALUES (?, ?, ?)", tenantId, "area-" + tenantId, kind);
        jdbc.update("INSERT INTO core_tenant_control VALUES (?, ?, ?)", tenantId, controlState, 3L);
        jdbc.update("INSERT INTO core_tenant_membership VALUES (?, ?, ?, ?)", tenantId, 17L, "ACTIVE", 8L);
        jdbc.update("INSERT INTO oauth_client_tenant_grant VALUES (?, ?, ?, ?)", tenantId, "web", "ACTIVE", 4L);
        jdbc.update("INSERT INTO oauth_client_tenant_domain_grant VALUES (?, ?, ?, ?)", tenantId, "web", "cyf", "ACTIVE");
    }

    @Test
    void optionsEnumerateOnlyExactEligibleAreasForTheRegisteredClient() {
        seed("0", "NORMAL", "ENABLED");
        seed("1", "TEST", "ENABLED");
        assertEquals(List.of("0", "1"), repository.listEligibleSelections(DataDomainId.of("cyf"), 17, "web")
                .stream().map(selection -> selection.tenantId().value()).toList());
        jdbc.update("UPDATE core_tenant_control SET state='DISABLED' WHERE tenant_id='0'");
        assertEquals(List.of("1"), repository.listEligibleSelections(DataDomainId.of("cyf"), 17, "web")
                .stream().map(selection -> selection.tenantId().value()).toList());
        jdbc.update("UPDATE oauth_client_tenant_grant SET client_id='WEB' WHERE tenant_id='1'");
        assertTrue(repository.listEligibleSelections(DataDomainId.of("cyf"), 17, "web").isEmpty());
        jdbc.update("UPDATE oauth_client_tenant_grant SET client_id='web' WHERE tenant_id='1'");
        jdbc.update("INSERT INTO oauth_client VALUES ('duplicate-web', 'web')");
        assertTrue(repository.listEligibleSelections(DataDomainId.of("cyf"), 17, "web").isEmpty());
    }

    @Test
    void realConsentRequiresSessionBoundSelectionBeforeIssuingACode() throws Exception {
        seed("0", "NORMAL", "ENABLED");
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://issuer.example").build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
        seed("1", "TEST", "ENABLED");
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web").clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://web.example/callback").scope("profile")
                .clientSettings(ClientSettings.builder().requireProofKey(true)
                        .requireAuthorizationConsent(false).build()).build();
        var clients = new InMemoryRegisteredClientRepository(client);
        OAuth2AuthorizationService authorizations = new AuthorizationServerConfig()
                .authorizationService();
        var consentService = new InMemoryOAuth2AuthorizationConsentService();
        AuthenticationProvider requestProvider = new TenantAuthorizationRequestProvider(
                new OAuth2AuthorizationCodeRequestAuthenticationProvider(clients, authorizations, consentService),
                authorizations);
        AuthenticationProvider consentProvider = new TenantConsentGrantProvider(
                new OAuth2AuthorizationConsentAuthenticationProvider(clients, authorizations, consentService),
                authorizations, clients, repository, jdbc);
        TenantSelectionService selection = new TenantSelectionService(authorizations, clients, repository, jdbc);
        CustomUserDetails user = new CustomUserDetails(17, "Jia-A", 4, "alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        Authentication login = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        var browser = new MockHttpServletRequest("GET", "/oauth2/authorize");
        MockHttpSession session = (MockHttpSession) browser.getSession();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(browser));
        String verifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorizationRequest = new OAuth2AuthorizationCodeRequestAuthenticationToken(
                "https://issuer.example/oauth2/authorize", "web", login,
                "https://web.example/callback", "client-state", Set.of("profile"),
                Map.of("tenant_hint", "1", "code_challenge", challenge, "code_challenge_method", "S256"));
        OAuth2AuthorizationConsentAuthenticationToken consent = assertInstanceOf(
                OAuth2AuthorizationConsentAuthenticationToken.class,
                requestProvider.authenticate(authorizationRequest));
        assertNotNull(authorizations.findByToken(consent.getState(),
                TenantAuthorizationRequestProvider.CONSENT_STATE));
        assertEquals(List.of("1"), selection.options(session, consent.getState(), login).options()
                .stream().map(area -> area.tenantId().value()).toList());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", login, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> selection.select(new MockHttpSession(), consent.getState(), "1", login))
                .getError().getErrorCode());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> selection.select(session, consent.getState(), "0", login))
                .getError().getErrorCode());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> selection.select(session, consent.getState(), "01", login))
                .getError().getErrorCode());
        jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=17");
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> selection.select(session, consent.getState(), "1", login))
                .getError().getErrorCode());
        jdbc.update("UPDATE user_info SET auth_epoch=4 WHERE id=17");
        assertEquals("1", selection.select(session, consent.getState(), "1", login).selected().tenantId().value());
        OAuth2Authorization pending = authorizations.findByToken(consent.getState(),
                TenantAuthorizationRequestProvider.CONSENT_STATE);
        OAuth2AuthorizationRequest original = pending.getAttribute(OAuth2AuthorizationRequest.class.getName());
        OAuth2AuthorizationRequest changedPkce = OAuth2AuthorizationRequest.from(original)
                .additionalParameters(params -> params.put("code_challenge", "a-different-challenge"))
                .build();
        assertThrows(IllegalArgumentException.class, () -> authorizations.save(OAuth2Authorization.from(pending)
                .attribute(OAuth2AuthorizationRequest.class.getName(), changedPkce).build()));
        assertEquals(original, authorizations.findByToken(consent.getState(),
                TenantAuthorizationRequestProvider.CONSENT_STATE).getAttribute(OAuth2AuthorizationRequest.class.getName()));
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> selection.select(session, consent.getState(), "1", login))
                .getError().getErrorCode());
        CustomUserDetails replacement = new CustomUserDetails(18, "Jia-B", 4, "alice", "another-password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        Authentication switchedLogin = new UsernamePasswordAuthenticationToken(
                replacement, null, replacement.getAuthorities());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", switchedLogin, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        CustomUserDetails newerEpoch = new CustomUserDetails(17, "Jia-A", 5, "alice", "new-password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        Authentication updatedLogin = new UsernamePasswordAuthenticationToken(
                newerEpoch, null, newerEpoch.getAuthorities());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", updatedLogin, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        CustomUserDetails anotherOwner = new CustomUserDetails(17, "Jia-B", 4, "alice", "password",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        Authentication changedOwner = new UsernamePasswordAuthenticationToken(
                anotherOwner, null, anotherOwner.getAuthorities());
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", changedOwner, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=17");
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", login, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        jdbc.update("UPDATE user_info SET auth_epoch=4 WHERE id=17");
        Authentication result = consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                consent.getAuthorizationUri(), "web", login, consent.getState(), Set.of("profile"), Map.of()));
        var code = assertInstanceOf(OAuth2AuthorizationCodeRequestAuthenticationToken.class, result)
                .getAuthorizationCode();
        assertNotNull(code);
        OAuth2Authorization stored = authorizations.findByToken(code.getTokenValue(), new OAuth2TokenType("code"));
        assertEquals("1", ((TenantAuthorizationAttributes) stored.getAttribute(
                TenantAuthorizationAttributes.attributeName())).tenantId().value());
        assertEquals(challenge, ((OAuth2AuthorizationRequest) stored.getAttribute(
                OAuth2AuthorizationRequest.class.getName())).getAdditionalParameters().get("code_challenge"));
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        consent.getAuthorizationUri(), "web", login, consent.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        var secondRequest = new OAuth2AuthorizationCodeRequestAuthenticationToken(
                "https://issuer.example/oauth2/authorize", "web", login,
                "https://web.example/callback", "client-state-2", Set.of("profile"),
                Map.of("tenant_hint", "1", "code_challenge", challenge, "code_challenge_method", "S256"));
        OAuth2AuthorizationConsentAuthenticationToken second = assertInstanceOf(
                OAuth2AuthorizationConsentAuthenticationToken.class,
                requestProvider.authenticate(secondRequest));
        selection.select(session, second.getState(), "1", login);
        var anotherBrowser = new MockHttpServletRequest("POST", "/oauth2/authorize");
        anotherBrowser.setSession(new MockHttpSession());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(anotherBrowser));
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        second.getAuthorizationUri(), "web", login, second.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(browser));
        jdbc.update("UPDATE core_tenant_membership SET membership_revision=9 WHERE tenant_id='1'");
        assertEquals("invalid_request", assertThrows(OAuth2AuthenticationException.class,
                () -> consentProvider.authenticate(new OAuth2AuthorizationConsentAuthenticationToken(
                        second.getAuthorizationUri(), "web", login, second.getState(),
                        Set.of("profile"), Map.of()))).getError().getErrorCode());
        assertNull(authorizations.findByToken(second.getState(), TenantAuthorizationRequestProvider.CONSENT_STATE)
                .getToken(OAuth2AuthorizationCode.class));
    }

    @Test
    void fixedTenantCodeAndRefreshUseOnlyServerBindingWithoutAuthorizationTable() throws Exception {
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder()
                .issuer("https://issuer.example").build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return settings.getIssuer(); }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
        RegisteredClient client = RegisteredClient.withId("registered-web")
                .clientId("web").clientSecret("{noop}secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://web.example/callback").scope("profile")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false).build()).build();
        var clients = new InMemoryRegisteredClientRepository(client);
        OAuth2AuthorizationService authorizations = new AuthorizationServerConfig().authorizationService();
        FixedTenantAuthorizationPolicy policy = fixedPolicy("1", "web");
        var consentStore = new InMemoryOAuth2AuthorizationConsentService();
        AuthenticationProvider requestProvider = new FixedTenantAuthorizationRequestProvider(
                new OAuth2AuthorizationCodeRequestAuthenticationProvider(clients, authorizations, consentStore),
                authorizations, policy);
        AuthenticationProvider consentProvider = new FixedTenantConsentGrantProvider(
                new OAuth2AuthorizationConsentAuthenticationProvider(clients, authorizations, consentStore),
                authorizations, clients, policy, jdbc);
        CustomUserDetails user = new CustomUserDetails(17, "Jia-A", 4, "alice", "not-persisted",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        Authentication login = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        var browser = new MockHttpServletRequest("GET", "/oauth2/authorize");
        MockHttpSession session = (MockHttpSession) browser.getSession();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(browser));
        String fixedVerifier = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
        String fixedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(
                        fixedVerifier.getBytes(StandardCharsets.US_ASCII)));
        OAuth2AuthorizationConsentAuthenticationToken consent = assertInstanceOf(
                OAuth2AuthorizationConsentAuthenticationToken.class,
                requestProvider.authenticate(new OAuth2AuthorizationCodeRequestAuthenticationToken(
                        "https://issuer.example/oauth2/authorize", "web", login,
                        "https://web.example/callback", "client-state-fixed", Set.of("profile"),
                        Map.of("tenant_id", "0", "tenant_hint", "0",
                                "code_challenge", fixedChallenge, "code_challenge_method", "S256"))));
        OAuth2Authorization staged = authorizations.findByToken(
                consent.getState(), TenantAuthorizationRequestProvider.CONSENT_STATE);
        FixedTenantAuthorizationBinding binding = staged.getAttribute(
                FixedTenantAuthorizationBinding.attributeName());
        assertEquals("1", binding.tenantId().value());
        assertEquals("web", binding.clientId());
        assertNull(staged.getAttribute(TenantAuthorizationAttributes.attributeName()));
        assertEquals("web", new FixedTenantConsentService(authorizations, clients, policy)
                .view(session, consent.getState(), login).clientId());

        Authentication consentResult = consentProvider.authenticate(
                new OAuth2AuthorizationConsentAuthenticationToken(consent.getAuthorizationUri(), "web", login,
                        consent.getState(), Set.of("profile"), Map.of("tenant_id", "0")));
        OAuth2AuthorizationCode code = assertInstanceOf(
                OAuth2AuthorizationCodeRequestAuthenticationToken.class, consentResult).getAuthorizationCode();
        OAuth2Authorization persistedCode = authorizations.findByToken(code.getTokenValue(), new OAuth2TokenType("code"));
        assertEquals(binding, persistedCode.getAttribute(FixedTenantAuthorizationBinding.attributeName()));

        OAuth2TokenGenerator<OAuth2Token> generator = context -> {
            Instant now = Instant.now();
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                        "fixed-access-" + context.getAuthorizationGrantType().getValue(), now,
                        now.plusSeconds(300), context.getAuthorizedScopes());
            }
            if (OAuth2TokenType.REFRESH_TOKEN.equals(context.getTokenType())) {
                return new OAuth2RefreshToken("fixed-refresh", now, now.plusSeconds(600));
            }
            return null;
        };
        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                client, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        OAuth2AccessTokenAuthenticationToken issued = assertInstanceOf(OAuth2AccessTokenAuthenticationToken.class,
                new FixedTenantTokenGrantProvider(
                        new OAuth2AuthorizationCodeAuthenticationProvider(authorizations, generator),
                        authorizations, policy, jdbc).authenticate(new OAuth2AuthorizationCodeAuthenticationToken(
                        code.getTokenValue(), clientPrincipal, "https://web.example/callback",
                        Map.of("code_verifier", fixedVerifier))));
        OAuth2Authorization exchanged = authorizations.findByToken(
                issued.getRefreshToken().getTokenValue(), OAuth2TokenType.REFRESH_TOKEN);
        assertEquals(binding, exchanged.getAttribute(FixedTenantAuthorizationBinding.attributeName()));

        AccountSecurityService accounts = mock(AccountSecurityService.class);
        when(accounts.findByUserId(17)).thenReturn(java.util.Optional.of(
                new AccountSecuritySnapshot(17, "Jia-A", AccountState.ACTIVE, 4)));
        JwtEncodingContext claims = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder().subject("alice"))
                .registeredClient(client).principal(exchanged.getAttribute(Principal.class.getName()))
                .authorization(exchanged).tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).build();
        new AccountSecurityTokenCustomizer(accounts, policy).customize(claims);
        assertEquals("1", claims.getClaims().build().getClaim("tenant_id"));
        assertNull(claims.getClaims().build().getClaim("tenant_session_epoch"));
        assertNull(claims.getClaims().build().getClaim("membership_revision"));

        OAuth2AccessTokenAuthenticationToken refreshed = assertInstanceOf(OAuth2AccessTokenAuthenticationToken.class,
                new FixedTenantTokenGrantProvider(
                        new OAuth2RefreshTokenAuthenticationProvider(authorizations, generator),
                        authorizations, policy, jdbc).authenticate(new OAuth2RefreshTokenAuthenticationToken(
                        issued.getRefreshToken().getTokenValue(), clientPrincipal, Set.of(), Map.of())));
        assertEquals("fixed-access-refresh_token", refreshed.getAccessToken().getTokenValue());

        RegisteredClient wrong = RegisteredClient.withId("registered-other").clientId("other")
                .clientSecret("{noop}secret").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN).scope("profile").build();
        OAuth2ClientAuthenticationToken wrongPrincipal = new OAuth2ClientAuthenticationToken(
                wrong, ClientAuthenticationMethod.CLIENT_SECRET_BASIC, "secret");
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new FixedTenantTokenGrantProvider(
                        new OAuth2RefreshTokenAuthenticationProvider(authorizations, generator),
                        authorizations, policy, jdbc).authenticate(new OAuth2RefreshTokenAuthenticationToken(
                        issued.getRefreshToken().getTokenValue(), wrongPrincipal, Set.of(), Map.of())))
                .getError().getErrorCode());
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new FixedTenantTokenGrantProvider(
                        new OAuth2RefreshTokenAuthenticationProvider(authorizations, generator),
                        authorizations, fixedPolicy("0", "web"), jdbc).authenticate(
                        new OAuth2RefreshTokenAuthenticationToken(issued.getRefreshToken().getTokenValue(),
                                clientPrincipal, Set.of(), Map.of())))
                .getError().getErrorCode());

        OAuth2AuthorizationRequest oldRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://issuer.example/oauth2/authorize").clientId("web")
                .redirectUri("https://web.example/callback").scope("profile").state("old").build();
        OAuth2Authorization oldUnbound = OAuth2Authorization.withRegisteredClient(client)
                .principalName("alice").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("profile"))
                .attribute(Principal.class.getName(), login)
                .attribute(OAuth2AuthorizationRequest.class.getName(), oldRequest)
                .refreshToken(new OAuth2RefreshToken("old-unbound-refresh", Instant.now(),
                        Instant.now().plusSeconds(600))).build();
        authorizations.save(oldUnbound);
        assertEquals("invalid_grant", assertThrows(OAuth2AuthenticationException.class,
                () -> new FixedTenantTokenGrantProvider(
                        new OAuth2RefreshTokenAuthenticationProvider(authorizations, generator),
                        authorizations, policy, jdbc).authenticate(new OAuth2RefreshTokenAuthenticationToken(
                        "old-unbound-refresh", clientPrincipal, Set.of(), Map.of())))
                .getError().getErrorCode());
    }

    private static FixedTenantAuthorizationPolicy fixedPolicy(String tenantId, String clientId) {
        TenantIssuanceProperties properties = new TenantIssuanceProperties();
        properties.setAdmissionMode(TenantIssuanceProperties.Mode.FIXED_TENANT);
        properties.setFixedTenantId(tenantId);
        properties.setFixedClientTenants(Map.of(clientId, tenantId));
        return FixedTenantAuthorizationPolicy.from(properties);
    }
}
