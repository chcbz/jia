package cn.jia.core.lock.impl;

import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnClass(RedissonClient.class)
public class RedissonDistributedLock implements IDistributedLock {

    @Resource
    private RedissonClient redissonClient;
    /**
     * 统一前缀
     */
    @Value("${redisson.lock.prefix:jia-lock}")
    private String prefix;

    @Override
    public ILock lock(String key) {
        return this.lock(key, 0L, TimeUnit.SECONDS, false);
    }

    @Override
    public ILock lock(String key, long lockTime, TimeUnit unit, boolean fair) {
        RLock lock = getLock(key, fair);
        // 获取锁,失败一直等待,直到获取锁,不支持自动续期
        if (lockTime > 0L) {
            lock.lock(lockTime, unit);
        } else {
            // 具有Watch Dog 自动延期机制 默认续30s 每隔30/3=10 秒续到30s
            lock.lock();
        }
        return new ILock(lock, this);
    }

    @Override
    public ILock tryLock(String key, long tryTime) throws Exception {
        return this.tryLock(key, tryTime, 0L, TimeUnit.SECONDS, false);
    }

    @Override
    public ILock tryLock(String key, long tryTime, long lockTime, TimeUnit unit, boolean fair) throws Exception {
        RLock lock = getLock(key, fair);
        boolean lockAcquired;
        // 尝试获取锁，获取不到超时异常,不支持自动续期
        if (lockTime > 0L) {
            lockAcquired = lock.tryLock(tryTime, lockTime, unit);
        } else {
            // 具有Watch Dog 自动延期机制 默认续30s 每隔30/3=10 秒续到30s
            lockAcquired = lock.tryLock(tryTime, unit);
        }
        if (lockAcquired) {
            return new ILock(lock, this);
        }
        return null;
    }

    /**
     * 获取锁
     *
     * @param key  键
     * @param fair 是否公平锁
     * @return 锁对象
     */
    private RLock getLock(String key, boolean fair) {
        RLock lock;
        String lockKey = prefix + ":" + key;
        if (fair) {
            // 获取公平锁
            lock = redissonClient.getFairLock(lockKey);
        } else {
            // 获取普通锁
            lock = redissonClient.getLock(lockKey);
        }
        return lock;
    }

    @Override
    public void unLock(Object lock) {
        if (!(lock instanceof RLock rLock)) {
            throw new IllegalArgumentException("Invalid lock object");
        }
        if (rLock.isLocked()) {
            try {
                rLock.unlock();
            } catch (IllegalMonitorStateException e) {
                log.error("释放分布式锁异常", e);
            }
        }
    }
}