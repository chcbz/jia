package cn.jia.core.config;

import cn.jia.core.elasticsearch.ElasticsearchService;
import cn.jia.core.elasticsearch.ElasticsearchTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.elasticsearch.uris")
public class ElasticsearchConfig {
    @Bean
    public ElasticsearchTimeouts elasticsearchTimeouts(
            @Value("${spring.elasticsearch.connection-timeout:500ms}") Duration connectTimeout,
            @Value("${spring.elasticsearch.socket-timeout:1750ms}") Duration socketTimeout,
            @Value("${jia.elasticsearch.request-timeout:2500ms}") Duration requestTimeout,
            @Value("${jia.elasticsearch.safety-margin:100ms}") Duration safetyMargin) {
        return new ElasticsearchTimeouts(connectTimeout, socketTimeout, requestTimeout, safetyMargin);
    }

    @Bean
    public ElasticsearchService elasticsearchService() {
        return new ElasticsearchService();
    }
}
