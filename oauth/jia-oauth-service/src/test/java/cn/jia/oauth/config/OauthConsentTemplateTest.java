package cn.jia.oauth.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OauthConsentTemplateTest {
    @Test
    void consentFormPostsToExactAuthorizationEndpoint() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/templates/oauth/authorize.html")) {
            assertNotNull(input);
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("method=\"post\" action=\"/oauth2/authorize\""));
            assertFalse(html.contains("method=\"post\" action=\"authorize\""));
        }
    }
}
