package cn.jia.agent.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import java.util.Objects;

/** Activates the dedicated Agent Rabbit topology before the application becomes ready. */
public final class AgentRabbitTopologyStartup implements ApplicationRunner, Ordered {
    private final AgentRabbitTopologyProvisioner provisioner;

    AgentRabbitTopologyStartup(AgentRabbitTopologyProvisioner provisioner) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        AgentRabbitTopologyReadiness.Snapshot activated = provisioner.activate();
        if (!activated.canonicalTopologyReady()) {
            throw new IllegalStateException("Agent Rabbit topology activation did not become ready");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
