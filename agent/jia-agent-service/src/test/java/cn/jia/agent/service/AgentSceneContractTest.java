package cn.jia.agent.service;

import cn.jia.agent.common.AgentSceneConstants;
import cn.jia.agent.entity.AgentSceneAgentDTO;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSceneContractTest {
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "x", "y", "path", "frame", "coordinates", "credential", "credentials",
            "apiKey", "token", "rawModelResponse", "rawResponse");

    @Test
    void stateContractContainsExactlySemanticStateFields() {
        assertEquals(Set.of(
                "agentId", "personaCode", "behavior", "originRegionId", "targetRegionId",
                "relatedType", "relatedId", "phase", "stateVersion", "startedAt",
                "expectedArrivalAt", "expiresAt"), instanceFieldNames(AgentSceneStateDTO.class));
        assertFalse(instanceFieldNames(AgentSceneStateDTO.class).stream().anyMatch(FORBIDDEN_FIELDS::contains));
    }

    @Test
    void snapshotAndSupportingContractsExposeOnlyApprovedFields() throws Exception {
        assertEquals(Set.of("sceneId", "sceneVersion", "generatedAt", "agents", "states"),
                instanceFieldNames(AgentSceneSnapshotDTO.class));
        assertEquals(Set.of("agentId", "personaCode", "status"), instanceFieldNames(AgentSceneAgentDTO.class));
        ParameterizedType agentsType = (ParameterizedType) AgentSceneSnapshotDTO.class
                .getDeclaredField("agents").getGenericType();
        assertEquals(AgentSceneAgentDTO.class, agentsType.getActualTypeArguments()[0]);
        assertEquals(Set.of("sceneVersion", "eventType", "state", "occurredAt"),
                instanceFieldNames(AgentSceneEventDTO.class));
        assertEquals(Set.of("reportId", "agentId", "stateVersion", "phase", "regionId", "occurredAt"),
                instanceFieldNames(AgentScenePhaseReportDTO.class));
        assertEquals(Set.of("reportId", "stateVersion", "result"),
                instanceFieldNames(AgentScenePhaseResultDTO.class));
        List.of(
                AgentSceneAgentDTO.class, AgentSceneStateDTO.class, AgentSceneSnapshotDTO.class, AgentSceneEventDTO.class,
                AgentScenePhaseReportDTO.class, AgentScenePhaseResultDTO.class)
                .forEach(type -> assertFalse(
                        instanceFieldNames(type).stream().anyMatch(FORBIDDEN_FIELDS::contains), type.getSimpleName()));
    }

    @Test
    void publicTimestampsUseNullableEpochMilliseconds() throws Exception {
        for (String field : List.of("startedAt", "expectedArrivalAt", "expiresAt")) {
            assertEquals(Long.class, AgentSceneStateDTO.class.getDeclaredField(field).getType());
        }
        assertEquals(Long.class, AgentSceneSnapshotDTO.class.getDeclaredField("generatedAt").getType());
        assertEquals(Long.class, AgentSceneEventDTO.class.getDeclaredField("occurredAt").getType());
        assertEquals(Long.class, AgentScenePhaseReportDTO.class.getDeclaredField("occurredAt").getType());
    }

    @Test
    void constantsAreExactAndImmutable() {
        assertEquals("juyiting-main", AgentSceneConstants.SCENE_JUYITING_MAIN);
        assertEquals(Set.of("arrived", "blocked"), AgentSceneConstants.PHASES);
        assertEquals(Set.of("accepted", "ignored_stale", "ignored_duplicate"), AgentSceneConstants.PHASE_RESULTS);
        assertThrows(UnsupportedOperationException.class, () -> AgentSceneConstants.PHASES.add("moving"));
        assertThrows(UnsupportedOperationException.class, () -> AgentSceneConstants.PHASE_RESULTS.add("rejected"));
    }

    @Test
    void serviceContractUsesExactSynchronousAndReactiveSignatures() throws Exception {
        assertEquals(AgentSceneSnapshotDTO.class,
                AgentSceneService.class.getMethod("snapshot", String.class).getReturnType());
        Method events = AgentSceneService.class.getMethod("events", String.class, long.class);
        assertEquals(Flux.class, events.getReturnType());
        ParameterizedType eventsType = (ParameterizedType) events.getGenericReturnType();
        assertEquals(AgentSceneEventDTO.class, eventsType.getActualTypeArguments()[0]);
        assertEquals(AgentScenePhaseResultDTO.class,
                AgentSceneService.class.getMethod("reportPhase", String.class, AgentScenePhaseReportDTO.class)
                        .getReturnType());
        assertEquals(AgentSceneStateDTO.class,
                AgentSceneService.class.getMethod("upsertState", String.class, AgentSceneStateDTO.class)
                        .getReturnType());
        assertEquals(4, AgentSceneService.class.getDeclaredMethods().length);
    }

    @Test
    void serializationContainsSemanticFieldsAndNoSensitiveOrGeometryFields() {
        AgentSceneAgentDTO agent = new AgentSceneAgentDTO();
        agent.setAgentId("agent-songjiang");
        agent.setPersonaCode("songjiang");
        agent.setStatus("online");
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId("agent-songjiang");
        state.setPersonaCode("songjiang");
        state.setTargetRegionId("council-table");
        state.setStateVersion(17L);
        state.setStartedAt(1_752_199_990_000L);
        AgentSceneSnapshotDTO snapshot = new AgentSceneSnapshotDTO();
        snapshot.setSceneId("juyiting-main");
        snapshot.setSceneVersion(128L);
        snapshot.setGeneratedAt(1_752_200_000_000L);
        snapshot.setAgents(List.of(agent));
        snapshot.setStates(List.of(state));

        String json = JsonUtil.toJson(snapshot);
        Map<String, Object> serialized = JsonUtil.jsonToMap(json);
        @SuppressWarnings("unchecked")
        Map<String, Object> serializedAgent = ((List<Map<String, Object>>) serialized.get("agents")).getFirst();

        assertTrue(json.contains("\"targetRegionId\":\"council-table\""));
        assertEquals(Set.of("agentId", "personaCode", "status"), serializedAgent.keySet());
        assertEquals(1_752_200_000_000L, ((Number) serialized.get("generatedAt")).longValue());
        FORBIDDEN_FIELDS.forEach(field -> assertFalse(json.contains("\"" + field + "\""), field));
    }

    @Test
    void snapshotAndEventsDefensivelyIsolateMutableInputsAndOutputs() {
        AgentSceneAgentDTO agent = new AgentSceneAgentDTO();
        agent.setAgentId("agent-songjiang");
        agent.setPersonaCode("songjiang");
        agent.setStatus("online");
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId("agent-songjiang");
        state.setTargetRegionId("council-table");
        List<AgentSceneAgentDTO> agents = new ArrayList<>(List.of(agent));
        List<AgentSceneStateDTO> states = new ArrayList<>(List.of(state));

        AgentSceneSnapshotDTO snapshot = new AgentSceneSnapshotDTO();
        snapshot.setAgents(agents);
        snapshot.setStates(states);
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setState(state);

        agent.setStatus("offline");
        state.setTargetRegionId("main-seat");
        agents.clear();
        states.clear();
        assertEquals("online", snapshot.getAgents().getFirst().getStatus());
        assertEquals("council-table", snapshot.getStates().getFirst().getTargetRegionId());
        assertEquals("council-table", event.getState().getTargetRegionId());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getAgents().add(new AgentSceneAgentDTO()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getStates().add(new AgentSceneStateDTO()));

        snapshot.getAgents().getFirst().setStatus("mutated-return");
        snapshot.getStates().getFirst().setTargetRegionId("mutated-return");
        event.getState().setTargetRegionId("mutated-return");
        assertEquals("online", snapshot.getAgents().getFirst().getStatus());
        assertEquals("council-table", snapshot.getStates().getFirst().getTargetRegionId());
        assertEquals("council-table", event.getState().getTargetRegionId());
    }

    private static Set<String> instanceFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
