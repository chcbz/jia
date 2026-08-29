package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.config.ArchiveQuestionProperties;
import cn.jia.chat.archive.config.ArchiveReaderAccessPolicy;
import cn.jia.chat.archive.config.ArchiveReaderProperties;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ArchiveSpringConstructorSelectionTest {
    @Test
    void springContextSelectsEveryProductionConstructorWithTestOnlyOverloads() {
        ArchiveQuestionTestSupport.Store questionStore = new ArchiveQuestionTestSupport.Store();
        ArchiveQuestionTestSupport.Content contentStore = new ArchiveQuestionTestSupport.Content();
        ArchiveTransactions transactions = new ArchiveQuestionTestSupport.Transactions(questionStore);
        ArchiveQuestionEventBroker broker = new ArchiveQuestionEventBroker();
        ArchiveQuestionProvider provider = request -> ArchiveQuestionProvider.Answer.complete("unused");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "archiveSpringConstructorSelectionTest",
                    Map.of("archive.question.enabled", "true")));
            context.getBeanFactory().registerSingleton("archiveQuestionStore", questionStore);
            context.getBeanFactory().registerSingleton("archivePersonalDataStore", contentStore);
            context.getBeanFactory().registerSingleton("archiveTransactions", transactions);
            context.getBeanFactory().registerSingleton("archiveQuestionEventBroker", broker);
            context.getBeanFactory().registerSingleton("archiveQuestionProvider", provider);
            context.getBeanFactory().registerSingleton("archiveQuestionAccessPolicy", enabledPolicy());
            context.registerBean(ArchiveQuestionEventDelivery.class);
            context.registerBean(ArchivePersonalDataServiceImpl.class);
            context.registerBean(ArchiveQuestionServiceImpl.class);
            context.registerBean(ArchiveQuestionSseService.class);

            context.refresh();

            assertNotNull(context.getBean(ArchiveQuestionEventDelivery.class));
            assertNotNull(context.getBean(ArchivePersonalDataServiceImpl.class));
            assertNotNull(context.getBean(ArchiveQuestionServiceImpl.class));
            assertNotNull(context.getBean(ArchiveQuestionSseService.class));
        }
    }

    private ArchiveQuestionAccessPolicy enabledPolicy() {
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(true);
        ArchiveReaderProperties reader = new ArchiveReaderProperties();
        reader.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId("owner-a");
        scope.setClientId("client-a");
        reader.setAllowedScopes(new ArrayList<>(List.of(scope)));
        return ArchiveQuestionAccessPolicy.from(question, ArchiveReaderAccessPolicy.from(reader));
    }
}
