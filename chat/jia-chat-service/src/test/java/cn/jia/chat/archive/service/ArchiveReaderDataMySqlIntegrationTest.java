package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveReaderDataSchemaInitializer;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.content.ArchiveManifest;
import cn.jia.chat.archive.content.ArchiveManifestBundle;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchivePersonalDataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Destructive H03 evidence is opt-in only. Required environment:
 * CYF_H03_MYSQL_ISOLATED=true; CYF_H03_MYSQL_URL must be an IP-literal loopback URL on an explicit
 * non-3306/non-33060 port with a database named cyf_h03_*; CYF_H03_MYSQL_DATABASE_CONFIRM must
 * exactly equal that database name. The guard rejects unsafe targets before JdbcTemplate or DROP.
 */
class ArchiveReaderDataMySqlIntegrationTest {
    private JdbcTemplate jdbc;
    private ArchiveReaderDataSchemaInitializer readerDataSchema;
    private ArchivePersonalDataService service;
    private ArchiveManifest manifest;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_H03_MYSQL_ISOLATED")),
                "requires explicit isolated H03 MySQL acknowledgement");
        var target = ArchiveReaderDataMySqlTestGuard.requireDisposable(System.getenv());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(target.url());
        dataSource.setUsername(System.getenv().getOrDefault("CYF_H03_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_H03_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        readerDataSchema = new ArchiveReaderDataSchemaInitializer(jdbc);
        readerDataSchema.initialize();
        readerDataSchema.initialize();
        ArchiveManifestBundle bundle = new ArchiveManifestLoader().load();
        new ArchiveContentImporter(new JdbcArchiveContentStore(jdbc),
                new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource)), 100)
                .importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        service = new ArchivePersonalDataServiceImpl(new JdbcArchivePersonalDataStore(jdbc),
                new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource)));
        manifest = bundle.manifest();
    }

    @AfterEach void tearDown() { if (jdbc != null) clean(); }

    @Test
    void realMysqlExactScopeReplayCasRollbackTombstonesCompletionAndDriftFailClosed() throws Exception {
        ArchiveManifest.Block firstBlock = manifest.chapters().getFirst();
        ArchiveManifest.Paragraph firstParagraph = firstBlock.paragraphs().getFirst();
        ArchiveManifest.Block finalBlock = manifest.chapters().getLast();
        ArchiveManifest.Paragraph finalParagraph = finalBlock.paragraphs().getLast();
        String bookmarkId = "123e4567-e89b-42d3-a456-426614174000";
        String noteId = "223e4567-e89b-82d3-a456-426614174000";
        List<ArchiveOwnerScope> owners = List.of(
                new ArchiveOwnerScope("owner-a", "client-a", "owner-a"),
                new ArchiveOwnerScope("owner-a", "client-b", "owner-a"),
                new ArchiveOwnerScope("owner-b", "client-a", "owner-b"),
                new ArchiveOwnerScope("owner-b", "client-b", "owner-b"));
        byte[] bookmarkBody = bookmarkBody(firstBlock, firstParagraph, "0");
        byte[] progressBody = progressBody(firstBlock, firstParagraph, "0", false, 0);
        byte[] noteBody = noteBody("0", "private-水滸");
        int beforeNonAsciiPath = count("archive_idempotency");
        ArchivePersonalDataException nonAsciiPath = assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(owners.getFirst(), "水", "/archive/v1/me/progress/水",
                        "non-ascii-progress", progressBody));
        assertEquals(404, nonAsciiPath.status());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", nonAsciiPath.code());
        assertEquals(beforeNonAsciiPath, count("archive_idempotency"));

        for (ArchiveOwnerScope owner : owners) {
            ArchiveMutationResult first = service.putBookmark(owner, bookmarkId,
                    "/archive/v1/me/bookmarks/" + bookmarkId, "bookmark-key", bookmarkBody);
            ArchiveMutationResult replay = service.putBookmark(owner, bookmarkId,
                    "/archive/v1/me/bookmarks/" + bookmarkId, "bookmark-key", bookmarkBody);
            assertArrayEquals(first.body(), replay.body());
            assertTrue(replay.replayed());
            service.putProgress(owner, manifest.editionId(),
                    "/archive/v1/me/progress/" + manifest.editionId(), "progress-key", progressBody);
            service.putNote(owner, noteId, "/archive/v1/me/notes/" + noteId, "note-key", noteBody);
        }
        assertEquals(4, count("archive_bookmark"));
        assertEquals(4, count("archive_reader_progress"));
        assertEquals(4, count("archive_note"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id,client_id,owner_jiacn) "
                + "FROM archive_reader_progress", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(DISTINCT tenant_id,client_id,owner_jiacn) "
                + "FROM archive_note", Integer.class));

        int beforeMismatch = count("archive_idempotency");
        ArchivePersonalDataException differentHash = assertThrows(ArchivePersonalDataException.class,
                () -> service.putBookmark(owners.getFirst(), bookmarkId,
                        "/archive/v1/me/bookmarks/" + bookmarkId, "bookmark-key",
                        bookmarkBody(firstBlock, firstParagraph, "1")));
        assertEquals("IDEMPOTENCY_KEY_REUSED", differentHash.code());
        assertEquals(beforeMismatch, count("archive_idempotency"));

        String bookmarkPath = "/archive/v1/me/bookmarks/" + bookmarkId;
        ArchiveMutationResult deleted = service.deleteBookmark(
                owners.getFirst(), bookmarkId, "1", bookmarkPath, "delete-key");
        ArchiveMutationResult deleteReplay = service.deleteBookmark(
                owners.getFirst(), bookmarkId, "1", bookmarkPath, "delete-key");
        assertArrayEquals(deleted.body(), deleteReplay.body());
        assertTrue(deleteReplay.replayed());
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ArchivePersonalDataException.class,
                () -> service.deleteBookmark(owners.getFirst(), bookmarkId, "2", bookmarkPath, "delete-key")).code());
        assertEquals("DELETED", jdbc.queryForObject("SELECT state FROM archive_bookmark WHERE tenant_id='owner-a' "
                + "AND client_id='client-a' AND bookmark_id=?", String.class, bookmarkId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_bookmark WHERE tenant_id='owner-a' "
                + "AND client_id='client-a' AND (paragraph_id IS NOT NULL OR paragraph_sha256 IS NOT NULL)", Integer.class));
        assertEquals(404, assertThrows(ArchivePersonalDataException.class,
                () -> service.putBookmark(owners.getFirst(), bookmarkId, bookmarkPath, "resurrect-key",
                        bookmarkBody(firstBlock, firstParagraph, "2"))).status());

        String notePath = "/archive/v1/me/notes/" + noteId;
        ArchiveMutationResult noteDeleted = service.deleteNote(owners.get(1), noteId, "1", notePath, "note-delete");
        assertArrayEquals(noteDeleted.body(), service.deleteNote(
                owners.get(1), noteId, "1", notePath, "note-delete").body());
        assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ArchivePersonalDataException.class,
                () -> service.deleteNote(owners.get(1), noteId, "2", notePath, "note-delete")).code());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_note WHERE tenant_id='owner-a' "
                + "AND client_id='client-b' AND (text IS NOT NULL OR anchor_json IS NOT NULL)", Integer.class));

        byte[] completion = progressBody(finalBlock, finalParagraph, "1", true,
                finalParagraph.utf8ByteLength());
        service.putProgress(owners.get(3), manifest.editionId(),
                "/archive/v1/me/progress/" + manifest.editionId(), "complete-key", completion);
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM archive_reader_progress "
                + "WHERE tenant_id='owner-b' AND client_id='client-b'", String.class));
        assertEquals(finalParagraph.utf8ByteLength(), jdbc.queryForObject(
                "SELECT byte_offset FROM archive_reader_progress WHERE tenant_id='owner-b' AND client_id='client-b'",
                Long.class));
        assertEquals(finalParagraph.paragraphId(), jdbc.queryForObject(
                "SELECT paragraph_id FROM archive_reader_progress WHERE tenant_id='owner-b' AND client_id='client-b'",
                String.class));

        int beforeZeroWrite = count("archive_idempotency");
        byte[] wrongHash = new String(bookmarkBody, StandardCharsets.UTF_8)
                .replace(firstParagraph.sha256(), "b".repeat(64)).getBytes(StandardCharsets.UTF_8);
        assertEquals("CONTENT_HASH_MISMATCH", assertThrows(ArchivePersonalDataException.class,
                () -> service.putBookmark(owners.get(1), "323e4567-e89b-f2d3-a456-426614174000",
                        "/archive/v1/me/bookmarks/323e4567-e89b-f2d3-a456-426614174000",
                        "rollback-key", wrongHash)).code());
        assertEquals("INVALID_REQUEST_JSON", assertThrows(ArchivePersonalDataException.class,
                () -> service.putProgress(owners.getFirst(), manifest.editionId(),
                        "/archive/v1/me/progress/" + manifest.editionId(), "scalar-key",
                        new String(progressBody, StandardCharsets.UTF_8)
                                .replace("\"expectedVersion\":\"0\"", "\"expectedVersion\":0")
                                .getBytes(StandardCharsets.UTF_8))).code());
        assertEquals("VERSION_CONFLICT", assertThrows(ArchivePersonalDataException.class,
                () -> service.putNote(owners.get(2), noteId, notePath, "note-conflict", noteBody("9", "changed"))).code());
        assertEquals(beforeZeroWrite, count("archive_idempotency"));
        assertEquals(4, count("archive_bookmark"));
        assertEquals(4, count("archive_note"));

        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = java.util.Collections.synchronizedList(new ArrayList<>());
        byte[] raceProgress = progressBody(firstBlock, firstParagraph, "1", false, 0);
        for (int i = 0; i < 2; i++) {
            String key = "race-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(service.putProgress(owners.get(2), manifest.editionId(),
                            "/archive/v1/me/progress/" + manifest.editionId(), key, raceProgress));
                } catch (Throwable failure) { outcomes.add(failure); }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter(ArchiveMutationResult.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(value -> value instanceof ArchivePersonalDataException failure
                && "VERSION_CONFLICT".equals(failure.code())).count());

        jdbc.execute("ALTER TABLE archive_note MODIFY text LONGTEXT CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_ai_ci NULL");
        assertThrows(IllegalStateException.class, readerDataSchema::initialize);
    }

    private byte[] bookmarkBody(ArchiveManifest.Block block, ArchiveManifest.Paragraph paragraph, String version) {
        return ("{\"expectedVersion\":\"" + version + "\",\"editionId\":\"" + manifest.editionId()
                + "\",\"location\":" + location(block, paragraph, 0) + "}").getBytes(StandardCharsets.UTF_8);
    }
    private byte[] progressBody(ArchiveManifest.Block block, ArchiveManifest.Paragraph paragraph,
                                String version, boolean completed, long offset) {
        return ("{\"expectedVersion\":\"" + version + "\",\"location\":"
                + location(block, paragraph, offset) + ",\"markCompleted\":" + completed + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
    private byte[] noteBody(String version, String text) {
        return ("{\"expectedVersion\":\"" + version + "\",\"editionId\":\""
                + manifest.editionId() + "\",\"text\":\"" + text + "\",\"anchor\":null}")
                .getBytes(StandardCharsets.UTF_8);
    }
    private String location(ArchiveManifest.Block block, ArchiveManifest.Paragraph paragraph, long offset) {
        return "{\"editionManifestSha256\":\"" + manifest.manifestSha256()
                + "\",\"blockType\":\"CHAPTER\",\"blockId\":\"" + block.blockId()
                + "\",\"paragraphId\":\"" + paragraph.paragraphId() + "\",\"byteOffset\":"
                + offset + ",\"paragraphSha256\":\"" + paragraph.sha256() + "\"}";
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    for (String table : new String[]{"archive_idempotency", "archive_note", "archive_bookmark",
                            "archive_reader_progress", "archive_paragraph", "archive_chapter",
                            "archive_edition", "archive_work"}) {
                        statement.execute("DROP TABLE IF EXISTS " + table);
                    }
                } finally { statement.execute("SET FOREIGN_KEY_CHECKS=1"); }
            }
            return null;
        });
    }
}
