package cn.jia.chat.archive.conversation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConversationArchiveMySqlTestGuardTest {
    private static final String OWNED_PREFIX = "mmd_ac12main";

    @Test
    void acceptsOnlyTheExplicitMainOwnedLoopbackFixture() {
        var target = ChatConversationArchiveMySqlTestGuard.requireDisposable(environment(
                "jdbc:mysql://127.0.0.1:33793/mysql?useSSL=false&serverTimezone=UTC",
                OWNED_PREFIX, true));
        assertEquals("root", target.user());
        assertEquals("", target.password());
        assertEquals(OWNED_PREFIX, target.databasePrefix());
    }

    @Test
    void rejectsForeignOrAmbiguousTargetsBeforeOpeningAConnection() {
        assertUnsafe(environment("jdbc:mysql://db.example:33793/mysql", OWNED_PREFIX, true));
        assertUnsafe(environment("jdbc:mysql://127.0.0.1:3306/mysql", OWNED_PREFIX, true));
        assertUnsafe(environment("jdbc:mysql://root@127.0.0.1:33793/mysql", OWNED_PREFIX, true));
        assertUnsafe(environment("jdbc:mysql://127.0.0.1:33793/other", OWNED_PREFIX, true));
        assertUnsafe(environment("jdbc:mysql://127.0.0.1:33793/mysql", "mmd_foreign", true));
        assertUnsafe(environment("jdbc:mysql://127.0.0.1:33793/mysql", OWNED_PREFIX, false));

        Map<String, String> missingPassword = environment(
                "jdbc:mysql://127.0.0.1:33793/mysql", OWNED_PREFIX, true);
        missingPassword.remove("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        assertUnsafe(missingPassword);
    }

    private static Map<String, String> environment(String url, String prefix, boolean isolated) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("MMD_U1_REFERENCE_MYSQL_URL", url);
        result.put("MMD_U1_REFERENCE_MYSQL_USER", "root");
        result.put("MMD_U1_REFERENCE_MYSQL_PASSWORD", "");
        result.put("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX", prefix);
        result.put("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE", Boolean.toString(isolated));
        return result;
    }

    private static void assertUnsafe(Map<String, String> environment) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ChatConversationArchiveMySqlTestGuard.requireDisposable(environment));
        assertTrue(failure.getMessage().startsWith("Unsafe AC12 MySQL test target:"),
                failure.getMessage());
    }
}
