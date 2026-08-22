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
import java.nio.charset.StandardCharsets;
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
            assertEquals("archive.question", condition.prefix(), type.getName());
            assertTrue(List.of(condition.name()).contains("enabled"), type.getName());
            assertEquals("true", condition.havingValue(), type.getName());
            assertTrue(condition.matchIfMissing() == false, type.getName());
        }
    }

    @Test
    void packagedServiceDefaultsQuestionCapabilityOffIndependentlyOfReaderFlag() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(input);
            String properties = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(properties.lines().anyMatch("archive.reader.enabled=false"::equals));
            assertTrue(properties.lines().anyMatch("archive.question.enabled=false"::equals));
        }
    }
}
