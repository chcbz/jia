package cn.jia.chat.handler;

import cn.jia.chat.deliberation.InteractionRoute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable U0 runtime capability declaration supplied during authenticated Agent registration. */
final class AgentRuntimeCapabilities {
    private static final Set<String> ROOT_KEYS = Set.of(
            "capabilityContractVersion", "runtimeVersion", "protocolVersions",
            "chatProtocolVersions", "contextSnapshotVersions", "contextEnvelopeVersions",
            "deltaVersions", "dispatchAckTypes", "deliverySemantics", "interactionModes",
            "profiles", "legacyCompatibility", "fastChatEnabled", "appServerEnabled",
            "trueDeltaEnabled");
    private static final Set<String> PROFILE_KEYS = Set.of("CHAT", "INSPECT", "EXECUTE");
    private static final Set<String> CHAT_DISABLED_KEYS = Set.of(
            "supported", "enabled", "strictNoToolsVerified");
    private static final Set<String> CHAT_ENABLED_KEYS = Set.of(
            "supported", "enabled", "strictNoToolsVerified", "toolPolicy");
    private static final Set<String> INSPECT_KEYS = Set.of(
            "supported", "enabled", "strictNoToolsVerified", "unavailableReason");
    private static final Set<String> EXECUTE_KEYS = Set.of("supported", "enabled");
    private static final Set<String> LEGACY_KEYS = Set.of(
            "PRIVATE", "TASK", "nativeStart", "dispatchAckTypes");

    enum Decision { LEGACY_COMPATIBLE, READY, WAITING_DISABLED, UNSUPPORTED }

    private final boolean modern;
    private final String runtimeVersion;
    private final Map<String, Profile> profiles;
    private final LegacyCompatibility legacyCompatibility;
    private final boolean fastChatEnabled;
    private final boolean appServerEnabled;
    private final boolean trueDeltaEnabled;

    private AgentRuntimeCapabilities(boolean modern, String runtimeVersion,
            Map<String, Profile> profiles, LegacyCompatibility legacyCompatibility,
            boolean fastChatEnabled, boolean appServerEnabled, boolean trueDeltaEnabled) {
        this.modern = modern;
        this.runtimeVersion = runtimeVersion;
        this.profiles = Map.copyOf(profiles);
        this.legacyCompatibility = legacyCompatibility;
        this.fastChatEnabled = fastChatEnabled;
        this.appServerEnabled = appServerEnabled;
        this.trueDeltaEnabled = trueDeltaEnabled;
    }

    static AgentRuntimeCapabilities legacy() {
        return new AgentRuntimeCapabilities(false, null, Map.of(),
                new LegacyCompatibility(true, true, true, List.of("chat.dispatch.ack")),
                false, false, false);
    }

    static AgentRuntimeCapabilities parse(Object raw) {
        if (raw == null) return legacy();
        Map<String, Object> root = exactMap(raw, ROOT_KEYS, "runtimeCapabilities");
        if (integer(root.get("capabilityContractVersion"), "capabilityContractVersion") != 1) {
            throw invalid("Unsupported capabilityContractVersion");
        }
        String runtimeVersion = text(root.get("runtimeVersion"), "runtimeVersion", 100);
        exactList(root.get("protocolVersions"), List.of(1), "protocolVersions");
        exactList(root.get("chatProtocolVersions"), List.of(1), "chatProtocolVersions");
        exactList(root.get("contextSnapshotVersions"), List.of(1), "contextSnapshotVersions");
        exactList(root.get("contextEnvelopeVersions"), List.of(2), "contextEnvelopeVersions");
        exactList(root.get("deltaVersions"), List.of(1), "deltaVersions");
        exactList(root.get("dispatchAckTypes"), List.of("chat.dispatch.ack"), "dispatchAckTypes");
        exactList(root.get("deliverySemantics"),
                List.of("AT_LEAST_ONCE_DURABLE_DEDUPE_REQUIRED"), "deliverySemantics");

        Map<String, Object> rawProfiles = exactMap(root.get("profiles"), PROFILE_KEYS, "profiles");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        profiles.put("CHAT", chatProfile(rawProfiles.get("CHAT")));
        profiles.put("INSPECT", inspectProfile(rawProfiles.get("INSPECT")));
        profiles.put("EXECUTE", executeProfile(rawProfiles.get("EXECUTE")));
        Profile chat = profiles.get("CHAT");
        exactList(root.get("interactionModes"), chat.enabled() ? List.of("CHAT") : List.of(),
                "interactionModes");

        Map<String, Object> legacy = exactMap(root.get("legacyCompatibility"), LEGACY_KEYS,
                "legacyCompatibility");
        exactList(legacy.get("dispatchAckTypes"), List.of("chat.dispatch.ack"),
                "legacyCompatibility.dispatchAckTypes");
        LegacyCompatibility compatibility = new LegacyCompatibility(
                bool(legacy.get("PRIVATE"), "legacyCompatibility.PRIVATE"),
                bool(legacy.get("TASK"), "legacyCompatibility.TASK"),
                bool(legacy.get("nativeStart"), "legacyCompatibility.nativeStart"),
                List.of("chat.dispatch.ack"));
        boolean fastChatEnabled = bool(root.get("fastChatEnabled"), "fastChatEnabled");
        boolean appServerEnabled = bool(root.get("appServerEnabled"), "appServerEnabled");
        boolean trueDeltaEnabled = bool(root.get("trueDeltaEnabled"), "trueDeltaEnabled");
        if (chat.enabled() && (!fastChatEnabled || !appServerEnabled)) {
            throw invalid("Enabled CHAT must match enabled Fast CHAT and app-server runtime flags");
        }
        return new AgentRuntimeCapabilities(true, runtimeVersion, profiles, compatibility,
                fastChatEnabled, appServerEnabled, trueDeltaEnabled);
    }

