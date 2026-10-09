package cn.jia;

import cn.jia.chat.deliberation.ChatDeliberationMapper;
import cn.jia.agent.config.AgentRuntimeSecurityConfiguration;
import cn.jia.core.diagnostics.StartupTimeline;
import cn.jia.starter.LocalTestAreaRegistrationPreflight;
import cn.jia.core.diagnostics.StartupTiming;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
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
@MapperScans({
        @MapperScan({"cn.jia.*.mapper"}),
        @MapperScan(basePackageClasses = ChatDeliberationMapper.class, annotationClass = Mapper.class)
})
public class JiaApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(JiaApplication.class);
		application.addInitializers(new LocalTestAreaRegistrationPreflight.BeforeRefresh());
		if (!StartupTiming.requested(args)) {
            application.run(args);
            return;
        }
        StartupTimeline timeline = new StartupTimeline();
        StartupTiming.use(timeline);
        application.setApplicationStartup(timeline);
        try {
            StartupTiming.run("cyf.application.run", () -> application.run(args));
        } finally {
            StartupTiming.use(null);
            timeline.finish();
        }
	}
}
