package cn.jia.core.redis;

import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis服务类，提供对Redis的各种操作封装
 * 包括基本的键值操作、订阅发布功能等
 */
@Slf4j
public class RedisService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Autowired
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;


    /**
     * 获取Redis中指定键的字符串值
     *
     * @param key 键
     * @return 内容
     */
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取Redis中指定键的值并转换为指定类型的对象
     *
     * @param key 键
     * @param clazz 值类型
     * @return 内容
     */
    public <T> T get(String key, Class<T> clazz) {
        String value = redisTemplate.opsForValue().get(key);
        return JsonUtil.fromJson(value, clazz);
    }

    /**
     * 设置键值对到Redis中
     *
     * @param key 键
     * @param value 值
     */
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置键值对到Redis中并指定有效期
     *
     * @param key 键
     * @param value 值
     * @param duration 有效期
     */
    public void set(String key, String value, Duration duration) {
        redisTemplate.opsForValue().set(key, value, duration);
    }

    /**
     * 设置键值对到Redis中并指定有效期
     *
     * @param key 键
     * @param value 值
     * @param timeout 有效期
     * @param timeUnit 超时单位
     */
    public void set(String key, String value, Long timeout, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    /**
     * 删除Redis中的指定键
     *
     * @param key 健
     * @return 结果
     */
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    /**
     * 批量删除Redis中的指定键
     *
     * @param key 健列表
     * @return 结果
     */
    public Long delete(Collection<String> key) {
        return redisTemplate.delete(key);
    }

    /**
     * 根据表达式获取匹配的所有key
     *
     * @param pattern 表达式
     * @return key列表
     */
    public Set<String> keys(String pattern) {
        return redisTemplate.keys(pattern);
    }

    /**
     * 执行Redis命令回调
     *
     * @param action redis命令
     * @return 执行结果
     * @param <T> 返回类型
     */
    public <T> T execute(RedisCallback<T> action) {
        return redisTemplate.execute(action);
    }

    /**
     * 订阅频道，监听特定会话的信号
     *
     * @param sessionId 会话ID
     * @return 消息流
     */
    public Flux<String> subscribeToChannel(String sessionId) {
        String channel = "channel:" + sessionId;
        return reactiveRedisTemplate.listenToChannel(channel)
                .map(ReactiveSubscription.Message::getMessage)
                .doOnSubscribe(sub ->
                        log.info("订阅频道: {}", channel))
                .doOnCancel(() ->
                        log.info("取消订阅: {}", channel));
    }

    /**
     * 发布信号到指定会话的频道
     *
     * @param sessionId 会话ID
     * @return 接收到消息的订阅者数量
     */
    public Mono<Long> publishSignal(String sessionId) {
        String channel = "channel:" + sessionId;
        return reactiveRedisTemplate.convertAndSend(channel, "SIGNAL")
                .doOnSuccess(count ->
                        log.info("信号已发布到 {} 个订阅者", count));
    }
}
