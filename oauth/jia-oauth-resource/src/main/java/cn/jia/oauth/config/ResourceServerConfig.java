package cn.jia.oauth.config;

import cn.jia.oauth.filter.LegacyAgentTenantBoundaryFilter;
import cn.jia.oauth.filter.TrustedTenantContextFilter;
import cn.jia.oauth.filter.TrustedTenantContextCleanupFilter;
import cn.jia.oauth.security.TenantDeploymentTokenValidator;
import cn.jia.oauth.security.AccountSecurityJwtValidator;
import cn.jia.oauth.security.TenantAuthorizationVerifier;
import cn.jia.oauth.security.TenantAdmissionPolicy;
import cn.jia.oauth.security.UserAdmissionScopeResolver;
import cn.jia.oauth.security.CentralSigningProperties;
import cn.jia.user.security.AccountSecurityService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.security.KeyPair;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Set;
import java.nio.file.Path;
import java.io.IOException;
import java.text.ParseException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * OAuth2 资源服务器配置类
 * 用于配置资源服务器的安全策略，包括JWT验证、权限控制等
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OAuth2ResourceServerConfigurer.class)
public class ResourceServerConfig {

    private static final String WX_MP_CHECK_SIGNATURE_PATH = "/wx/mp/checksignature";

    @Getter
    @Setter
    public static class TenantDeploymentProperties {
        private TenantAdmissionPolicy.Mode admissionMode = TenantAdmissionPolicy.Mode.PLATFORM;
        private String fixedTenantId;
        /** Existing platform-mode compatibility switch; not valid in fixed-tenant mode. */
        private boolean requireBoundIdentity;
        /** Existing platform-mode deployment allowlist; fixed-tenant uses fixedTenantId only. */
        private List<String> allowedTenants = new ArrayList<>();
    }

    @Bean
    @ConfigurationProperties(prefix = "cyf.tenant")
    public TenantDeploymentProperties tenantDeploymentProperties() {
        return new TenantDeploymentProperties();
    }

    @Getter
    @Setter
    public static class CentralJwtTrustProperties {
        /** Exact issuer value from the central authorization server. */
        private String issuerUri;
        /** Central server's public JWKS endpoint, never a client-provided URL. */
        private String jwkSetUri;
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.security.oauth2.resourceserver.jwt")
    public CentralJwtTrustProperties centralJwtTrustProperties() {
        return new CentralJwtTrustProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "oauth.resource")
    public OauthResourceProperties oauthResourceProperties() {
        return new OauthResourceProperties();
    }

    @Bean
    @ConditionalOnMissingBean(CentralSigningProperties.class)
    @ConfigurationProperties(prefix = "cyf.oauth.signing")
    public CentralSigningProperties centralSigningProperties() {
        return new CentralSigningProperties();
    }

