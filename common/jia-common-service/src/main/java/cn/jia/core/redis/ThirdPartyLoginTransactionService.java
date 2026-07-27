package cn.jia.core.redis;

import cn.jia.core.util.JsonUtil;
import cn.jia.core.util.StringUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * Persists the authorization context needed to resume a browser-based third-party login.
 * The opaque state is shared through Redis so the callback does not rely on a browser session.
 */
@Service
public class ThirdPartyLoginTransactionService {
    private static final String KEY_PREFIX = "oauth:third-party-login:";
    private static final String COMPLETED_KEY_PREFIX = KEY_PREFIX + "completed:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(1);
    private static final int STATE_BYTES = 32;

    private final RedisService redisService;
    private final SecureRandom secureRandom = new SecureRandom();

    public ThirdPartyLoginTransactionService(RedisService redisService) {
        this.redisService = redisService;
    }

    public String create(String provider, String redirectUrl) {
        if (StringUtil.isEmpty(provider) || StringUtil.isEmpty(redirectUrl)) {
            throw new IllegalArgumentException("provider and redirectUrl are required");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            String state = nextState();
            Transaction transaction = new Transaction(provider, redirectUrl);
            if (redisService.setIfAbsent(KEY_PREFIX + state, JsonUtil.toJson(transaction), TTL)) {
                return state;
            }
        }
        throw new IllegalStateException("Unable to create third-party login transaction");
    }

    /**
     * Atomically consumes a transaction, preventing callback replay.
     */
    public Transaction consume(String provider, String state) {
        if (StringUtil.isEmpty(provider) || !isValidState(state)) {
            return null;
        }
        String value = redisService.getAndDelete(KEY_PREFIX + state);
        if (StringUtil.isEmpty(value)) {
            return null;
        }
        Transaction transaction = JsonUtil.fromJson(value, Transaction.class);
        if (!isValidTransaction(transaction) || !provider.equals(transaction.getProvider())) {
            return null;
        }
        return transaction;
    }

    /**
     * Remembers a successful callback briefly so WebView/provider duplicate callbacks can
     * resume the already-completed authorization without reusing their authorization code.
     */
    public void markCompleted(String state, Transaction transaction) {
        if (!isValidState(state) || !isValidTransaction(transaction)) {
            throw new IllegalArgumentException("valid state and transaction are required");
        }
        redisService.set(COMPLETED_KEY_PREFIX + state, JsonUtil.toJson(transaction), COMPLETED_TTL);
    }

    /**
     * Returns a short-lived completed transaction for an idempotent duplicate callback.
     * This does not make the third-party code reusable.
     */
    public Transaction findCompleted(String provider, String state) {
        if (StringUtil.isEmpty(provider) || !isValidState(state)) {
            return null;
        }
        Transaction transaction = redisService.get(COMPLETED_KEY_PREFIX + state, Transaction.class);
        return isValidTransaction(transaction) && provider.equals(transaction.getProvider()) ? transaction : null;
    }

    private boolean isValidTransaction(Transaction transaction) {
        return transaction != null && StringUtil.isNotEmpty(transaction.getProvider())
                && StringUtil.isNotEmpty(transaction.getRedirectUrl());
    }

    private String nextState() {
        byte[] bytes = new byte[STATE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean isValidState(String state) {
        return state != null && state.length() == 43 && state.matches("[A-Za-z0-9_-]+$");
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Transaction {
        private String provider;
        private String redirectUrl;
    }
}
