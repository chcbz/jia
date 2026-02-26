package cn.jia.test;

import com.github.microwww.redis.RedisServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.IOException;

/**
 * 内置Redis服务器加载
 * @author chc
 */
@Slf4j
@Component
public class RedisServerLoader {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    private RedisServer redisServer;

    /**
     * 启动Redis服务器
     *
     */
    @PostConstruct
    public void startRedis() throws IOException {
        log.info("redis server is starting...");
        redisServer = new RedisServer();
        redisServer.listener(redisHost, redisPort);
        log.info("redis server has started");
    }

    /**
     * 关闭Redis服务器.
     */
    @PreDestroy
    public void stopRedis() {
        log.info("redis server is stopping...");
//        redisServer.stop();
        log.info("redis server has stopped.");
    }
}