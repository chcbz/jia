package cn.jia.test;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.embedded.RedisExecProvider;
import redis.embedded.RedisServer;
import redis.embedded.RedisServerBuilder;
import redis.embedded.util.OS;
import redis.embedded.util.OSDetector;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 内置Redis服务器加载
 * @author chc
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
        RedisExecProvider customProvider = RedisExecProvider.defaultProvider();
        RedisServerBuilder builder = RedisServer.builder().redisExecProvider(customProvider)
                .setting("bind 127.0.0.1").port(redisPort).setting("daemonize no").setting("appendonly no");
        if (OS.WINDOWS.equals(OSDetector.getOS())) {
            builder.setting("maxheap 200m");
        }
        redisServer = builder.build();
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
