package cn.jia.core.config;

import cn.jia.core.elasticsearch.ElasticsearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("spring.elasticsearch.uris")
public class ElasticsearchConfig {
    @Bean
    public ElasticsearchService elasticsearchService() {
        return new ElasticsearchService();
    }
}
