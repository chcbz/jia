package cn.jia.oauth.config;

import cn.jia.user.security.AccountSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the shipped dev/grey/prod profiles route economy through the real JWT resource chain. */
class EconomyResourceProfileSecurityTest {

    @Test
    void devGreyAndProdProfilesProtectEconomyWithJwtResourceChain() throws Exception {
        for (String profile : List.of("dev", "grey", "prod")) {
            assertEconomyJwtRouting(profile);
        }
    }

    private static void assertEconomyJwtRouting(String profile) throws Exception {
        List<String> resourceProperties = resourceProperties(profile);
        assertTrue(resourceProperties.stream().anyMatch(value -> value.endsWith("=/economy/**")), profile);

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        try {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, resourceProperties.toArray(String[]::new));
            context.register(TestApplication.class);
            context.refresh();

            FilterChainProxy security = context.getBean(FilterChainProxy.class);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/economy/wallet");
            long matchingChains = security.getFilterChains().stream().filter(chain -> chain.matches(request)).count();
            assertEquals(1L, matchingChains, profile);

            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
            mvc.perform(get("/economy/wallet")).andExpect(status().isUnauthorized());
            mvc.perform(get("/economy/wallet").header("Authorization", "Bearer valid-user"))
                    .andExpect(status().isNoContent());
        } finally {
            context.close();
        }
    }

    private static List<String> resourceProperties(String profile) throws IOException {
        Path file = repositoryRoot().resolve("starter/src/main/resources/application-" + profile + ".properties");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        List<String> names = properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("oauth.resource.uris["))
                .sorted(Comparator.comparingInt(EconomyResourceProfileSecurityTest::propertyIndex))
                .toList();
        List<String> values = new ArrayList<>(names.size());
        for (String name : names) values.add(name + "=" + properties.getProperty(name));
        return List.copyOf(values);
    }

    private static int propertyIndex(String name) {
        int start = name.indexOf('[') + 1;
        int end = name.indexOf(']', start);
        return Integer.parseInt(name.substring(start, end));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("starter/src/main/resources"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("API repository root not found");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @EnableConfigurationProperties
    @Import(ResourceServerConfig.class)
    static class TestApplication {
        @Bean
        EconomyProbeController economyProbeController() {
            return new EconomyProbeController();
        }

        @Bean
        AccountSecurityService accountSecurityService() {
            return mock(AccountSecurityService.class);
        }

        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject("user-1").claim("client_id", "client-a").claim("jiacn", "tenant-a")
                    .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300)).build();
        }
    }

    @RestController
    static class EconomyProbeController {
        @GetMapping("/economy/wallet")
        ResponseEntity<Void> wallet() {
            return ResponseEntity.noContent().build();
        }
    }
}
