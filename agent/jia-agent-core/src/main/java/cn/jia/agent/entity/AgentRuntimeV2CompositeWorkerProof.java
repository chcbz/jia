package cn.jia.agent.entity;

import cn.jia.core.tenant.WorkerScope;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Registered process proof; never retains the bearer credential. */
public record AgentRuntimeV2CompositeWorkerProof(WorkerScope scope,String artifactSha256,String credentialSha256) {
    public AgentRuntimeV2CompositeWorkerProof {
        Objects.requireNonNull(scope,"scope");
        if(artifactSha256==null||!artifactSha256.matches("[0-9a-f]{64}")||credentialSha256==null||!credentialSha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("worker proof digest");
    }
    public static AgentRuntimeV2CompositeWorkerProof from(WorkerScope scope,String artifact,String credential){
        if(credential==null||!credential.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("registration credential");
        String value="cyf-deployment-v1\0"+scope.deploymentId()+"\0"+scope.workerGeneration()+"\0"+credential;
        return new AgentRuntimeV2CompositeWorkerProof(scope,artifact,AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(value.getBytes(StandardCharsets.UTF_8))));
    }
}
