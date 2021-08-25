package cn.jia.test;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.embedded.RedisServer;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * 内置Redis服务器加载
 */
@Slf4j
@Component
public class RedisServerLoader {

    @Value("${spring.redis.port}")
    private int redisPort;

    private RedisServer redisServer;

    /**
     * 启动Redis服务器
     *
     */
    @PostConstruct
    public void startRedis() {
        log.info("redis server is starting...");
        redisServer = RedisServer.builder().setting("maxheap 200m").port(redisPort).setting("bind localhost")
                .setting("tcp-keepalive 1").build();
        redisServer.start();
        log.info("redis server has started");
    }

    /**
     * 关闭Redis服务器.
     */
    @PreDestroy
    public void stopRedis() {
        log.info("redis server is stopping...");
        redisServer.stop();
        log.info("redis server has stopped.");
    }
}
