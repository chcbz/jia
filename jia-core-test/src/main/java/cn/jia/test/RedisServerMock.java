package cn.jia.test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.embedded.RedisServer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 单元测试Redis服务器
 */
@Component
public class RedisServerMock {

    @Value("${spring.redis.port}")
    private int redisPort;

    private RedisServer redisServer;

    /**
     * 构造方法之后执行.
     *
     */
    @PostConstruct
    public void startRedis() {
        redisServer = RedisServer.builder().setting("maxheap 200m").port(redisPort).setting("bind localhost").build();
        redisServer.start();
    }

    /**
     * 析构方法之后执行.
     */
    @PreDestroy
    public void stopRedis() {
        redisServer.stop();
    }
}
