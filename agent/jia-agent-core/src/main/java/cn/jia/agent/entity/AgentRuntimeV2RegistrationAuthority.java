package cn.jia.agent.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact ordered registration authority evidence frozen at one authorization decision. */
public record AgentRuntimeV2RegistrationAuthority(List<String> fields) {
    public static final int FIELD_COUNT = 50;
    private static final List<Integer> NONNEGATIVE_LONGS = List.of(
            1, 2, 6, 7, 8, 9, 10, 11, 12, 14, 18, 19, 20, 21,
            24, 25, 27, 35, 36, 37, 41, 42, 43, 44, 45, 46);
    private static final List<Integer> BOOLEANS = List.of(26, 38, 39, 47, 48);
    private static final List<Integer> HASHES = List.of(0, 4, 28, 33);
    private static final List<Integer> NULLABLE = List.of(5, 28, 29, 30);

    public AgentRuntimeV2RegistrationAuthority {
        Objects.requireNonNull(fields, "fields");
        if (fields.size() != FIELD_COUNT) {
            throw new IllegalArgumentException("registration authority requires exactly 50 values after its domain label");
        }
        List<String> copy = new ArrayList<>(fields);
        for (int index = 0; index < copy.size(); index++) {
            String value = copy.get(index);
            if (value == null) {
                if (!NULLABLE.contains(index)) {
                    throw new IllegalArgumentException("registration authority field " + index + " is required");
                }
                continue;
            }
            RuntimeV2ExactIdentity.require(value, maximumUtf8Bytes(index), "registrationAuthority[" + index + "]");
        }
        HASHES.forEach(index -> {
            String value = copy.get(index);
            if (value != null && !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("registration authority field " + index + " is not lowercase SHA-256");
            }
        });
        NONNEGATIVE_LONGS.forEach(index -> canonicalLong(copy.get(index), index));
        canonicalNullableLong(copy.get(5), 5);
        canonicalNullableLong(copy.get(29), 29);
        canonicalNullableLong(copy.get(30), 30);
        BOOLEANS.forEach(index -> {
            if (!List.of("0", "1").contains(copy.get(index))) {
                throw new IllegalArgumentException("registration authority field " + index + " is not canonical boolean");
            }
        });
        AgentRuntimeV2RegistrationFingerprints.uuid(copy.get(13), "registrationAuthorityId");
        // This is evidence typing, not admission. DRAINING needs an exact completion
        // authorization in the transaction resolver; ordinary admission still rejects it.
        if (!List.of("ACTIVE", "DRAINING").contains(copy.get(34)) || !"ACTIVE".equals(copy.get(49))) {
            throw new IllegalArgumentException("registration authority requires active deployment and module admission");
        }
        fields = Collections.unmodifiableList(copy);
    }

    public String registrationSubjectSha256() {
        return Objects.requireNonNull(fields.getFirst(), "registrationSubjectSha256");
    }

    private static void canonicalNullableLong(String value, int index) {
        if (value != null) canonicalLong(value, index);
    }

    private static void canonicalLong(String value, int index) {
        if (value == null || !value.matches("0|[1-9][0-9]*")) {
            throw new IllegalArgumentException("registration authority field " + index + " is not canonical non-negative long");
        }
        try {
            Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("registration authority field " + index + " exceeds signed long", exception);
        }
    }

    private static int maximumUtf8Bytes(int index) {
        if (HASHES.contains(index)) return 64;
        if (NONNEGATIVE_LONGS.contains(index) || BOOLEANS.contains(index)
                || List.of(5, 29, 30).contains(index)) return 19;
        return switch (index) {
            case 3 -> 100;
            case 13 -> 36;
            case 15, 22 -> 64;
            case 16, 31 -> 100;
            default -> 200;
        };
    }
}
