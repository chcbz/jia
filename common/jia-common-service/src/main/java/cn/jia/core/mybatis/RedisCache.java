package cn.jia.core.mybatis;

import cn.jia.core.redis.RedisService;
import cn.jia.core.util.CollectionUtil;
import org.apache.ibatis.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisServerCommands;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 使用Redis来做Mybatis的二级缓存
 * 实现Mybatis的Cache接口
 * @author chcbz
 * @since 2018年4月27日 下午3:15:54
 */
public class RedisCache implements Cache {

    private static final Logger logger = LoggerFactory.getLogger(RedisCache.class);
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final String id; // cache instance id
    private RedisService redisService;
    private static final long EXPIRE_TIME_IN_MINUTES = 30; // redis过期时间（分钟）

    public RedisCache(String id) {
        if (id == null) {
            throw new IllegalArgumentException("Cache instances require an ID");
        }
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Put query result to redis
     * @param key 键
     * @param value 值
     */
    @Override
    public void putObject(Object key, Object value) {
        redisService.set(String.valueOf(key), String.valueOf(value), EXPIRE_TIME_IN_MINUTES, TimeUnit.MINUTES);
        logger.debug("Put query result to redis");
    }

    @Override
    public Object getObject(Object key) {
        try {
            if (key != null) {
                return redisService.get(key.toString());
            }
        } catch (Exception e) {
            logger.error("[RedisCache]getObject error", e);
        }
        return null;
    }

    @Override
    public Object removeObject(Object key) {
        try {
            if (key != null) {
                redisService.delete(key.toString());
            }
        } catch (Exception e) {
            logger.error("[RedisCache]removeObject error", e);
        }
        return null;
    }

    @Override
    public void clear() {
        logger.debug("清空缓存");
        try {
            Set<String> keys = redisService.keys("*:" + this.id + "*");
            if (CollectionUtil.isNotNullOrEmpty(keys)) {
                redisService.delete(keys);
            }
        } catch (Exception e) {
            logger.error("[RedisCache]clear error", e);
        }
    }

    @Override
    public int getSize() {
        Long size = redisService.execute(RedisServerCommands::dbSize);
        assert size != null;
        return size.intValue();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return this.readWriteLock;
    }

}
