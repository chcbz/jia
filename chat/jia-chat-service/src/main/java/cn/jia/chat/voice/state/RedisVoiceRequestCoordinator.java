package cn.jia.chat.voice.state;

import cn.jia.chat.voice.config.VoiceSpeechProperties;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * STT uses an atomic pre-materialization admission followed by an admitted idempotency begin;
 * TTS retains the single atomic begin path. Every key shares one Redis cluster hash tag.
 */
public final class RedisVoiceRequestCoordinator implements VoiceRequestCoordinator {
    static final long SUCCEEDED_TTL_MS = Duration.ofMinutes(10).toMillis();
    static final long FAILED_KNOWN_TTL_MS = Duration.ofMinutes(2).toMillis();
    static final long FAILED_UNKNOWN_TTL_MS = Duration.ofMinutes(10).toMillis();
    private static final long IN_PROGRESS_TTL_MS = Duration.ofMinutes(10).toMillis();

    private static final DefaultRedisScript<List> ADMIT_SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
            local minute = tonumber(redis.call('GET', KEYS[1]) or '0')
            local hour = tonumber(redis.call('GET', KEYS[2]) or '0')
            if minute >= tonumber(ARGV[2]) or hour >= tonumber(ARGV[3]) then
              return {'RATE_LIMITED'}
            end
            if redis.call('ZCARD', KEYS[3]) >= 1
                or redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[4]) then
              return {'CONCURRENCY_LIMITED'}
            end
            local minuteNext = redis.call('INCR', KEYS[1])
            if minuteNext == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end
            local hourNext = redis.call('INCR', KEYS[2])
            if hourNext == 1 then redis.call('PEXPIRE', KEYS[2], 3600000) end
            local leaseExpiry = now + tonumber(ARGV[5])
            redis.call('ZADD', KEYS[3], leaseExpiry, ARGV[1])
            redis.call('PEXPIRE', KEYS[3], ARGV[5])
            redis.call('ZADD', KEYS[4], leaseExpiry, ARGV[1])
            redis.call('PEXPIRE', KEYS[4], ARGV[5])
            return {'ADMITTED'}
            """, List.class);

    private static final DefaultRedisScript<List> BEGIN_ADMITTED_SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local identityExpiry = redis.call('ZSCORE', KEYS[2], ARGV[2])
            local globalExpiry = redis.call('ZSCORE', KEYS[3], ARGV[2])
            if not identityExpiry or tonumber(identityExpiry) <= now
                or not globalExpiry or tonumber(globalExpiry) <= now then
              return {'ADMISSION_INVALID'}
            end
            local digest = redis.call('HGET', KEYS[1], 'digest')
            if digest then
              if digest ~= ARGV[1] then return {'IDEMPOTENCY_CONFLICT'} end
              local state = redis.call('HGET', KEYS[1], 'state')
              if state == 'IN_PROGRESS' then
                local stateLease = redis.call('HGET', KEYS[1], 'lease')
                local stateIdentityExpiry = stateLease and redis.call('ZSCORE', KEYS[2], stateLease) or false
                local stateGlobalExpiry = stateLease and redis.call('ZSCORE', KEYS[3], stateLease) or false
                local stateIdentityActive = stateIdentityExpiry and tonumber(stateIdentityExpiry) > now
                local stateGlobalActive = stateGlobalExpiry and tonumber(stateGlobalExpiry) > now
                if not stateIdentityActive and not stateGlobalActive then
                  redis.call('HSET', KEYS[1], 'state', 'FAILED_UNKNOWN', 'payload', '',
                             'contentType', '')
                  redis.call('PEXPIRE', KEYS[1], ARGV[4])
                  if stateLease then
                    redis.call('ZREM', KEYS[2], stateLease)
                    redis.call('ZREM', KEYS[3], stateLease)
                  end
                  return {'RESULT_UNKNOWN'}
                end
                return {'IN_PROGRESS'}
              end
              if state == 'FAILED_UNKNOWN' then return {'RESULT_UNKNOWN'} end
              if state == 'FAILED_KNOWN' then return {'FAILED_KNOWN'} end
              if state == 'SUCCEEDED' then
                return {'REPLAY', redis.call('HGET', KEYS[1], 'payload') or '',
                        redis.call('HGET', KEYS[1], 'contentType') or ''}
              end
              return {'RESULT_UNKNOWN'}
            end
            redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'state', 'IN_PROGRESS',
                       'lease', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {'RESERVED'}
            """, List.class);

    private static final DefaultRedisScript<List> BEGIN_SCRIPT = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local digest = redis.call('HGET', KEYS[1], 'digest')
            if digest then
              if digest ~= ARGV[1] then return {'IDEMPOTENCY_CONFLICT'} end
              local state = redis.call('HGET', KEYS[1], 'state')
              if state == 'IN_PROGRESS' then
                local lease = redis.call('HGET', KEYS[1], 'lease')
                local identityExpiry = lease and redis.call('ZSCORE', KEYS[4], lease) or false
                local globalExpiry = lease and redis.call('ZSCORE', KEYS[5], lease) or false
                local identityActive = identityExpiry and tonumber(identityExpiry) > now
                local globalActive = globalExpiry and tonumber(globalExpiry) > now
                if not identityActive and not globalActive then
                  redis.call('HSET', KEYS[1], 'state', 'FAILED_UNKNOWN', 'payload', '',
                             'contentType', '')
                  redis.call('PEXPIRE', KEYS[1], ARGV[8])
                  if lease then
                    redis.call('ZREM', KEYS[4], lease)
                    redis.call('ZREM', KEYS[5], lease)
                  end
                  return {'RESULT_UNKNOWN'}
                end
                return {'IN_PROGRESS'}
              end
              if state == 'FAILED_UNKNOWN' then return {'RESULT_UNKNOWN'} end
              if state == 'FAILED_KNOWN' then return {'FAILED_KNOWN'} end
              if state == 'SUCCEEDED' then
                return {'REPLAY', redis.call('HGET', KEYS[1], 'payload') or '',
                        redis.call('HGET', KEYS[1], 'contentType') or ''}
              end
              return {'RESULT_UNKNOWN'}
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
            redis.call('ZREMRANGEBYSCORE', KEYS[5], '-inf', now)
            local minute = tonumber(redis.call('GET', KEYS[2]) or '0')
            local hour = tonumber(redis.call('GET', KEYS[3]) or '0')
            if minute >= tonumber(ARGV[3]) or hour >= tonumber(ARGV[4]) then
              return {'RATE_LIMITED'}
            end
            if redis.call('ZCARD', KEYS[4]) >= 1
                or redis.call('ZCARD', KEYS[5]) >= tonumber(ARGV[5]) then
              return {'CONCURRENCY_LIMITED'}
            end
            local minuteNext = redis.call('INCR', KEYS[2])
            if minuteNext == 1 then redis.call('PEXPIRE', KEYS[2], 60000) end
            local hourNext = redis.call('INCR', KEYS[3])
            if hourNext == 1 then redis.call('PEXPIRE', KEYS[3], 3600000) end
            local leaseExpiry = now + tonumber(ARGV[6])
            redis.call('ZADD', KEYS[4], leaseExpiry, ARGV[2])
            redis.call('PEXPIRE', KEYS[4], ARGV[6])
            redis.call('ZADD', KEYS[5], leaseExpiry, ARGV[2])
            redis.call('PEXPIRE', KEYS[5], ARGV[6])
            redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'state', 'IN_PROGRESS', 'lease', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[7])
            return {'RESERVED'}
            """, List.class);

    private static final DefaultRedisScript<Long> TERMINAL_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'state') ~= 'IN_PROGRESS'
                or redis.call('HGET', KEYS[1], 'digest') ~= ARGV[1]
                or redis.call('HGET', KEYS[1], 'lease') ~= ARGV[2] then
              return 0
            end
            redis.call('HSET', KEYS[1], 'state', ARGV[3], 'payload', ARGV[4],
                       'contentType', ARGV[5])
            redis.call('PEXPIRE', KEYS[1], ARGV[6])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            local lease = redis.call('HGET', KEYS[1], 'lease')
            if lease == ARGV[1] and redis.call('HGET', KEYS[1], 'state') == 'IN_PROGRESS' then
              redis.call('HSET', KEYS[1], 'state', 'FAILED_UNKNOWN', 'payload', '',
                         'contentType', '')
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            local removedIdentity = redis.call('ZREM', KEYS[2], ARGV[1])
            local removedGlobal = redis.call('ZREM', KEYS[3], ARGV[1])
            return removedIdentity + removedGlobal
            """, Long.class);

    private final StringRedisTemplate redis;
    private final VoiceSpeechProperties properties;
    private final VoicePayloadCipher cipher;

    public RedisVoiceRequestCoordinator(
            RedisConnectionFactory connectionFactory,
            VoiceSpeechProperties properties,
            VoicePayloadCipher cipher) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.redis.afterPropertiesSet();
        this.properties = properties;
        this.cipher = cipher;
    }

    RedisVoiceRequestCoordinator(
            StringRedisTemplate redis,
            VoiceSpeechProperties properties,
            VoicePayloadCipher cipher) {
        this.redis = redis;
        this.properties = properties;
        this.cipher = cipher;
    }

    @Override
    public VoiceAdmissionResult admit(
            VoiceOperation operation, String identityScope, String requestId) {
        if (operation == null || identityScope == null || identityScope.isBlank()
                || requestId == null || requestId.isBlank()) {
            throw new VoiceStateUnavailableException();
        }
        String leaseToken = UUID.randomUUID().toString();
        VoiceAdmission admission = new VoiceAdmission(
                operation, identityScope, requestId, leaseToken);
        Keys keys = keys(operation, identityScope, requestId);
        long leaseTtl = leaseTtl();
        try {
            List<?> response = redis.execute(ADMIT_SCRIPT, keys.admissionKeys(),
                    leaseToken,
                    Integer.toString(properties.getPerMinute()),
                    Integer.toString(properties.getPerHour()),
                    Integer.toString(properties.getGlobalConcurrency()),
                    Long.toString(leaseTtl));
            if (response == null || response.isEmpty()) {
                releaseAfterUncertainAdmission(admission);
                throw new VoiceStateUnavailableException();
            }
            return switch (String.valueOf(response.get(0))) {
                case "ADMITTED" -> VoiceAdmissionResult.admitted(admission);
                case "RATE_LIMITED" -> VoiceAdmissionResult.outcome(
                        VoiceAdmissionResult.Outcome.RATE_LIMITED);
                case "CONCURRENCY_LIMITED" -> VoiceAdmissionResult.outcome(
                        VoiceAdmissionResult.Outcome.CONCURRENCY_LIMITED);
                default -> {
                    releaseAfterUncertainAdmission(admission);
                    throw new VoiceStateUnavailableException();
                }
            };
        } catch (VoiceStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            releaseAfterUncertainAdmission(admission);
            throw new VoiceStateUnavailableException();
        }
    }

    @Override
    public VoiceBeginResult begin(VoiceAdmission admission, String digest) {
        if (admission == null || admission.operation() == null
                || admission.identityScope() == null || admission.identityScope().isBlank()
                || admission.requestId() == null || admission.requestId().isBlank()
                || admission.leaseToken() == null || admission.leaseToken().isBlank()
                || digest == null || digest.isBlank()) {
            throw new VoiceStateUnavailableException();
        }
        Keys keys = keys(
                admission.operation(), admission.identityScope(), admission.requestId());
        try {
            List<?> response = redis.execute(BEGIN_ADMITTED_SCRIPT, keys.admittedBeginKeys(),
                    digest, admission.leaseToken(), Long.toString(IN_PROGRESS_TTL_MS),
                    Long.toString(FAILED_UNKNOWN_TTL_MS));
            if (response == null || response.isEmpty()) {
                throw new VoiceStateUnavailableException();
            }
            String outcome = String.valueOf(response.get(0));
            return switch (outcome) {
                case "RESERVED" -> VoiceBeginResult.reserved(new VoiceReservation(
                        admission.operation(), admission.identityScope(), admission.requestId(),
                        digest, admission.leaseToken()));
                case "REPLAY" -> replay(
                        admission.operation(), admission.identityScope(), admission.requestId(), digest,
                        stringAt(response, 1), stringAt(response, 2));
                case "IN_PROGRESS" -> VoiceBeginResult.outcome(VoiceBeginResult.Outcome.IN_PROGRESS);
                case "IDEMPOTENCY_CONFLICT" -> VoiceBeginResult.outcome(
                        VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT);
                case "RESULT_UNKNOWN" -> VoiceBeginResult.outcome(
                        VoiceBeginResult.Outcome.RESULT_UNKNOWN);
                case "FAILED_KNOWN" -> VoiceBeginResult.outcome(
                        VoiceBeginResult.Outcome.FAILED_KNOWN);
                case "ADMISSION_INVALID" -> throw new VoiceStateUnavailableException();
                default -> throw new VoiceStateUnavailableException();
            };
        } catch (VoiceStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    @Override
    public VoiceBeginResult begin(
            VoiceOperation operation, String identityScope, String requestId, String digest) {
        String leaseToken = UUID.randomUUID().toString();
        Keys keys = keys(operation, identityScope, requestId);
        long leaseTtl = leaseTtl();
        try {
            List<?> response = redis.execute(BEGIN_SCRIPT, keys.beginKeys(),
                    digest, leaseToken,
                    Integer.toString(properties.getPerMinute()),
                    Integer.toString(properties.getPerHour()),
                    Integer.toString(properties.getGlobalConcurrency()),
                    Long.toString(leaseTtl),
                    Long.toString(IN_PROGRESS_TTL_MS),
                    Long.toString(FAILED_UNKNOWN_TTL_MS));
            if (response == null || response.isEmpty()) {
                throw new VoiceStateUnavailableException();
            }
            String outcome = String.valueOf(response.get(0));
            return switch (outcome) {
                case "RESERVED" -> VoiceBeginResult.reserved(new VoiceReservation(
                        operation, identityScope, requestId, digest, leaseToken));
                case "REPLAY" -> replay(
                        operation, identityScope, requestId, digest,
                        stringAt(response, 1), stringAt(response, 2));
                case "IN_PROGRESS" -> VoiceBeginResult.outcome(VoiceBeginResult.Outcome.IN_PROGRESS);
                case "IDEMPOTENCY_CONFLICT" -> VoiceBeginResult.outcome(
                        VoiceBeginResult.Outcome.IDEMPOTENCY_CONFLICT);
                case "RESULT_UNKNOWN" -> VoiceBeginResult.outcome(VoiceBeginResult.Outcome.RESULT_UNKNOWN);
                case "FAILED_KNOWN" -> VoiceBeginResult.outcome(VoiceBeginResult.Outcome.FAILED_KNOWN);
                case "RATE_LIMITED" -> VoiceBeginResult.outcome(VoiceBeginResult.Outcome.RATE_LIMITED);
                case "CONCURRENCY_LIMITED" -> VoiceBeginResult.outcome(
                        VoiceBeginResult.Outcome.CONCURRENCY_LIMITED);
                default -> throw new VoiceStateUnavailableException();
            };
        } catch (VoiceStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    @Override
    public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
        if (reservation == null || result == null
                || !expectedContentType(reservation.operation()).equals(result.contentType())) {
            throw new VoiceStateUnavailableException();
        }
        transition(reservation, "SUCCEEDED", cipher.encrypt(
                        result.payload(), reservation.operation(), reservation.identityScope(),
                        reservation.requestId(), reservation.digest(), result.contentType()),
                result.contentType(), SUCCEEDED_TTL_MS);
    }

    @Override
    public void failKnown(VoiceReservation reservation) {
        transition(reservation, "FAILED_KNOWN", "", "", FAILED_KNOWN_TTL_MS);
    }

    @Override
    public void failUnknown(VoiceReservation reservation) {
        transition(reservation, "FAILED_UNKNOWN", "", "", FAILED_UNKNOWN_TTL_MS);
    }

    @Override
    public void release(VoiceAdmission admission) {
        release(admission.operation(), admission.identityScope(),
                admission.requestId(), admission.leaseToken());
    }

    @Override
    public void release(VoiceReservation reservation) {
        release(reservation.operation(), reservation.identityScope(),
                reservation.requestId(), reservation.leaseToken());
    }

    private void release(
            VoiceOperation operation, String identityScope, String requestId, String leaseToken) {
        Keys keys = keys(operation, identityScope, requestId);
        try {
            redis.execute(RELEASE_SCRIPT,
                    List.of(keys.stateKey(), keys.identityLeases(), keys.globalLeases()),
                    leaseToken, Long.toString(FAILED_UNKNOWN_TTL_MS));
        } catch (RuntimeException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    private void releaseAfterUncertainAdmission(VoiceAdmission admission) {
        try {
            release(admission);
        } catch (VoiceStateUnavailableException ignored) {
            // The bounded lease remains fail-closed and expires if Redis is unavailable.
        }
    }

    private long leaseTtl() {
        return Math.addExact(Math.min(properties.getProviderDeadlineMillis(), 25_000), 30_000);
    }

    private void transition(
            VoiceReservation reservation, String state, String payload,
            String contentType, long ttlMillis) {
        Keys keys = keys(reservation.operation(), reservation.identityScope(), reservation.requestId());
        try {
            Long changed = redis.execute(TERMINAL_SCRIPT, List.of(keys.stateKey()),
                    reservation.digest(), reservation.leaseToken(), state,
                    payload == null ? "" : payload,
                    contentType == null ? "" : contentType,
                    Long.toString(ttlMillis));
            if (changed == null || changed != 1L) {
                throw new VoiceStateUnavailableException();
            }
        } catch (VoiceStateUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VoiceStateUnavailableException();
        }
    }

    private VoiceBeginResult replay(
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest,
            String encryptedPayload,
            String contentType) {
        if (!expectedContentType(operation).equals(contentType)) {
            throw new VoiceStateUnavailableException();
        }
        return VoiceBeginResult.replay(new VoiceCachedResult(
                cipher.decrypt(encryptedPayload, operation, identityScope, requestId, digest, contentType),
                contentType));
    }

    private static String expectedContentType(VoiceOperation operation) {
        if (operation == null) {
            throw new VoiceStateUnavailableException();
        }
        return switch (operation) {
            case TRANSCRIPTION -> "application/json";
            case SYNTHESIS -> "audio/mpeg";
        };
    }

    private static String stringAt(List<?> values, int index) {
        if (values.size() <= index || values.get(index) == null) {
            return "";
        }
        return String.valueOf(values.get(index));
    }

    private static Keys keys(VoiceOperation operation, String identityScope, String requestId) {
        String common = "cyf:{voice}:v1:";
        String operationPrefix = common + operation.namespace() + ":";
        return new Keys(
                operationPrefix + "request:" + identityScope + ":" + requestId,
                common + "rate:minute:" + identityScope,
                common + "rate:hour:" + identityScope,
                common + "leases:identity:" + identityScope,
                common + "leases:global");
    }

    private record Keys(
            String stateKey,
            String minuteRate,
            String hourRate,
            String identityLeases,
            String globalLeases) {
        List<String> admissionKeys() {
            return List.of(minuteRate, hourRate, identityLeases, globalLeases);
        }

        List<String> admittedBeginKeys() {
            return List.of(stateKey, identityLeases, globalLeases);
        }

        List<String> beginKeys() {
            return List.of(stateKey, minuteRate, hourRate, identityLeases, globalLeases);
        }
    }
}