    boolean modern() { return modern; }

    Decision decision(InteractionRoute route) {
        if (!modern) {
            return route == InteractionRoute.CHAT || route == InteractionRoute.CHAT_STATUS
                    ? Decision.LEGACY_COMPATIBLE : Decision.UNSUPPORTED;
        }
        String profileName = profileName(route);
        Profile profile = profiles.get(profileName);
        if (profile == null || !profile.supported()) return Decision.UNSUPPORTED;
        return profile.enabled() ? Decision.READY : Decision.WAITING_DISABLED;
    }

    Map<String, Object> normalizedForReceipt() {
        if (!modern) {
            return Map.of(
                    "capabilityContractVersion", 0,
                    "mode", "legacy-compatible",
                    "legacyCompatibility", legacyCompatibility.asMap());
        }
        Map<String, Object> normalizedProfiles = new LinkedHashMap<>();
        profiles.forEach((name, profile) -> normalizedProfiles.put(name, profile.asMap()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilityContractVersion", 1);
        result.put("runtimeVersion", runtimeVersion);
        result.put("protocolVersions", List.of(1));
        result.put("chatProtocolVersions", List.of(1));
        result.put("contextSnapshotVersions", List.of(1));
        result.put("contextEnvelopeVersions", List.of(2));
        result.put("deltaVersions", List.of(1));
        result.put("dispatchAckTypes", List.of("chat.dispatch.ack"));
        result.put("deliverySemantics", List.of("AT_LEAST_ONCE_DURABLE_DEDUPE_REQUIRED"));
        result.put("interactionModes", profiles.get("CHAT").enabled() ? List.of("CHAT") : List.of());
        result.put("profiles", normalizedProfiles);
        result.put("legacyCompatibility", legacyCompatibility.asMap());
        result.put("fastChatEnabled", fastChatEnabled);
        result.put("appServerEnabled", appServerEnabled);
        result.put("trueDeltaEnabled", trueDeltaEnabled);
        return Map.copyOf(result);
    }

    Map<String, Object> negotiatedProfile(InteractionRoute route, Decision decision) {
        String profileName = profileName(route);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decision.name());
        result.put("profile", profileName);
        result.put("capabilityContractVersion", modern ? 1 : 0);
        if (modern) {
            result.put("runtimeVersion", runtimeVersion);
            result.put("policy", profiles.get(profileName).asMap());
        } else {
            result.put("legacyCompatibility", legacyCompatibility.asMap());
        }
        return Map.copyOf(result);
    }

    private static String profileName(InteractionRoute route) {
        return switch (route) {
            case CHAT, CHAT_STATUS -> "CHAT";
            case INSPECT -> "INSPECT";
            case EXECUTE -> "EXECUTE";
        };
    }

    private static Profile chatProfile(Object raw) {
        if (!(raw instanceof Map<?, ?> untyped)) throw invalid("profiles.CHAT must be an object");
        boolean enabled = bool(untyped.get("enabled"), "profiles.CHAT.enabled");
        Map<String, Object> value = exactMap(raw,
                enabled ? CHAT_ENABLED_KEYS : CHAT_DISABLED_KEYS, "profiles.CHAT");
        boolean supported = bool(value.get("supported"), "profiles.CHAT.supported");
        if (!supported) throw invalid("CHAT must remain supported in the immutable U0 client contract");
        if (bool(value.get("strictNoToolsVerified"), "profiles.CHAT.strictNoToolsVerified")) {
            throw invalid("Strict no-tools proof is not accepted by contract v1");
        }
        String toolPolicy = enabled
                ? text(value.get("toolPolicy"), "profiles.CHAT.toolPolicy", 50) : null;
        if (enabled && !"read-only-constrained".equals(toolPolicy)) {
            throw invalid("Enabled CHAT toolPolicy must be read-only-constrained");
        }
        return new Profile(supported, enabled, toolPolicy, false, null);
    }

    private static Profile inspectProfile(Object raw) {
        Map<String, Object> value = exactMap(raw, INSPECT_KEYS, "profiles.INSPECT");
        boolean supported = bool(value.get("supported"), "profiles.INSPECT.supported");
        boolean enabled = bool(value.get("enabled"), "profiles.INSPECT.enabled");
        boolean strict = bool(value.get("strictNoToolsVerified"),
                "profiles.INSPECT.strictNoToolsVerified");
        String unavailableReason = text(value.get("unavailableReason"),
                "profiles.INSPECT.unavailableReason", 100);
        if (supported || enabled || strict
                || !"fixed-manifest-provider-isolation-not-verified".equals(unavailableReason)) {
            throw invalid("INSPECT remains unavailable in the immutable U0 client contract");
        }
        return new Profile(false, false, null, false, unavailableReason);
    }

    private static Profile executeProfile(Object raw) {
        Map<String, Object> value = exactMap(raw, EXECUTE_KEYS, "profiles.EXECUTE");
        boolean supported = bool(value.get("supported"), "profiles.EXECUTE.supported");
        boolean enabled = bool(value.get("enabled"), "profiles.EXECUTE.enabled");
        if (supported || enabled) throw invalid("EXECUTE is unavailable through chat contract v1");
        return new Profile(false, false, null, false, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exactMap(Object raw, Set<String> exactKeys, String field) {
        if (!(raw instanceof Map<?, ?> map)) throw invalid(field + " must be an object");
        if (!map.keySet().stream().allMatch(String.class::isInstance)) {
            throw invalid(field + " contains a non-string key");
        }
        Map<String, Object> value = (Map<String, Object>) map;
        if (!value.keySet().equals(exactKeys)) throw invalid(field + " keys do not match contract v1");
        return value;
    }

    private static void exactList(Object raw, List<?> expected, String field) {
        if (!(raw instanceof List<?> value) || !value.equals(expected)) {
            throw invalid(field + " does not match contract v1");
        }
    }

    private static boolean bool(Object raw, String field) {
        if (!(raw instanceof Boolean value)) throw invalid(field + " must be boolean");
        return value;
    }

    private static int integer(Object raw, String field) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw invalid(field + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw invalid(field + " is out of range");
        return (int) value;
    }

    private static String text(Object raw, String field, int max) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > max
                || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(field + " must be exact bounded text");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid runtimeCapabilities: " + message);
    }

    private record Profile(boolean supported, boolean enabled, String toolPolicy,
            boolean strictNoToolsVerified, String unavailableReason) {
        Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("supported", supported);
            value.put("enabled", enabled);
            if (toolPolicy != null) value.put("toolPolicy", toolPolicy);
            if (unavailableReason != null) value.put("unavailableReason", unavailableReason);
            if (toolPolicy != null || unavailableReason != null || "CHAT".equals(unavailableReason)) {
                value.put("strictNoToolsVerified", strictNoToolsVerified);
            } else if (!supported && !enabled && unavailableReason == null) {
                return Map.copyOf(value);
            } else {
                value.put("strictNoToolsVerified", strictNoToolsVerified);
            }
            return Map.copyOf(value);
        }
    }

    private record LegacyCompatibility(boolean privateExecution, boolean taskExecution,
            boolean nativeStart, List<String> dispatchAckTypes) {
        Map<String, Object> asMap() {
            return Map.of(
                    "PRIVATE", privateExecution,
                    "TASK", taskExecution,
                    "nativeStart", nativeStart,
                    "dispatchAckTypes", dispatchAckTypes);
        }
    }
}
