package cn.jia.chat.archive.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchiveQuestionMySqlTestGuardTest {
    @Test
    void acceptsOnlyExplicitDisposableLoopbackIdentity() {
        var target = ArchiveQuestionMySqlTestGuard.requireDisposable(valid());
        assertEquals("cyf_h05a_review_01", target.databaseName());
        assertEquals("jdbc:mysql://127.0.0.1:13326/cyf_h05a_review_01", target.url());
    }

    @Test
    void rejectsMissingAckRemoteProductionPortsUnsafeNamesQueriesAndMismatchedConfirmation() {
        reject(with("CYF_H05A_MYSQL_ISOLATED", "TRUE"));
        reject(with("CYF_H05A_MYSQL_URL", "jdbc:mysql://db.example.com:13326/cyf_h05a_review_01"));
        reject(with("CYF_H05A_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/cyf_h05a_review_01"));
        reject(with("CYF_H05A_MYSQL_URL", "jdbc:mysql://127.0.0.1:33060/cyf_h05a_review_01"));
        reject(with("CYF_H05A_MYSQL_URL", "jdbc:mysql://127.0.0.1:13326/cyf_h02_review_01"));
        reject(with("CYF_H05A_MYSQL_DATABASE_CONFIRM", "cyf_h05a_other"));
        reject(with("CYF_H05A_MYSQL_URL", "jdbc:mysql://127.0.0.1:13326/cyf_h05a_review_01?x=1"));
    }

    private void reject(Map<String, String> env) {
        assertThrows(IllegalStateException.class,
                () -> ArchiveQuestionMySqlTestGuard.requireDisposable(env));
    }
    private Map<String, String> with(String key, String value) {
        Map<String, String> env = valid(); env.put(key, value); return env;
    }
    private Map<String, String> valid() {
        Map<String, String> env = new HashMap<>();
        env.put("CYF_H05A_MYSQL_ISOLATED", "true");
        env.put("CYF_H05A_MYSQL_URL", "jdbc:mysql://127.0.0.1:13326/cyf_h05a_review_01");
        env.put("CYF_H05A_MYSQL_DATABASE_CONFIRM", "cyf_h05a_review_01");
        return env;
    }
}
