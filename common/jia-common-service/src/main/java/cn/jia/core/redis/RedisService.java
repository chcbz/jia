package cn.jia.core.redis;

import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.List;
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
        requireCommandBudget();
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 原子读取并删除键值，适用于一次性令牌等不可重放场景。
     *
     * @param key 键
     * @return 删除前的值；键不存在时返回null
     */
    public String getAndDelete(String key) {
        requireCommandBudget();
        DefaultRedisScript<String> script = new DefaultRedisScript<>(
                "local value = redis.call('GET', KEYS[1]); "
                        + "if value then redis.call('DEL', KEYS[1]); end; return value",
                String.class);
        return redisTemplate.execute(script, List.of(key));
    }

    /**
     * Deletes a key only while it still contains the expected value.
     *
     * @param key key to inspect
     * @param expectedValue value observed by the caller
     * @return whether the matching key was deleted
     */
    public boolean deleteIfValueEquals(String key, String expectedValue) {
        requireCommandBudget();
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then "
                        + "return redis.call('DEL', KEYS[1]); end; return 0",
                Long.class);
        Long deleted = redisTemplate.execute(script, List.of(key), expectedValue);
        return Long.valueOf(1L).equals(deleted);
    }

    /**
     * 获取Redis中指定键的值并转换为指定类型的对象
     *
     * @param key 键
     * @param clazz 值类型
     * @return 内容
     */
    public <T> T get(String key, Class<T> clazz) {
        requireCommandBudget();
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
        requireCommandBudget();
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
        requireCommandBudget();
        redisTemplate.opsForValue().set(key, value, duration);
    }

    /**
     * 仅当键不存在时设置键值对并指定有效期。
     *
     * @param key 键
     * @param value 值
     * @param duration 有效期
     * @return 是否设置成功
     */
    public boolean setIfAbsent(String key, String value, Duration duration) {
        requireCommandBudget();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, duration));
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
        requireCommandBudget();
        redisTemplate.opsForValue().set(key, value, timeout, timeUnit);
    }

    /**
     * 删除Redis中的指定键
     *
     * @param key 健
     * @return 结果
     */
    public Boolean delete(String key) {
        requireCommandBudget();
        return redisTemplate.delete(key);
    }

    /**
     * 批量删除Redis中的指定键
     *
     * @param key 健列表
     * @return 结果
     */
    public Long delete(Collection<String> key) {
        requireCommandBudget();
        return redisTemplate.delete(key);
    }

    /**
     * 根据表达式获取匹配的所有key
     *
     * @param pattern 表达式
     * @return key列表
     */
    public Set<String> keys(String pattern) {
        requireCommandBudget();
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
        requireCommandBudget();
        return redisTemplate.execute(action);
    }

    private void requireCommandBudget() {
        RedisRequestBudget.requireCommandBudget();
    }

    /**
     * 订阅频道，监听特定会话的信号
     *
     * @param sessionId 会话ID
     * @return 消息流
     */
    public Flux<String> subscribeToChannel(String sessionId) {
        String channel = "channel:" + sessionId;
        return Flux.defer(() -> {
            requireCommandBudget();
            return reactiveRedisTemplate.listenToChannel(channel);
        })
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
        return Mono.defer(() -> {
            requireCommandBudget();
            return reactiveRedisTemplate.convertAndSend(channel, "SIGNAL");
        })
                .doOnSuccess(count ->
                        log.info("信号已发布到 {} 个订阅者", count));
    }
}
