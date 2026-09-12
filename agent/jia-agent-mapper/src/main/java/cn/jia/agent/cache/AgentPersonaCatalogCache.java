package cn.jia.agent.cache;

import cn.jia.agent.entity.AgentPersonaEntity;
import jakarta.inject.Named;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded-lifetime cache for immutable persona catalog metadata.
 *
 * <p>The cache key is the byte-exact tenant/client scope plus a catalog generation. Binding,
 * identity, runtime and user-state fields are structurally absent from {@link CatalogEntry} and
 * must be overlaid after every cache read.</p>
 */
@Named
public class AgentPersonaCatalogCache {
    private static final long DEFAULT_TTL_NANOS = Duration.ofMinutes(1).toNanos();
    private static final int LOCK_STRIPES = 64;

    private final ConcurrentHashMap<CacheKey, CatalogSnapshot> snapshots = new ConcurrentHashMap<>();
    private final AtomicLong catalogGeneration = new AtomicLong(1L);
    private final ReentrantLock[] loadLocks = new ReentrantLock[LOCK_STRIPES];
    private final LongSupplier nanoTime;
    private final long ttlNanos;

    public AgentPersonaCatalogCache() {
        this(System::nanoTime, DEFAULT_TTL_NANOS);
    }

    AgentPersonaCatalogCache(LongSupplier nanoTime, long ttlNanos) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (ttlNanos <= 0) {
            throw new IllegalArgumentException("persona catalog cache TTL must be positive");
        }
        this.ttlNanos = ttlNanos;
        for (int index = 0; index < loadLocks.length; index++) {
            loadLocks[index] = new ReentrantLock();
        }
    }

    public CatalogSnapshot get(String tenantId, String clientId,
            Supplier<List<AgentPersonaEntity>> loader) {
        Scope scope = new Scope(tenantId, clientId);
        Objects.requireNonNull(loader, "loader");
        long generation = catalogGeneration.get();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return load(scope, generation, loader, nanoTime.getAsLong());
        }

        CacheKey key = new CacheKey(scope, generation);
        long now = nanoTime.getAsLong();
        CatalogSnapshot cached = snapshots.get(key);
        if (fresh(cached, now)) {
            return cached;
        }

        ReentrantLock lock = loadLocks[Math.floorMod(scope.hashCode(), loadLocks.length)];
        lock.lock();
        try {
            while (true) {
                generation = catalogGeneration.get();
                key = new CacheKey(scope, generation);
                now = nanoTime.getAsLong();
                cached = snapshots.get(key);
                if (fresh(cached, now)) {
                    return cached;
                }
                CatalogSnapshot loaded = load(scope, generation, loader, now);
                if (catalogGeneration.get() != generation) {
                    continue;
                }
                long loadedGeneration = generation;
                snapshots.keySet().removeIf(existing -> existing.scope().equals(scope)
                        && existing.generation() != loadedGeneration);
                snapshots.put(key, loaded);
                return loaded;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Schedule invalidation after commit; rollback deliberately leaves the current generation live. */
    public void invalidateAllAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new IllegalStateException(
                        "persona catalog mutation requires active transaction synchronization");
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateAllNow();
                }
            });
            return;
        }
        invalidateAllNow();
    }

    private void invalidateAllNow() {
        catalogGeneration.incrementAndGet();
        snapshots.clear();
    }

    private CatalogSnapshot load(Scope scope, long generation,
            Supplier<List<AgentPersonaEntity>> loader, long loadedAtNanos) {
        List<AgentPersonaEntity> rows = loader.get();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("persona catalog load returned no active rows");
        }
        List<CatalogEntry> entries = new ArrayList<>(rows.size());
        Set<String> exactCodes = new HashSet<>();
        for (AgentPersonaEntity row : rows) {
            CatalogEntry entry = validateAndCopy(scope, row);
            if (!exactCodes.add(entry.personaCode())) {
                throw new IllegalStateException("persona catalog contains duplicate personaCode");
            }
            entries.add(entry);
        }
        entries.sort(Comparator.comparing(
                        CatalogEntry::rankNo, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(CatalogEntry::personaCode));
        List<CatalogEntry> immutable = List.copyOf(entries);
        return new CatalogSnapshot(version(scope, generation, immutable), immutable,
                loadedAtNanos + ttlNanos);
    }

    private CatalogEntry validateAndCopy(Scope scope, AgentPersonaEntity row) {
        if (row == null || !Boolean.TRUE.equals(row.getActive())
                || !validExact(row.getPersonaCode(), 50)
                || !validExact(row.getName(), 50)
                || !("0".equals(row.getTenantId()) || scope.tenantId().equals(row.getTenantId()))
                || !(row.getClientId() == null || scope.clientId().equals(row.getClientId()))) {
            throw new IllegalStateException("persona catalog row is null, inactive, or outside exact scope");
        }
        return new CatalogEntry(row.getPersonaCode(), row.getRankNo(), row.getStarName(),
                row.getName(), row.getTitle(), row.getAvatar(), row.getVisualConfig(),
                row.getAbilities(), row.getPower(), row.getIntelligence(), row.getLeadership(),
                Boolean.TRUE.equals(row.getSystemAgent()));
    }

    private String version(Scope scope, long generation, List<CatalogEntry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, scope.tenantId());
            update(digest, scope.clientId());
            update(digest, Long.toString(generation));
            for (CatalogEntry entry : entries) {
                update(digest, entry.personaCode());
                update(digest, entry.rankNo());
                update(digest, entry.starName());
                update(digest, entry.name());
                update(digest, entry.title());
                update(digest, entry.avatar());
                update(digest, entry.visualConfig());
                update(digest, entry.abilities());
                update(digest, entry.power());
                update(digest, entry.intelligence());
                update(digest, entry.leadership());
                update(digest, entry.systemAgent());
            }
            return generation + "-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void update(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private boolean fresh(CatalogSnapshot snapshot, long now) {
        return snapshot != null && now - snapshot.expiresAtNanos() < 0;
    }

    private static boolean validExact(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > maxCodePoints
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().allMatch(AgentPersonaCatalogCache::isPadding)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private record Scope(String tenantId, String clientId) {
        private Scope {
            if ("0".equals(tenantId) || !validExact(tenantId, 50) || !validExact(clientId, 50)) {
                throw new IllegalArgumentException("exact non-system persona catalog scope is required");
            }
        }
    }

    private record CacheKey(Scope scope, long generation) { }

    public record CatalogEntry(
            String personaCode,
            Integer rankNo,
            String starName,
            String name,
            String title,
            String avatar,
            String visualConfig,
            String abilities,
            Integer power,
            Integer intelligence,
            Integer leadership,
            boolean systemAgent) { }

    public record CatalogSnapshot(
            String catalogVersion,
            List<CatalogEntry> entries,
            long expiresAtNanos) {
        public CatalogSnapshot {
            Objects.requireNonNull(catalogVersion, "catalogVersion");
            entries = List.copyOf(entries);
        }
    }
}
