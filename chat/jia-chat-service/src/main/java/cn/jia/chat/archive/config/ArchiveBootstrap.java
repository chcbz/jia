package cn.jia.chat.archive.config;

import cn.jia.core.diagnostics.StartupTiming;
import cn.jia.chat.archive.content.ArchiveManifestBundle;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.service.ArchiveContentImporter;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ArchiveBootstrap implements ApplicationRunner {
    private final ArchiveReaderAccessPolicy accessPolicy;
    private final ArchiveSchemaInitializer schemaInitializer;
    private final ArchiveReaderDataSchemaInitializer readerDataSchemaInitializer;
    private final ArchiveManifestLoader manifestLoader;
    private final ArchiveContentImporter importer;

    public ArchiveBootstrap(ArchiveReaderAccessPolicy accessPolicy,
                            ArchiveSchemaInitializer schemaInitializer,
                            ArchiveReaderDataSchemaInitializer readerDataSchemaInitializer,
                            ArchiveContentImporter importer) {
        this.accessPolicy = accessPolicy;
        this.schemaInitializer = schemaInitializer;
        this.readerDataSchemaInitializer = readerDataSchemaInitializer;
        this.manifestLoader = new ArchiveManifestLoader();
        this.importer = importer;
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
        ArchiveManifestBundle bundle = StartupTiming.call("cyf.archive.manifest-load", manifestLoader::load);
        StartupTiming.run("cyf.archive.import-activate",
                () -> importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256()));
    }
}
