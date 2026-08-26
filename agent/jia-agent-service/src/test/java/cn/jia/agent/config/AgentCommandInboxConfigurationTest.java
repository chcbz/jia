package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.service.AgentCommandInboxService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentCommandInboxConfigurationTest {
    private static final String[] ENABLED = {
            "agent.command-outbox.enabled=true",
            "agent.rabbit-topology.enabled=true",
            "agent.rabbit-consume.enabled=true",
            "agent.rabbit-broker.host=isolated.invalid",
            "agent.rabbit-broker.port=35672",
            "agent.rabbit-broker.username=d07-user",
            "agent.rabbit-broker.password=d07-pass",
            "agent.rabbit-broker.virtual-host=/d07"
    };

    @Test
    void disabledConsumeRegistersNoInboxServiceAndNeedsNoDao() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        AgentRabbitSafetyConfiguration.class,
                        AgentCommandInboxConfiguration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("agentCommandInboxService"));
                });
    }

    @Test
    void consumeWithoutOutboxOrTopologyFailsBeforeInboxOrDaoRegistration() {
        for (String[] invalid : new String[][] {
                {
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-consume.enabled=true"
                },
                {
                        "agent.command-outbox.enabled=true",
                        "agent.rabbit-consume.enabled=true"
                }
        }) {
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            AgentRabbitSafetyConfiguration.class,
                            AgentCommandInboxConfiguration.class)
                    .withPropertyValues(concat(invalid, broker()))
                    .run(context -> {
                        Throwable failure = context.getStartupFailure();
                        assertNotNull(failure);
                        String messages = messages(failure);
                        assertTrue(messages.contains(
                                "rabbit consume requires command outbox and topology"), messages);
                        assertFalse(messages.contains("AgentCommandInboxDao"), messages);
                    });
        }
    }

    @Test
    void enabledConsumeRegistersExactlyOneRealService() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        AgentRabbitSafetyConfiguration.class,
                        AgentCommandInboxConfiguration.class,
                        Dependencies.class)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("agentCommandInboxService"));
                    assertNotNull(context.getBean(AgentCommandInboxService.class));
                });
    }

    @Test
    void enabledConsumeWithMissingWiringFailsStartupRatherThanNoOp() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        AgentRabbitSafetyConfiguration.class,
                        AgentCommandInboxConfiguration.class)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    assertTrue(messages(failure).contains("AgentCommandInboxDao"), messages(failure));
                });
    }

    @Test
    void booleanBinderAliasOnActivatesTheConditionalBean() {
        String[] values = ENABLED.clone();
        values[2] = "agent.rabbit-consume.enabled=on";
        new ApplicationContextRunner()
                .withUserConfiguration(
                        AgentRabbitSafetyConfiguration.class,
                        AgentCommandInboxConfiguration.class,
                        Dependencies.class)
                .withPropertyValues(values)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("agentCommandInboxService"));
                });
    }

    private static String[] broker() {
        return new String[] {
                "agent.rabbit-broker.host=isolated.invalid",
                "agent.rabbit-broker.port=35672",
                "agent.rabbit-broker.username=d07-user",
                "agent.rabbit-broker.password=d07-pass",
                "agent.rabbit-broker.virtual-host=/d07"
        };
    }

    private static String[] concat(String[] first, String[] second) {
        String[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String messages(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) result.append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        AgentCommandInboxDao agentCommandInboxDao() {
            return mock(AgentCommandInboxDao.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:d07_config");
            return new DataSourceTransactionManager(source);
        }
    }
}
