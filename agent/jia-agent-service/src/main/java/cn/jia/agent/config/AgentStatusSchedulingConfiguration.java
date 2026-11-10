package cn.jia.agent.config;

import cn.jia.agent.service.AgentStatusMonitor;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Local connections refresh shared presence; one cluster scan reconciles persisted heartbeat age. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public class AgentStatusSchedulingConfiguration implements ApplicationListener<ApplicationReadyEvent> {
    public static final String CONNECTIONS = "agent.status-connections";
    public static final String TIMEOUTS = "agent.status-timeouts";
    private final AgentStatusMonitor monitor;
    private final CronScheduleService schedules;
    private final CronSchedule connections;
    private final CronSchedule timeouts;

    public AgentStatusSchedulingConfiguration(AgentStatusMonitor monitor, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, @Value("${cyf.task.scheduling.node-id}") String node,
            @Value("${jia.agent.status.scan-cron:0/10 * * * * *}") String cron) {
        if (node == null || node.isBlank()) throw new IllegalArgumentException("Presence refresh requires a scheduler node identity");
        this.monitor = monitor;
        this.schedules = schedules;
        String client = scope.clientId() == null ? "" : scope.clientId();
        connections = new CronSchedule(client, CONNECTIONS, node, cron, "UTC", CronSchedule.Misfire.COALESCE);
        timeouts = new CronSchedule(client, TIMEOUTS, "", cron, "UTC", CronSchedule.Misfire.COALESCE);
    }

    @Bean public ScheduledJobHandler agentConnectedPresenceJob() {
        return new StatusJob(CONNECTIONS, monitor::refreshLocallyConnectedAgents);
    }
    @Bean public ScheduledJobHandler agentHeartbeatTimeoutJob() {
        return new StatusJob(TIMEOUTS, monitor::markHeartbeatTimedOutAgentsOffline);
    }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        schedules.register(connections);
        schedules.register(timeouts);
    }
    private record StatusJob(String name, Runnable action) implements ScheduledJobHandler {
        @Override public String getName() { return name; }
        @Override public void executeScheduled(ScheduledInvocation invocation) { action.run(); }
    }
}
