package cn.jia.core.redis;

import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RedisService {
    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 获取Redis内容
     *
     * @param key 键
     * @return 内容
     */
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 获取Redis内容
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
     * 设置内容
     *
     * @param key 键
     * @param value 值
     */
    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 设置内容
     *
     * @param key 键
     * @param value 值
     * @param duration 有效期
     */
    public void set(String key, String value, Duration duration) {
        redisTemplate.opsForValue().set(key, value, duration);
    }

    /**
     * 设置内容
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
     * 删除内容
     *
     * @param key 健
     * @return 结果
     */
    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    /**
     * 删除内容
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
     * 执行命令
     *
     * @param action redis命令
     * @return 执行结果
     * @param <T> 返回类型
     */
    public <T> T execute(RedisCallback<T> action) {
        return redisTemplate.execute(action);
    }
}