    @Getter
    @Setter
    @ToString
    static class OauthResourceProperties {
        private List<String> uris = new ArrayList<>();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain oauthIdentityResourceSecurityFilterChain(
            HttpSecurity http, TenantDeploymentProperties tenantDeploymentProperties,
            ObjectProvider<AccountSecurityService> accountSecurityServiceProvider) {
        TenantAdmissionPolicy admissionPolicy = TenantAdmissionPolicy.from(tenantDeploymentProperties);
        http
                .securityMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/resource"))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterBefore(new TrustedTenantContextCleanupFilter(),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TrustedTenantContextFilter(
                                new UserAdmissionScopeResolver(admissionPolicy,
                                        accountSecurityServiceProvider.getIfAvailable()), admissionPolicy),
                        BearerTokenAuthenticationFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * 配置资源服务器的安全过滤链
     *
     * @param http HttpSecurity对象，用于配置安全策略
     * @return SecurityFilterChain 安全过滤链
     */
    @Bean
    @Order(3)
    public SecurityFilterChain resourceServerSecurityFilterChain(
            HttpSecurity http, OauthResourceProperties resourceProperties,
            ObjectProvider<AccountSecurityService> accountSecurityServiceProvider,
            TenantDeploymentProperties tenantDeploymentProperties) {
        TenantAdmissionPolicy admissionPolicy = TenantAdmissionPolicy.from(tenantDeploymentProperties);
        AccountSecurityService accountSecurityService = accountSecurityServiceProvider.getIfAvailable();
        List<String> resourceUris = new ArrayList<>(resourceProperties.getUris());
        // A strict deployment may supply no legacy resource URI list at all. Route
        // old tenant-zero browser APIs through the authenticated bearer + scope gate.
        if (admissionPolicy.requiresIsolatedResourceLane()) {
            for (String legacyPath : List.of("/agent", "/agent/**", "/chat", "/chat/**",
                    "/archive", "/archive/**", "/juyiting", "/juyiting/**",
                    "/task", "/task/**", "/job", "/job/**",
                    "/economy", "/economy/**", "/point", "/point/**",
                    "/workflow", "/workflow/**", "/tip", "/tip/**",
                    "/user/**", "/ws/chat")) {
                if (!resourceUris.contains(legacyPath)) resourceUris.add(legacyPath);
            }
            // Account identity and session revocation are global-account operations;
            // a local area-1 deployment must authenticate them with its central JWT
            // even when it has no legacy oauth.resource.uris list.
            for (String identityPath : List.of("/user/my", "/user/me/sessions/revoke-all")) {
                if (!resourceUris.contains(identityPath)) resourceUris.add(identityPath);
            }
        }
        if (resourceUris.isEmpty()) {
            http.securityMatcher(request -> false);
        } else {
            if (accountSecurityService == null) {
                throw new IllegalStateException(
                        "AccountSecurityService is required when oauth.resource.uris are configured");
            }
            if (admissionPolicy.requiresIsolatedResourceLane()) {
                // /user/** also matches /user. Preserve the existing Principal
                // endpoint backed by form login while gating /user/... business APIs.
                PathPatternRequestMatcher principalEndpoint =
                        PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/user");
                PathPatternRequestMatcher principalHeadEndpoint =
                        PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, "/user");
                List<PathPatternRequestMatcher> matchers = resourceUris.stream()
                        .map(PathPatternRequestMatcher::pathPattern).toList();
                http.securityMatcher(request -> !principalEndpoint.matches(request)
                        && !principalHeadEndpoint.matches(request)
                        && matchers.stream().anyMatch(matcher -> matcher.matches(request)));
            } else {
                http.securityMatcher(resourceUris.toArray(new String[0]));
            }
        }
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(WX_MP_CHECK_SIGNATURE_PATH).permitAll()
                        .requestMatchers("/dwz/view/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterBefore(new TrustedTenantContextCleanupFilter(),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new TrustedTenantContextFilter(
                                new UserAdmissionScopeResolver(admissionPolicy, accountSecurityService),
                                admissionPolicy),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new LegacyAgentTenantBoundaryFilter(admissionPolicy),
                        TrustedTenantContextFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(AbstractHttpConfigurer::disable)
        ;
        return http.build();
    }

