package cn.jia.task.config;

import cn.jia.task.job.JobHandler;
import cn.jia.task.job.JobRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 任务调度自动配置
 * 扫描所有实现了 JobHandler 接口的 Bean 并自动注册到 JobRegistry
 *
 * @author chc
 */
@Slf4j
@Configuration
public class JobAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(JobRegistry.class)
    public JobRegistry jobRegistry() {
        return new JobRegistry();
    }

    /**
     * JobHandler 初始化器
     * 使用 CommandLineRunner 在应用启动完成后（所有 Bean 创建完毕后）自动注册所有 JobHandler
     * 这避免了循环依赖问题，因为此时 JobRegistry 已经创建完成
     */
    @Bean
    @ConditionalOnMissingBean(name = "jobHandlerInitializer")
    public CommandLineRunner jobHandlerInitializer(JobRegistry jobRegistry, ApplicationContext applicationContext) {
        return args -> {
            Map<String, JobHandler> handlers = applicationContext.getBeansOfType(JobHandler.class);
            log.info("Found {} JobHandler implementations", handlers.size());
            
            for (Map.Entry<String, JobHandler> entry : handlers.entrySet()) {
                JobHandler handler = entry.getValue();
                try {
                    jobRegistry.register(handler);
                    log.info("Registered JobHandler: {} (group: {})", handler.getName(), handler.getGroup());
                } catch (Exception e) {
                    log.error("Failed to register JobHandler: {}", handler.getName(), e);
                }
            }
        };
    }
}
