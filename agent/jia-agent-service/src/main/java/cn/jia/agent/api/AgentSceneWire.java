package cn.jia.agent.api;
import cn.jia.agent.entity.*;
import java.util.List;

/** Scene transport versions are exact decimals; timestamps remain epoch milliseconds. */
final class AgentSceneWire {
    private AgentSceneWire() { }
    private static String version(Long value) {
        if (value==null || value<0) throw new IllegalArgumentException("Invalid scene version");
        return value.toString();
    }
    record State(String agentId,String personaCode,String behavior,String originRegionId,String targetRegionId,
            String relatedType,String relatedId,String phase,String stateVersion,Long startedAt,Long expectedArrivalAt,Long expiresAt) { }
    static State from(AgentSceneStateDTO v) { return v==null?null:new State(v.getAgentId(),v.getPersonaCode(),v.getBehavior(),v.getOriginRegionId(),v.getTargetRegionId(),v.getRelatedType(),v.getRelatedId(),v.getPhase(),version(v.getStateVersion()),v.getStartedAt(),v.getExpectedArrivalAt(),v.getExpiresAt()); }
    record Snapshot(String sceneId,String sceneVersion,Long generatedAt,List<AgentSceneAgentDTO> agents,List<State> states) { }
    static Snapshot from(AgentSceneSnapshotDTO v) { return new Snapshot(v.getSceneId(),version(v.getSceneVersion()),v.getGeneratedAt(),v.getAgents(),v.getStates().stream().map(AgentSceneWire::from).toList()); }
    record Event(String sceneVersion,String eventType,State state,Long occurredAt) { }
    static Event from(AgentSceneEventDTO v) { return new Event(version(v.getSceneVersion()),v.getEventType(),from(v.getState()),v.getOccurredAt()); }
    record PhaseResult(String reportId,String stateVersion,String result) { }
    static PhaseResult from(AgentScenePhaseResultDTO v) { return new PhaseResult(v.getReportId(),version(v.getStateVersion()),v.getResult()); }
    record PhaseInput(String reportId,String agentId,String stateVersion,String phase,String regionId,Long occurredAt) {
        PhaseInput {
            if (stateVersion==null || !stateVersion.matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid stateVersion");
            Long.parseLong(stateVersion);
        }
        AgentScenePhaseReportDTO internal() { var v=new AgentScenePhaseReportDTO();v.setReportId(reportId);v.setAgentId(agentId);v.setStateVersion(Long.parseLong(stateVersion));v.setPhase(phase);v.setRegionId(regionId);v.setOccurredAt(occurredAt);return v; }
    }
}
