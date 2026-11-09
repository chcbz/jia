package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

/** No business payload in Redis: only a client-partition hint to reread durable work. */
public final class RedisScheduleWakeup implements Consumer<ScheduleKey> {
    private static final Logger log = LoggerFactory.getLogger(RedisScheduleWakeup.class);
    private final RedissonClient redis;
    private final FixedTenantAdmissionPolicy scope;

    public RedisScheduleWakeup(RedissonClient redis, FixedTenantAdmissionPolicy scope) {
        this.redis = redis; this.scope = scope;
    }
    @Override public void accept(ScheduleKey key) {
        redis.getTopic(topic(scope.businessTenantId(), key.targetNode()), StringCodec.INSTANCE)
                .publishAsync(key.clientId()).whenComplete((receivers, failure) -> {
                    if (failure != null) log.warn("Schedule notification unavailable; DB recovery remains authoritative", failure);
                });
    }
    static String topic(String tenant, String node) { return "cyf:schedule:wakeup:" + encode(tenant) + ":" + encode(node); }
    static String lock(String tenant, String client, String node) {
        return "cyf:schedule:owner:" + encode(tenant) + ":" + (client == null ? "*" : encode(client)) + ":" + encode(node);
    }
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