    /**
     * 默认发放令牌
     *
     * @return 令牌
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource(CentralSigningProperties signing) {
        if (signing.isEnabled()) {
            signing.validatedIssuer();
            String file = signing.getPrivateJwkPath();
            if (file == null || file.isBlank() || !file.equals(file.strip())) {
                throw new IllegalStateException("Central signing private JWK path is missing");
            }
            try {
                Path path = Path.of(file);
                if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                    throw new IllegalStateException("Central signing private JWK must be a readable absolute file");
                }
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
                if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_"))) {
                    throw new IllegalStateException("Central signing private JWK must have owner-only permissions");
                }
                RSAKey key = RSAKey.parse(Files.readString(path));
                if (key.getKeyID() == null || key.getKeyID().isBlank()
                        || key.toRSAPublicKey() == null || key.toRSAPrivateKey() == null
                        || key.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                        || key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) {
                    throw new IllegalStateException("Central signing JWK must contain an RSA public/private pair, kid and RS256 usage");
                }
                if (key.toRSAPublicKey().getModulus().bitLength() < 2048) {
                    throw new IllegalStateException("Central signing key must be at least RSA-2048");
                }
                Signature probe = Signature.getInstance("SHA256withRSA");
                probe.initSign(key.toRSAPrivateKey());
                probe.update(new byte[] {1, 2, 3, 4});
                byte[] signedProbe = probe.sign();
                probe.initVerify(key.toRSAPublicKey());
                probe.update(new byte[] {1, 2, 3, 4});
                if (!probe.verify(signedProbe)) {
                    throw new IllegalStateException("Central signing public/private JWK pair does not match");
                }
                return new ImmutableJWKSet<>(new JWKSet(key));
            } catch (IOException | ParseException | JOSEException | GeneralSecurityException | IllegalArgumentException ex) {
                throw new IllegalStateException("Cannot load central signing private JWK", ex);
            }
        }
        if (signing.getIssuer() != null && !signing.getIssuer().isBlank()
                || signing.getPrivateJwkPath() != null && !signing.getPrivateJwkPath().isBlank()) {
            throw new IllegalStateException("Central signing parameters require cyf.oauth.signing.enabled=true");
        }
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static KeyPair generateRsaKey() {
        KeyPair keyPair;
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            keyPair = keyPairGenerator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return keyPair;
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource,
                                 ObjectProvider<AccountSecurityService> accountSecurityServiceProvider,
                                 ObjectProvider<TenantAuthorizationVerifier> tenantAuthorizationVerifierProvider,
                                 TenantDeploymentProperties deploymentProperties,
                                 CentralJwtTrustProperties centralTrust,
                                 CentralSigningProperties signing) {
        TenantAdmissionPolicy admissionPolicy = TenantAdmissionPolicy.from(deploymentProperties);
        TenantDeploymentTokenValidator deploymentValidator = new TenantDeploymentTokenValidator(admissionPolicy);
        AccountSecurityService account = accountSecurityServiceProvider.getIfAvailable();
        TenantAuthorizationVerifier verifier = tenantAuthorizationVerifierProvider.getIfAvailable();
        if (admissionPolicy.requiresIsolatedResourceLane() && account == null) {
            throw new IllegalStateException("Isolated tenant deployment requires current account checks");
        }
        if (admissionPolicy.requiresTenantAuthorizationVerifierAtStartup() && verifier == null) {
            throw new IllegalStateException("Strict tenant deployment requires current account and tenant authorization checks");
        }
        NimbusJwtDecoder decoder;
        if (admissionPolicy.requiresCentralTrust()) {
            requireCentralJwtTrust(centralTrust);
            if (signing.isEnabled() && !signing.validatedIssuer().equals(centralTrust.getIssuerUri())) {
                throw new IllegalStateException("Central signer and resource verifier issuers must match");
            }
            decoder = NimbusJwtDecoder.withJwkSetUri(centralTrust.getJwkSetUri()).build();
        } else {
            decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                admissionPolicy.requiresCentralTrust()
                        ? JwtValidators.createDefaultWithIssuer(centralTrust.getIssuerUri())
                        : signing.isEnabled() ? JwtValidators.createDefaultWithIssuer(signing.validatedIssuer())
                        : JwtValidators.createDefault(),
                new AccountSecurityJwtValidator(account, verifier, admissionPolicy),
                deploymentValidator));
        return decoder;
    }

    private static void requireCentralJwtTrust(CentralJwtTrustProperties trust) {
        if (trust == null) throw new IllegalStateException("Central JWT trust is required for isolated deployments");
        CentralSigningProperties.requireTrustedUri(trust.getIssuerUri(), "issuer");
        CentralSigningProperties.requireTrustedUri(trust.getJwkSetUri(), "JWKS URI");
    }

}
