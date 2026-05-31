package cn.jia.config;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.ProcessEngineConfiguration;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CamundaEngineConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ProcessEngine processEngine(@Value("${spring.datasource.driverClassName}") String driverClassName,
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        StandaloneProcessEngineConfiguration configuration =
                (StandaloneProcessEngineConfiguration) ProcessEngineConfiguration
                        .createStandaloneProcessEngineConfiguration();
        configuration.setJdbcDriver(driverClassName);
        configuration.setJdbcUrl(jdbcUrl);
        configuration.setJdbcUsername(username);
        configuration.setJdbcPassword(password);
        configuration.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        configuration.setHistory(ProcessEngineConfiguration.HISTORY_FULL);
        configuration.setJobExecutorActivate(false);
        configuration.setSkipIsolationLevelCheck(true);
        return configuration.buildProcessEngine();
    }

    @Bean
    @ConditionalOnMissingBean
    public RepositoryService repositoryService(ProcessEngine processEngine) {
        return processEngine.getRepositoryService();
    }

    @Bean
    @ConditionalOnMissingBean
    public RuntimeService runtimeService(ProcessEngine processEngine) {
        return processEngine.getRuntimeService();
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskService taskService(ProcessEngine processEngine) {
        return processEngine.getTaskService();
    }

    @Bean
    @ConditionalOnMissingBean
    public HistoryService historyService(ProcessEngine processEngine) {
        return processEngine.getHistoryService();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdentityService identityService(ProcessEngine processEngine) {
        return processEngine.getIdentityService();
    }
}
