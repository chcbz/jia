package cn.jia.chat.archive.config;

import cn.jia.chat.archive.service.ArchiveContentImporter;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ArchiveBootstrapTest {
    @Test
    void enabledBootstrapKeepsH02SchemaAndImportBeforeSeparateAdditiveH03Initializer() {
        ArchiveSchemaInitializer h02 = mock(ArchiveSchemaInitializer.class);
        ArchiveReaderDataSchemaInitializer h03 = mock(ArchiveReaderDataSchemaInitializer.class);
        ArchiveContentImporter importer = mock(ArchiveContentImporter.class);
        ArchiveBootstrap bootstrap = new ArchiveBootstrap(enabledPolicy(), h02, h03, importer);

        bootstrap.run(mock(ApplicationArguments.class));

        InOrder order = inOrder(h02, importer, h03);
        order.verify(h02).initialize();
        order.verify(importer).importAndActivate(any(), anyString());
        order.verify(h03).initialize();
    }

    @Test
    void disabledBootstrapTouchesNeitherSchemaNorContent() {
        ArchiveSchemaInitializer h02 = mock(ArchiveSchemaInitializer.class);
        ArchiveReaderDataSchemaInitializer h03 = mock(ArchiveReaderDataSchemaInitializer.class);
        ArchiveContentImporter importer = mock(ArchiveContentImporter.class);
        new ArchiveBootstrap(ArchiveReaderAccessPolicy.from(new ArchiveReaderProperties()), h02, h03, importer)
                .run(mock(ApplicationArguments.class));
        verify(h02, never()).initialize();
        verify(h03, never()).initialize();
        verify(importer, never()).importAndActivate(any(), anyString());
    }

    private ArchiveReaderAccessPolicy enabledPolicy() {
        ArchiveReaderProperties properties = new ArchiveReaderProperties();
        properties.setEnabled(true);
        ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
        scope.setTenantId("owner-a"); scope.setClientId("client-a");
        properties.setAllowedScopes(new java.util.ArrayList<>(List.of(scope)));
        return ArchiveReaderAccessPolicy.from(properties);
    }
}
