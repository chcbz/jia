package cn.jia.core.config;

import cn.jia.core.deadline.RequestDeadlineClientHttpRequestInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RestTemplateConfigTest {
    @Test
    void sharedRestTemplateInstallsExactlyOneDeadlinePropagationInterceptor() {
        RestTemplateConfig config = new RestTemplateConfig();
        var restTemplate = config.restTemplate(new SimpleClientHttpRequestFactory());

        assertEquals(1, restTemplate.getInterceptors().size());
        assertInstanceOf(RequestDeadlineClientHttpRequestInterceptor.class, restTemplate.getInterceptors().get(0));
        assertInstanceOf(RestTemplateConfig.RestErrorHandler.class, restTemplate.getErrorHandler());
    }
}
