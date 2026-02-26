package cn.jia.core.config;

import cn.jia.core.redis.RedisService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedisSerializer.class)
public class RedisConfig {
    @Bean
    public RedisService redisService() {
        return new RedisService();
    }
}
