package cn.jia.chat.archive.config;

import cn.jia.core.diagnostics.StartupTiming;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Profile("schema-maintenance")
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ArchiveBootstrap implements ApplicationRunner {
    private final ArchiveReaderAccessPolicy accessPolicy;
    private final ArchiveSchemaInitializer schemaInitializer;
    private final ArchiveReaderDataSchemaInitializer readerDataSchemaInitializer;

    public ArchiveBootstrap(ArchiveReaderAccessPolicy accessPolicy,
                            ArchiveSchemaInitializer schemaInitializer,
                            ArchiveReaderDataSchemaInitializer readerDataSchemaInitializer) {
        this.accessPolicy = accessPolicy;
        this.schemaInitializer = schemaInitializer;
        this.readerDataSchemaInitializer = readerDataSchemaInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        StartupTiming.run("cyf.runner.archive", () -> runTimed(args));
    }

    private void runTimed(ApplicationArguments args) {
        if (!accessPolicy.enabled()) {
            return;
        }
        StartupTiming.run("cyf.archive.schema", schemaInitializer::initialize);
        StartupTiming.run("cyf.archive.reader-schema", readerDataSchemaInitializer::initialize);
    }
}
