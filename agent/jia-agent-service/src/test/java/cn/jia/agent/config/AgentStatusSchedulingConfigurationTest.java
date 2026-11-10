package cn.jia.agent.config;

import cn.jia.agent.service.AgentStatusMonitor;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentStatusSchedulingConfigurationTest {
    @Test void registersLocalRefreshAndClusterTimeoutWithoutLocalTimersOrDiscovery() {
        var monitor=mock(AgentStatusMonitor.class);
        var schedules=mock(CronScheduleService.class);
        var configuration=new AgentStatusSchedulingConfiguration(monitor,schedules,
                new FixedTenantAdmissionPolicy("blue","client"),"node-a","0/10 * * * * *");
        configuration.onApplicationEvent(null);
        verify(schedules).register(new CronSchedule("client",AgentStatusSchedulingConfiguration.CONNECTIONS,
                "node-a","0/10 * * * * *","UTC",CronSchedule.Misfire.COALESCE));
        verify(schedules).register(new CronSchedule("client",AgentStatusSchedulingConfiguration.TIMEOUTS,
                "","0/10 * * * * *","UTC",CronSchedule.Misfire.COALESCE));
        verifyNoInteractions(monitor);
        configuration.agentConnectedPresenceJob().executeScheduled(null);
        verify(monitor).refreshLocallyConnectedAgents();
        verify(monitor,never()).markHeartbeatTimedOutAgentsOffline();
        configuration.agentHeartbeatTimeoutJob().executeScheduled(null);
        verify(monitor).markHeartbeatTimedOutAgentsOffline();
    }
    @Test void bothHandlersAreDiscoverableAndOnlyExplicitlyEnabled() {
        for (boolean enabled : new boolean[]{false,true}) {
            try (var context=new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                        "cyf.task.scheduling.enabled",String.valueOf(enabled),"cyf.task.scheduling.node-id","node-a")));
                context.registerBean(AgentStatusMonitor.class,()->mock(AgentStatusMonitor.class));
                context.registerBean(CronScheduleService.class,()->mock(CronScheduleService.class));
                context.registerBean(FixedTenantAdmissionPolicy.class,()->new FixedTenantAdmissionPolicy("blue",null));
                context.register(AgentStatusSchedulingConfiguration.class);context.refresh();
                var jobs=context.getBeansOfType(ScheduledJobHandler.class).values();
                assertEquals(enabled?2:0,jobs.size());
                if(enabled) assertEquals(java.util.Set.of(AgentStatusSchedulingConfiguration.CONNECTIONS,
                        AgentStatusSchedulingConfiguration.TIMEOUTS),jobs.stream().map(ScheduledJobHandler::getName)
                        .collect(java.util.stream.Collectors.toSet()));
            }
        }
    }
    @Test void nodeIdentityIsRequiredForLocalPresence() {
        assertThrows(IllegalArgumentException.class,()->new AgentStatusSchedulingConfiguration(
                mock(AgentStatusMonitor.class),mock(CronScheduleService.class),
                new FixedTenantAdmissionPolicy("0",null),"","0/10 * * * * *"));
    }
}
