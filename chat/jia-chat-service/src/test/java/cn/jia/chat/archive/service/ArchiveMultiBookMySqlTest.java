package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.config.ArchiveReaderDataSchemaInitializer;
import cn.jia.chat.archive.content.ArchiveGenericManifestTest;
import cn.jia.chat.archive.dto.ArchiveAnchorSegmentDTO;
import cn.jia.chat.archive.dto.ArchiveTextAnchorDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchivePersonalDataStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Reuses the existing CYF_REVIEW_MYSQLD owned-instance pattern. No external JDBC target or fallback. */
@EnabledIfEnvironmentVariable(named = "CYF_REVIEW_MYSQLD", matches = ".+")
class ArchiveMultiBookMySqlTest {
    @TempDir static Path scratch;
    private static Process initializationProcess, databaseProcess;
    private static String baseUrl;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeAll static void startOwnedDatabase() throws Exception {
        Path executable = Path.of(System.getenv("CYF_REVIEW_MYSQLD")).toRealPath();
        assertTrue(Files.isExecutable(executable));
        Path data = Files.createDirectory(scratch.resolve("data"));
        Path log = scratch.resolve("mysql.log");
        String user = System.getProperty("user.name");
        try {
            initializationProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                    "--user=" + user, "--datadir=" + data, "--basedir=" + executable.getParent().getParent())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertEquals(0, initializationProcess.waitFor(), "Owned MySQL initialization failed");
            initializationProcess = null;
            int port;
            do { try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); } }
            while (port == 3306 || port == 33060);
            databaseProcess = new ProcessBuilder(executable.toString(), "--no-defaults", "--user=" + user,
                    "--datadir=" + data, "--basedir=" + executable.getParent().getParent(),
                    "--socket=" + scratch.resolve("mysql.sock"), "--pid-file=" + scratch.resolve("mysql.pid"),
                    "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                    "--innodb-buffer-pool-size=67108864")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            baseUrl = "jdbc:mysql://127.0.0.1:" + port + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            Class.forName("com.mysql.cj.jdbc.Driver");
            while (true) {
                assertTrue(databaseProcess.isAlive(), "Owned MySQL exited before readiness");
                try (var connection = DriverManager.getConnection(baseUrl, "root", "")) {
                    assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8.0.21"));
                    break;
                } catch (SQLException notReady) { Thread.sleep(100); }
            }
        } catch (Throwable failure) {
            try { stopOwnedDatabase(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stopOwnedDatabase() throws InterruptedException {
        try { stop(databaseProcess); }
        finally { databaseProcess = null; try { stop(initializationProcess); } finally { initializationProcess = null; } }
    }
    private static void stop(Process process) throws InterruptedException {
        if (process == null) return;
        process.destroy();
        try { process.waitFor(); }
        catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try { process.waitFor(); } finally { Thread.currentThread().interrupt(); }
            throw interrupted;
        }
    }

    @BeforeEach void createOwnedSchema() {
        String database = "cyf_archive_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(baseUrl, "root", ""))
                .execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source = new DriverManagerDataSource(baseUrl.replace("/mysql?", "/" + database + "?"), "root", "");
        jdbc = new JdbcTemplate(source);
    }

    @Test void booksCompletionAndSelectionsAreEditionScopedInRealDatabase() throws Exception {
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        var transactions = new SpringArchiveTransactions(new DataSourceTransactionManager(source));
        var content = new JdbcArchiveContentStore(jdbc);
        var importer = new ArchiveContentImporter(content, transactions);
        var personalStore = new JdbcArchivePersonalDataStore(jdbc);
        var personal = new ArchivePersonalDataServiceImpl(personalStore, transactions);
        var owner = new ArchiveOwnerScope("owner", "client", "owner");
        var foreign = new ArchiveOwnerScope("other", "client", "other");
        var first = ArchiveGenericManifestTest.fixture("book-a");
        var second = ArchiveGenericManifestTest.fixture("book-b");
        for (var loaded : List.of(first, second)) {
            importer.importAndActivate(loaded.manifest(), loaded.manifestFileSha256());
            importer.importAndActivate(loaded.manifest(), loaded.manifestFileSha256());
            var manifest = loaded.manifest();
            var last = manifest.chapters().getLast();
            var paragraph = last.paragraphs().getLast();
            var point = personalStore.lockFinalContentPoint(manifest.editionId());
            assertEquals(paragraph.paragraphId(), point.paragraphId());
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("expectedVersion", "0", "markCompleted", true,
                    "location", Map.of("editionManifestSha256", manifest.manifestSha256(), "blockType", "CHAPTER",
                            "blockId", last.blockId(), "paragraphId", paragraph.paragraphId(),
                            "byteOffset", paragraph.utf8ByteLength(), "paragraphSha256", paragraph.sha256())));
            assertEquals(200, personal.putProgress(owner, manifest.editionId(),
                    "/archive/v1/me/progress/" + manifest.editionId(), "complete-" + manifest.workId(), body).status());
            assertEquals("COMPLETED", personal.progress(owner, manifest.editionId()).state());
            assertNull(personal.progress(foreign, manifest.editionId()));
        }
        assertEquals(List.of("book-a", "book-b"), content.listPublishedBooks().stream().map(book -> book.workId()).toList());
        var reader = new ArchiveReaderServiceImpl(content);
        assertNull(reader.catalog("book-b").data().activeEdition().preface());
        assertEquals(121, reader.catalog("book-b").data().activeEdition().chapters().size());
        var block = first.manifest().chapters().getFirst();
        var paragraph = block.paragraphs().getFirst();
        var anchor = new ArchiveTextAnchorDTO(first.manifest().manifestSha256(), "CHAPTER", block.blockId(),
                List.of(new ArchiveAnchorSegmentDTO(paragraph.paragraphId(), 0L, paragraph.utf8ByteLength(), paragraph.sha256())), paragraph.sha256());
        var validator = new ArchiveTextSelectionValidator(personalStore);
        assertEquals(paragraph.text(), transactions.required(() -> validator.reconstruct(first.manifest().editionId(), anchor)).selectedText());
        assertThrows(ArchivePersonalDataException.class, () -> transactions.required(() -> validator.reconstruct(second.manifest().editionId(), anchor)));
        jdbc.update("UPDATE archive_edition SET import_state='STAGING' WHERE edition_id=?", second.manifest().editionId());
        assertEquals(1, content.listPublishedBooks().size());
        assertNull(personalStore.lockActiveEdition(second.manifest().editionId()));
    }

    @Test void oldBookSpecificChecksRequireExplicitMigration() throws Exception {
        String legacy = new ClassPathResource("db/archive-schema.sql").getContentAsString(StandardCharsets.UTF_8)
                .replace("chapter_count >= 1", "chapter_count = 120")
                .replace("preface_paragraph_count >= 0", "preface_paragraph_count >= 1")
                .replace("preface_utf8_byte_length >= 0", "preface_utf8_byte_length > 0")
                .replace("reader_ordinal >= 1", "reader_ordinal between 1 and 120");
        new ResourceDatabasePopulator(new ByteArrayResource(legacy.getBytes(StandardCharsets.UTF_8))).execute(source);
        var schema = new ArchiveSchemaInitializer(jdbc);
        assertThrows(IllegalStateException.class, schema::initialize);
        new ResourceDatabasePopulator(new ClassPathResource("db/archive-multi-work-migration.sql")).execute(source);
        assertDoesNotThrow(schema::initialize);
        var loaded = ArchiveGenericManifestTest.fixture("book-b");
        new ArchiveContentImporter(new JdbcArchiveContentStore(jdbc), new SpringArchiveTransactions(new DataSourceTransactionManager(source)))
                .importAndActivate(loaded.manifest(), loaded.manifestFileSha256());
        assertEquals(121, jdbc.queryForObject("SELECT chapter_count FROM archive_edition", Integer.class));
    }
}
