package cn.jia;

import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.core.diagnostics.StartupTimeline;
import cn.jia.core.diagnostics.StartupTiming;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 启动类
 *
 * @author chcbz
 */
@SpringBootApplication
@Import(AgentRuntimeSecurityConfiguration.class)
@EnableTransactionManagement
@EnableAsync
@EnableScheduling
@MapperScan({"cn.jia.*.mapper"})
public class JiaApplication {

	public static void main(String[] args) {
		if (!StartupTiming.requested(args)) {
            SpringApplication.run(JiaApplication.class, args);
            return;
        }
        StartupTimeline timeline = new StartupTimeline();
        StartupTiming.use(timeline);
        SpringApplication application = new SpringApplication(JiaApplication.class);
        application.setApplicationStartup(timeline);
        try {
            StartupTiming.run("cyf.application.run", () -> application.run(args));
        } finally {
            StartupTiming.use(null);
            timeline.finish();
        }
	}
}
