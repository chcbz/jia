package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.content.ArchiveManifestBundle;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Isolated MySQL evidence. It performs DDL/DML only when the runner explicitly supplies
 * CYF_H02_MYSQL_ISOLATED=true and a disposable CYF_H02_MYSQL_URL database.
 */
class ArchiveMySqlIntegrationTest {
    private JdbcTemplate jdbc;
    private ArchiveSchemaInitializer schemaInitializer;
    private ArchiveContentImporter importer;
    private ArchiveManifestBundle bundle;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_H02_MYSQL_ISOLATED")),
                "requires explicit isolated-MySQL acknowledgement");
        String url = System.getenv("CYF_H02_MYSQL_URL");
        assumeTrue(url != null && !url.isBlank(), "CYF_H02_MYSQL_URL is required");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault("CYF_H02_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_H02_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        clean();
        schemaInitializer = new ArchiveSchemaInitializer(jdbc);
        schemaInitializer.initialize();
        JdbcArchiveContentStore store = new JdbcArchiveContentStore(jdbc);
        importer = new ArchiveContentImporter(store,
                new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource)), 100);
        bundle = new ArchiveManifestLoader().load();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) clean();
    }

    @Test
    void realMysqlSchemaImportRestartConcurrencyMismatchAndDriftFailClosed() throws Exception {
        importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        assertEquals(120, count("archive_chapter", "block_type='CHAPTER'"));
        assertEquals(1, count("archive_chapter", "block_type='PREFACE'"));
        assertEquals(3677, count("archive_paragraph", "1=1"));
        assertEquals(bundle.manifest().editionId(), jdbc.queryForObject(
                "SELECT active_edition_id FROM archive_work WHERE work_id='shuihuzhuan'", String.class));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) pool.submit(() -> {
            try {
                start.await();
                importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
            } catch (Throwable failure) {
                failures.add(failure);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), failures.toString());

        String paragraphId = bundle.manifest().chapters().getFirst().paragraphs().getFirst().paragraphId();
        jdbc.update("UPDATE archive_paragraph SET text='tampered' WHERE paragraph_id=?", paragraphId);
        assertThrows(ArchiveImportException.class,
                () -> importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256()));
        assertEquals("tampered", jdbc.queryForObject(
                "SELECT text FROM archive_paragraph WHERE paragraph_id=?", String.class, paragraphId));

        jdbc.execute("ALTER TABLE archive_paragraph MODIFY text LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        assertThrows(IllegalStateException.class, schemaInitializer::initialize);
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        try {
            jdbc.execute("DROP TABLE IF EXISTS archive_paragraph");
            jdbc.execute("DROP TABLE IF EXISTS archive_chapter");
            jdbc.execute("DROP TABLE IF EXISTS archive_edition");
            jdbc.execute("DROP TABLE IF EXISTS archive_work");
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }
}
