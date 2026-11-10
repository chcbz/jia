package cn.jia.chat.archive.config;

import cn.jia.chat.archive.http.ArchiveQuestionController;
import cn.jia.chat.archive.service.ArchiveQuestionEventBroker;
import cn.jia.chat.archive.service.ArchiveQuestionEventDelivery;
import cn.jia.chat.archive.service.ArchiveQuestionServiceImpl;
import cn.jia.chat.archive.service.ArchiveQuestionSseService;
import cn.jia.chat.archive.service.ArchiveQuestionWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.util.Properties;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveQuestionFeatureFlagTest {
    @Test
    void everyQuestionRuntimeCapabilityAndInitializerRequiresIndependentOptIn() {
        for (Class<?> type : List.of(
                ArchiveQuestionBootstrap.class,
                ArchiveQuestionProviderConfiguration.class,
                ArchiveQuestionSchemaInitializer.class,
                ArchiveQuestionController.class,
                ArchiveQuestionServiceImpl.class,
                ArchiveQuestionSseService.class,
                ArchiveQuestionWorker.class,
                ArchiveQuestionEventBroker.class,
                ArchiveQuestionEventDelivery.class)) {
            ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(condition, type.getName());
            if (type == ArchiveQuestionBootstrap.class) {
                assertTrue(List.of(condition.name()).containsAll(List.of("archive.question.enabled", "cyf.task.scheduling.enabled")));
            } else {
                assertEquals("archive.question", condition.prefix(), type.getName());
                assertTrue(List.of(condition.name()).contains("enabled"), type.getName());
            }
            assertEquals("true", condition.havingValue(), type.getName());
            assertTrue(condition.matchIfMissing() == false, type.getName());
        }
    }

    @Test
    void packagedServiceDefaultsQuestionCapabilityOffIndependentlyOfReaderFlag() throws Exception {
        // Dependencies also ship application.properties; the first classloader hit is not
        // necessarily this service's packaged defaults (common-test has its own file).
        var resources = getClass().getClassLoader().getResources("application.properties");
        int archiveDefaults = 0;
        while (resources.hasMoreElements()) {
            var resource = resources.nextElement();
            Properties properties = new Properties();
            try (InputStream input = resource.openStream()) { properties.load(input); }
            if (!properties.containsKey("archive.reader.enabled")
                    && !properties.containsKey("archive.question.enabled")) continue;
            archiveDefaults++;
            assertEquals("false", properties.getProperty("archive.reader.enabled"), resource.toString());
            assertEquals("false", properties.getProperty("archive.question.enabled"), resource.toString());
        }
        assertEquals(1, archiveDefaults, "exactly one packaged archive defaults resource");
    }
}
