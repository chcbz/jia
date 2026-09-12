package cn.jia.sms.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class SmsExternalHttpConfiguration {
    @Bean("smsExternalRestTemplate")
    public RestTemplate smsExternalRestTemplate(
            @Qualifier("smsExternalClientHttpRequestFactory") ClientHttpRequestFactory requestFactory) {
        RestTemplate template = new RestTemplate(requestFactory);
        // Preserve the shared template's response behavior while keeping this client isolated.
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return template;
    }

    @Bean("smsExternalClientHttpRequestFactory")
    public ClientHttpRequestFactory smsExternalClientHttpRequestFactory(SmsExternalHttpTimeouts timeouts) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeouts.getConnectTimeoutMillis());
        factory.setReadTimeout(timeouts.getReadTimeoutMillis());
        return factory;
    }
}
