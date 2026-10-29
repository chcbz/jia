package cn.jia.chat.handler;

import cn.jia.chat.deliberation.InteractionRoute;
import org.junit.jupiter.api.Test;

import cn.jia.core.util.JsonUtil;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRuntimeCapabilitiesTest {
    @Test
    @SuppressWarnings("unchecked")
    void immutableClient326b857FixtureParsesAsDisabledChatAndUnsupportedInspect() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/contracts/u0-client-runtime-capabilities-v1.json")) {
            Map<String, Object> fixture = JsonUtil.getMapper().readValue(stream, Map.class);
            AgentRuntimeCapabilities capabilities = AgentRuntimeCapabilities.parse(fixture);
            assertEquals(AgentRuntimeCapabilities.Decision.WAITING_DISABLED,
                    capabilities.decision(InteractionRoute.CHAT));
            assertEquals(AgentRuntimeCapabilities.Decision.UNSUPPORTED,
                    capabilities.decision(InteractionRoute.INSPECT));
            assertEquals(compact(fixture), capabilities.normalizedForReceipt());
        }
    }

    @Test
    void exactV1ParsesAndStrictNoToolsUnverifiedChatRemainsUsable() {
        AgentRuntimeCapabilities capabilities = AgentRuntimeCapabilities.parse(candidate(true, true));
        assertEquals(AgentRuntimeCapabilities.Decision.READY,
                capabilities.decision(InteractionRoute.CHAT));
        @SuppressWarnings("unchecked")
        Map<String, Object> chat = (Map<String, Object>) ((Map<String, Object>)
                capabilities.normalizedForReceipt().get("profiles")).get("CHAT");
        assertFalse((Boolean) chat.get("strictNoToolsVerified"));
        assertEquals("read-only-constrained", chat.get("toolPolicy"));
    }

    @Test
    void disabledModernChatWaitsAndInspectExecuteRemainUnsupported() {
        AgentRuntimeCapabilities capabilities = AgentRuntimeCapabilities.parse(candidate(true, false));
        assertEquals(AgentRuntimeCapabilities.Decision.WAITING_DISABLED,
                capabilities.decision(InteractionRoute.CHAT));
        assertEquals(AgentRuntimeCapabilities.Decision.UNSUPPORTED,
                capabilities.decision(InteractionRoute.INSPECT));
        assertEquals(AgentRuntimeCapabilities.Decision.UNSUPPORTED,
                capabilities.decision(InteractionRoute.EXECUTE));
    }

    @Test
    void malformedOrBroaderClaimsFailClosed() {
        Map<String, Object> noTools = candidate(true, true);
        @SuppressWarnings("unchecked") Map<String, Object> profiles =
                new LinkedHashMap<>((Map<String, Object>) noTools.get("profiles"));
        @SuppressWarnings("unchecked") Map<String, Object> chat =
                new LinkedHashMap<>((Map<String, Object>) profiles.get("CHAT"));
        chat.put("toolPolicy", "no-tools"); profiles.put("CHAT", chat); noTools.put("profiles", profiles);
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(noTools));

        Map<String, Object> unknown = candidate(true, false); unknown.put("unknown", false);
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(unknown));

        Map<String, Object> impossible = candidate(false, true);
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(impossible));

        Map<String, Object> wrongVersion = candidate(true, false);
        wrongVersion.put("capabilityContractVersion", "1");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(wrongVersion));
    }

    @Test
    void compactV1HasSameRoutingAsExpandedV1AndDoesNotEchoRetiredConstants() {
        for (boolean enabled : new boolean[] {true, false}) {
            var expanded = AgentRuntimeCapabilities.parse(candidate(true, enabled));
            var reduced = AgentRuntimeCapabilities.parse(compact(candidate(true, enabled)));
            for (var route : InteractionRoute.values()) assertEquals(expanded.decision(route), reduced.decision(route));
            assertEquals(expanded.normalizedForReceipt(), reduced.normalizedForReceipt());
            assertFalse(reduced.normalizedForReceipt().containsKey("legacyCompatibility"));
            assertFalse(reduced.normalizedForReceipt().containsKey("protocolVersions"));
        }
        var missing = compact(candidate(true, true)); missing.remove("profiles");
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(missing));
        var conflict = candidate(true, true); conflict.put("protocolVersions", java.util.List.of(99));
        assertThrows(IllegalArgumentException.class, () -> AgentRuntimeCapabilities.parse(conflict));
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactRuntimeCompactRegistrationRetainsMaterialsNativeExecutionAndSkillCatalog() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/contracts/registration-compact-v1.json")) {
            Map<String, Object> envelope = JsonUtil.getMapper().readValue(stream, Map.class);
            assertEquals("agent.register", envelope.get("messageType"));
            Map<String, Object> data = (Map<String, Object>) envelope.get("data");
            var runtime = AgentRuntimeCapabilities.parse(data.get("runtimeCapabilities"));
            assertEquals(AgentRuntimeCapabilities.Decision.WAITING_DISABLED, runtime.decision(InteractionRoute.CHAT));
            var typed = TypedDeliberationDeclaration.parse(data.get("typedDeliberation"));
            assertEquals(TypedDeliberationDeclaration.State.UNAVAILABLE, typed.state());
            var material = (Map<String, Object>) typed.frozenReceipt().get("materialsInput");
            assertEquals(9, ((java.util.List<?>) material.get("supportedInputs")).size());
            var nativeExecution = (Map<String, Object>) typed.frozenReceipt().get("nativeExecution");
            assertEquals(java.util.List.of("NATIVE_SKILL_SCRIPT_V1"), nativeExecution.get("supportedToolPolicyRefs"));
            var skills = SkillExecutionDeclaration.parse(data.get("skillExecution"));
            org.junit.jupiter.api.Assertions.assertNotNull(skills);
            assertEquals(4, skills.skills().size());
            assertFalse(data.containsKey("inputCarriers"));
        }
    }

    private static Map<String, Object> compact(Map<String, Object> expanded) {
        var compact = new LinkedHashMap<>(expanded);
        compact.keySet().retainAll(java.util.Set.of("capabilityContractVersion", "runtimeVersion", "profiles",
                "fastChatEnabled", "appServerEnabled", "trueDeltaEnabled"));
        return compact;
    }

    static Map<String, Object> candidate(boolean supported, boolean enabled) {
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("supported", supported);
        chat.put("enabled", enabled);
        chat.put("strictNoToolsVerified", false);
        if (enabled) chat.put("toolPolicy", "read-only-constrained");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capabilityContractVersion", 1);
        result.put("runtimeVersion", "juyiting-fast-context-runtime-v2");
        result.put("protocolVersions", java.util.List.of(1));
        result.put("chatProtocolVersions", java.util.List.of(1));
        result.put("contextSnapshotVersions", java.util.List.of(1));
        result.put("contextEnvelopeVersions", java.util.List.of(2));
        result.put("deltaVersions", java.util.List.of(1));
        result.put("dispatchAckTypes", java.util.List.of("chat.dispatch.ack"));
        result.put("deliverySemantics", java.util.List.of("AT_LEAST_ONCE_DURABLE_DEDUPE_REQUIRED"));
        result.put("interactionModes", enabled ? java.util.List.of("CHAT") : java.util.List.of());
        result.put("profiles", Map.of(
                "CHAT", chat,
                "INSPECT", Map.of("supported", false, "enabled", false,
                        "strictNoToolsVerified", false,
                        "unavailableReason", "fixed-manifest-provider-isolation-not-verified"),
                "EXECUTE", Map.of("supported", false, "enabled", false)));
        result.put("legacyCompatibility", Map.of(
                "PRIVATE", true, "TASK", true, "nativeStart", false,
                "dispatchAckTypes", java.util.List.of("chat.dispatch.ack")));
        result.put("fastChatEnabled", enabled);
        result.put("appServerEnabled", enabled);
        result.put("trueDeltaEnabled", false);
        return result;
    }
}
