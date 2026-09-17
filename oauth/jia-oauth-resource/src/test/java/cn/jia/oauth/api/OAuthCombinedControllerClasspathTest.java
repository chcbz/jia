package cn.jia.oauth.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class OAuthCombinedControllerClasspathTest {
    @Test
    void clientTokenAndResourceIdentityControllersCoexistWithoutClassOrBeanShadowing() throws Exception {
        ClassLoader loader = OAuthResourceIdentityController.class.getClassLoader();
        assertEquals(1, Collections.list(loader.getResources(
                "cn/jia/oauth/api/AuthenticationController.class")).size());
        assertEquals(1, Collections.list(loader.getResources(
                "cn/jia/oauth/api/OAuthResourceIdentityController.class")).size());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AuthenticationController.class, OAuthResourceIdentityController.class);
            context.refresh();
            assertNotNull(context.getBean(AuthenticationController.class));
            assertNotNull(context.getBean(OAuthResourceIdentityController.class));
            assertEquals("/token", AuthenticationController.class.getDeclaredMethod("token",
                    org.springframework.security.oauth2.client.OAuth2AuthorizedClient.class)
                    .getAnnotation(GetMapping.class).value()[0]);
            assertEquals("/resource", OAuthResourceIdentityController.class
                    .getDeclaredMethod("resource", org.springframework.security.core.Authentication.class)
                    .getAnnotation(GetMapping.class).value()[0]);
        }
    }
}
