package cn.jia.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentSceneFeatureFlags {
    private final boolean sceneStateEnabled;
    private final boolean sceneEventsEnabled;

    public AgentSceneFeatureFlags(
            @Value("${juyiting.scene-state.enabled:false}") boolean sceneStateEnabled,
            @Value("${juyiting.scene-events.enabled:false}") boolean sceneEventsEnabled) {
        this.sceneStateEnabled = sceneStateEnabled;
        this.sceneEventsEnabled = sceneEventsEnabled;
    }

    public boolean sceneStateEnabled() {
        return sceneStateEnabled;
    }

    public boolean sceneEventsEnabled() {
        return sceneEventsEnabled;
    }
}
