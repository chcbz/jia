package cn.jia.base.config;

import cn.jia.core.elasticsearch.ElasticsearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ElasticsearchOperations.class)
public class ElasticsearchConfig {
    @Bean
    public ElasticsearchService elasticsearchService() {
        return new ElasticsearchService();
    }
}
