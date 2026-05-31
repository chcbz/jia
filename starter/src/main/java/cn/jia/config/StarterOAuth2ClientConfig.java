package cn.jia.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
public class StarterOAuth2ClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public ClientRegistrationRepository clientRegistrationRepository() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("jia-client-oidc")
                .clientId("jia_client")
                .clientSecret("jia_secret")
                .clientName("jia-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:10018/login/oauth2/code/jia-client-oidc")
                .scope("openid")
                .authorizationUri("http://localhost:10018/oauth2/authorize")
                .tokenUri("http://localhost:10018/oauth2/token")
                .jwkSetUri("http://localhost:10018/oauth2/jwks")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
