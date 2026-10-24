package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatStartupSchemaValidationTest {
    @Test
    void validateModeSkipsInstallAndMigrationEvenWhenMigrationFlagIsTrue() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ChatDeliberationSchemaInitializer initializer =
                new ChatDeliberationSchemaInitializer(jdbc, "validate");
        Field migration = ChatDeliberationSchemaInitializer.class
                .getDeclaredField("allowAdditiveMigration");
        migration.setAccessible(true);
        migration.setBoolean(initializer, true);

        assertThrows(IllegalStateException.class, initializer::initializeWhileLocked);

        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc).queryForList(anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void unknownSchemaModeFailsClosedBeforeDatabaseAccess() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertThrows(IllegalStateException.class,
                () -> new ChatDeliberationSchemaInitializer(jdbc, "unknown"));
        verify(jdbc, never()).execute(anyString());
    }
}
