package cn.jia.chat.archive.config;

import cn.jia.core.diagnostics.StartupTiming;
import cn.jia.chat.archive.service.ArchiveQuestionWorker;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 110)
public class ArchiveQuestionBootstrap implements ApplicationRunner {
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveQuestionSchemaInitializer schemaInitializer;
    private final ArchiveQuestionWorker worker;

    public ArchiveQuestionBootstrap(ArchiveQuestionAccessPolicy accessPolicy,
                                    ArchiveQuestionSchemaInitializer schemaInitializer,
                                    ArchiveQuestionWorker worker) {
        this.accessPolicy = accessPolicy;
        this.schemaInitializer = schemaInitializer;
        this.worker = worker;
    }

    @Override
    public void run(ApplicationArguments args) {
        StartupTiming.run("cyf.runner.archive-question", () -> runTimed(args));
    }

    private void runTimed(ApplicationArguments args) {
        if (!accessPolicy.enabled()) return;
        StartupTiming.run("cyf.archive.question-schema", schemaInitializer::initialize);
        StartupTiming.run("cyf.archive.question-worker-start", worker::start);
    }
}
