package cn.jia.diagnostics;

import cn.jia.core.diagnostics.StartupTimeline;
import cn.jia.core.diagnostics.StartupTiming;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/** No auto-configuration or production component scan: no DB, messaging or web port. */
class StartupTimelineBootTest {
    @Test void bootUsesTimelineAndRunsOriginalRunnerBeforeReturning() {
        StartupTimeline timeline = new StartupTimeline();
        StartupTiming.use(timeline);
        SpringApplication app = new SpringApplication(Fixture.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setApplicationStartup(timeline);
        app.setRegisterShutdownHook(false);
        try (ConfigurableApplicationContext context = StartupTiming.call("cyf.fixture.application", () ->
                app.run("--spring.main.banner-mode=off", "--spring.config.location=optional:classpath:/startup-fixture-absent.properties"))) {
            assertSame(timeline, context.getApplicationStartup());
            assertTrue(context.getBean(RunnerFixture.class).called);
        } finally {
            StartupTiming.use(null);
            timeline.finish();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean RunnerFixture runnerFixture() { return new RunnerFixture(); }
    }
    static class RunnerFixture implements ApplicationRunner {
        boolean called;
        @Override public void run(org.springframework.boot.ApplicationArguments args) {
            StartupTiming.run("cyf.fixture.runner", () -> called = true);
        }
    }
}
