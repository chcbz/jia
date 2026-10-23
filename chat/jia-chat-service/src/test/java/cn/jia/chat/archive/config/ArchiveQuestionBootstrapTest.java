package cn.jia.chat.archive.config;

import cn.jia.chat.archive.service.ArchiveQuestionWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ArchiveQuestionBootstrapTest {
    @Test
    void enabledQuestionBootstrapStartsWorkerWithoutSchemaMaintenance() {
        ArchiveQuestionWorker worker = mock(ArchiveQuestionWorker.class);
        ArchiveQuestionBootstrap bootstrap = new ArchiveQuestionBootstrap(policy(true, true), worker);

        bootstrap.run(mock(ApplicationArguments.class));

        verify(worker).start();
    }

    @Test
    void questionOrReaderFlagOffTouchesNeitherQuestionSchemaNorWorker() {
        for (ArchiveQuestionAccessPolicy policy : List.of(policy(false, true), policy(true, false))) {
            ArchiveQuestionWorker worker = mock(ArchiveQuestionWorker.class);
            new ArchiveQuestionBootstrap(policy, worker).run(mock(ApplicationArguments.class));
            verify(worker, never()).start();
        }
    }

    private ArchiveQuestionAccessPolicy policy(boolean questionEnabled, boolean readerEnabled) {
        ArchiveReaderProperties reader = new ArchiveReaderProperties();
        reader.setEnabled(readerEnabled);
        if (readerEnabled) {
            ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
            scope.setTenantId("owner-a");
            scope.setClientId("client-a");
            reader.setAllowedScopes(new ArrayList<>(List.of(scope)));
        }
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(questionEnabled);
        return ArchiveQuestionAccessPolicy.from(question, ArchiveReaderAccessPolicy.from(reader));
    }
}
