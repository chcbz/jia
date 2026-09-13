package cn.jia.agent.config;

import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activates the dedicated Agent Rabbit topology before publishers and listeners start. */
public final class AgentRabbitTopologyStartup implements SmartLifecycle {
    static final int PHASE = Integer.MAX_VALUE - 200;

    private final AgentRabbitTopologyProvisioner provisioner;
    private final AtomicBoolean running = new AtomicBoolean();

    AgentRabbitTopologyStartup(AgentRabbitTopologyProvisioner provisioner) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
    }

    @Override
    public void start() {
        if (running.get()) {
            return;
        }
        AgentRabbitTopologyReadiness.Snapshot activated = provisioner.activate();
        if (!activated.canonicalTopologyReady()) {
            throw new IllegalStateException("Agent Rabbit topology activation did not become ready");
        }
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
