package cn.jia.chat.archive.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveMySqlTestGuardTest {
    @Test
    void mysqlConnectorDriverIsAvailableToIsolatedTestRuntime() throws Exception {
        assertEquals("com.mysql.cj.jdbc.Driver",
                Class.forName("com.mysql.cj.jdbc.Driver").getName());
    }

    @Test
    void acceptsOnlyExactAcknowledgedDisposableLoopbackDatabase() {
        Map<String, String> environment = validEnvironment();
        ArchiveMySqlTestGuard.Target target = ArchiveMySqlTestGuard.requireDisposable(environment);
        assertEquals("cyf_h02_review_01", target.databaseName());
        assertEquals("jdbc:mysql://127.0.0.1:13316/cyf_h02_review_01", target.url());
    }

    @Test
    void rejectsMissingAckRemoteDefaultPortUnsafeNameAndConfirmationMismatch() {
        assertRejected(with("CYF_H02_MYSQL_ISOLATED", "TRUE"));
        assertRejected(with("CYF_H02_MYSQL_URL", "jdbc:mysql://db.example.com:13316/cyf_h02_review_01"));
        assertRejected(with("CYF_H02_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/cyf_h02_review_01"));
        assertRejected(with("CYF_H02_MYSQL_URL", "jdbc:mysql://127.0.0.1:13316/jia_dev"));
        assertRejected(with("CYF_H02_MYSQL_DATABASE_CONFIRM", "cyf_h02_other"));
        assertRejected(with("CYF_H02_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:13316/cyf_h02_review_01?socketFactory=unsafe"));
    }

    @Test
    void acceptsIpv6LoopbackWithExplicitSafePort() {
        Map<String, String> environment = validEnvironment();
        environment.put("CYF_H02_MYSQL_URL", "jdbc:mysql://[::1]:13317/cyf_h02_review_01");
        ArchiveMySqlTestGuard.Target target = ArchiveMySqlTestGuard.requireDisposable(environment);
        assertEquals("cyf_h02_review_01", target.databaseName());
    }

    private void assertRejected(Map<String, String> environment) {
        assertThrows(IllegalStateException.class,
                () -> ArchiveMySqlTestGuard.requireDisposable(environment));
    }

    private Map<String, String> with(String key, String value) {
        Map<String, String> environment = validEnvironment();
        environment.put(key, value);
        return environment;
    }

    private Map<String, String> validEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("CYF_H02_MYSQL_ISOLATED", "true");
        environment.put("CYF_H02_MYSQL_URL", "jdbc:mysql://127.0.0.1:13316/cyf_h02_review_01");
        environment.put("CYF_H02_MYSQL_DATABASE_CONFIRM", "cyf_h02_review_01");
        return environment;
    }
}
