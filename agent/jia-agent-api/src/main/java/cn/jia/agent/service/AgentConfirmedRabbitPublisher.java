package cn.jia.agent.service;

import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;

import java.util.Map;

/** Reusable bounded correlated-confirm primitive for exact D04 manifest routes. */
public interface AgentConfirmedRabbitPublisher {
    AgentRabbitPublishResult publish(
            AgentConfirmedPublishRequest request, long confirmTimeoutMillis);

    /** D09 path; implementations must preserve the validated allowlisted broker headers exactly. */
    default AgentRabbitPublishResult publishPreservingHeaders(
            AgentConfirmedPublishRequest request,
            Map<String, Object> preservedHeaders,
            long confirmTimeoutMillis) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null,
                "PRESERVED_HEADERS_UNSUPPORTED");
    }
}
