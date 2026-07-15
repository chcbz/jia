package cn.jia.agent.service;

import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import reactor.core.publisher.Flux;

public interface AgentSceneService {
    AgentSceneSnapshotDTO snapshot(String sceneId);

    Flux<AgentSceneEventDTO> events(String sceneId, long sinceVersion);

    AgentScenePhaseResultDTO reportPhase(String sceneId, AgentScenePhaseReportDTO request);

    AgentSceneStateDTO upsertState(String sceneId, AgentSceneStateDTO state);
}
