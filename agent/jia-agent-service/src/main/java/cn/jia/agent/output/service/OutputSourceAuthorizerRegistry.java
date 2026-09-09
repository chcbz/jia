package cn.jia.agent.output.service;

import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAuthorizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an immutable one-handler-per-source registry and fails startup on ambiguity. */
@Component
public final class OutputSourceAuthorizerRegistry {
    private final Map<String, OutputSourceAuthorizer> authorizers;

    public OutputSourceAuthorizerRegistry(List<OutputSourceAuthorizer> handlers) {
        Map<String, OutputSourceAuthorizer> indexed = new LinkedHashMap<>();
        for (OutputSourceAuthorizer handler : handlers) {
            String type = handler.sourceType();
            if (type == null || type.isBlank() || indexed.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Duplicate or invalid output source authorizer: " + type);
            }
        }
        authorizers = Map.copyOf(indexed);
    }

    public OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceType,
            String sourceId, String producerAgentId) {
        return lockAndAuthorize(tenantId, clientId, sourceType, sourceId,
                producerAgentId, OutputSourceAccessMode.MUTATION);
    }

    public OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceType,
            String sourceId, String producerAgentId, OutputSourceAccessMode accessMode) {
        OutputSourceAuthorizer handler = authorizers.get(sourceType);
        if (handler == null) {
            throw new OutputAuthorizationException("OUTPUT_SOURCE_UNSUPPORTED",
                    "Output source is unsupported");
        }
        OutputSourceAuthorization authorization = accessMode == OutputSourceAccessMode.MUTATION
                ? handler.lockAndAuthorize(tenantId, clientId, sourceId, producerAgentId)
                : handler.lockAndAuthorize(
                        tenantId, clientId, sourceId, producerAgentId, accessMode);
        if (authorization == null
                || (accessMode == OutputSourceAccessMode.MUTATION && !authorization.writable())
                || !tenantId.equals(authorization.tenantId())
                || !clientId.equals(authorization.clientId())
                || !sourceType.equals(authorization.sourceType())
                || !sourceId.equals(authorization.sourceId())
                || !producerAgentId.equals(authorization.producerAgentId())) {
            throw new OutputAuthorizationException("OUTPUT_SOURCE_FORBIDDEN",
                    "Output source authorization was rejected");
        }
        return authorization;
    }
}
