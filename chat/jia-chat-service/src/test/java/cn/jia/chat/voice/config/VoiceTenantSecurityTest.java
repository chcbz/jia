package cn.jia.chat.voice.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoiceTenantSecurityTest {
    @Test
    void strictVoiceChainRejectsOtherRegionAndMissingBinding() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityFixture.class)
                .withPropertyValues("cyf.tenant.require-bound-identity=true")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    mvc.perform(get("/chat/speech/probe").header("Authorization", "Bearer region-one"))
                            .andExpect(status().isUnauthorized());
                    mvc.perform(get("/chat/speech/probe").header("Authorization", "Bearer unbound"))
                            .andExpect(status().isUnauthorized());
                    mvc.perform(get("/chat/speech/probe").header("Authorization", "Bearer malformed-area"))
                            .andExpect(status().isUnauthorized());
                    mvc.perform(get("/chat/speech/probe").header("Authorization", "Bearer region-zero"))
                            .andExpect(status().isOk());
                    mvc.perform(get("/chat/speech/probe"))
                            .andExpect(status().isUnauthorized());
                    assertEquals(1, context.getBean(FilterChainProxy.class).getFilterChains().stream()
                            .filter(chain -> chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                                    "GET", "/chat/speech/probe"))).count());
                });
    }

    @Test
    void compatibilityVoiceChainRetainsPreviousAccess() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SecurityFixture.class)
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                            .addFilters(context.getBean(FilterChainProxy.class)).build();
                    mvc.perform(get("/chat/speech/probe").header("Authorization", "Bearer region-one"))
                            .andExpect(status().isOk());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @org.springframework.context.annotation.Import(VoiceSecurityConfiguration.class)
    static class SecurityFixture {
        @Bean
        ObjectMapper objectMapper() {
            return mock(ObjectMapper.class);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                Jwt.Builder builder = Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .subject("person")
                        .issuedAt(Instant.now().minusSeconds(30))
                        .expiresAt(Instant.now().plusSeconds(60));
                switch (token) {
                    case "region-zero" -> builder.claim("tenant_id", "0").claim("data_domain_id", "cyf");
                    case "region-one" -> builder.claim("tenant_id", "1").claim("data_domain_id", "cyf");
                    case "malformed-area" -> builder.claim("tenant_id", "00").claim("data_domain_id", "cyf");
                    case "unbound" -> { }
                    default -> throw new BadJwtException("unknown test token");
                }
                return builder.build();
            };
        }

        @Bean
        VoiceProbe voiceProbe() {
            return new VoiceProbe();
        }
    }

    @RestController
    static class VoiceProbe {
        @GetMapping("/chat/speech/probe")
        String probe() {
            return "ok";
        }
    }
}
