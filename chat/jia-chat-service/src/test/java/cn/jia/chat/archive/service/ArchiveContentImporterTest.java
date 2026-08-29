package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveManifest;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;
import cn.jia.chat.archive.store.ArchiveContentStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveContentImporterTest {
    @Test
    void restartIsIdempotentAndMismatchNeverOverwritesPersistedText() {
        ArchiveManifest manifest = new ArchiveManifestLoader().load().manifest();
        MemoryStore store = new MemoryStore();
        ArchiveContentImporter importer = new ArchiveContentImporter(store, store, 100);

        importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);
        int insertsAfterFirstRun = store.successfulInserts;
        store.resetDiagnostics();
        importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);

        assertEquals(insertsAfterFirstRun, store.successfulInserts);
        assertEquals(0, store.insertAttempts,
                "a READY restart must not probe every persisted row with duplicate inserts");
        assertEquals(0, store.activationMutations,
                "an already-active READY restart must be read-only after validation");
        assertEquals(0, store.activeTransactions);
        assertEquals(manifest.editionId(), store.work.activeEditionId());
        assertEquals("READY", store.edition.importState());
        assertEquals(121, store.blocks.size());
        assertEquals(3677, store.paragraphs.size());

        String paragraphId = manifest.chapters().getFirst().paragraphs().getFirst().paragraphId();
        ArchiveParagraphRecord original = store.paragraphs.get(paragraphId);
        ArchiveParagraphRecord tampered = new ArchiveParagraphRecord(original.editionId(), original.blockId(),
                original.paragraphId(), original.ordinal(), "tampered", original.utf8ByteLength(), original.sha256());
        store.paragraphs.put(paragraphId, tampered);

        assertThrows(ArchiveImportException.class,
                () -> importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256));
        assertEquals("tampered", store.paragraphs.get(paragraphId).text());
    }

    @Test
    void concurrentReadyMismatchFuturesTerminatePropagateAndReleaseTransactions() throws Exception {
        ArchiveManifest manifest = new ArchiveManifestLoader().load().manifest();
        MemoryStore store = new MemoryStore();
        ArchiveContentImporter importer = new ArchiveContentImporter(store, store, 100);
        importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);

        String paragraphId = manifest.chapters().getFirst().paragraphs().getFirst().paragraphId();
        ArchiveParagraphRecord original = store.paragraphs.get(paragraphId);
        store.paragraphs.put(paragraphId, new ArchiveParagraphRecord(
                original.editionId(), original.blockId(), original.paragraphId(), original.ordinal(),
                "tampered", original.utf8ByteLength(), original.sha256()));
        store.resetDiagnostics();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(pool.submit(() -> {
                start.await();
                importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        for (Future<Void> future : futures) {
            assertTrue(future.isDone());
            ExecutionException failure = assertThrows(ExecutionException.class, future::get);
            assertTrue(failure.getCause() instanceof ArchiveImportException, failure.toString());
        }
        assertEquals(0, store.insertAttempts);
        assertEquals(0, store.activationMutations);
        assertEquals(0, store.activeTransactions,
                "every exceptional READY validation transaction must release in finally");
        assertEquals("tampered", store.paragraphs.get(paragraphId).text());
    }

    @Test
    void partialBatchRollbackLeavesPointerHiddenAndRestartConverges() {
        ArchiveManifest manifest = new ArchiveManifestLoader().load().manifest();
        MemoryStore store = new MemoryStore();
        store.failParagraphInsertAt = 150;
        ArchiveContentImporter importer = new ArchiveContentImporter(store, store, 100);

        assertThrows(ArchiveImportException.class,
                () -> importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256));
        assertNull(store.work.activeEditionId());
        assertEquals("STAGING", store.edition.importState());
        assertEquals(store.lastRolledBackParagraphCount, store.paragraphs.size(),
                "the failing bounded batch must roll back completely");
        assertTrue(store.paragraphs.size() > 0 && store.paragraphs.size() < 149);

        store.failParagraphInsertAt = -1;
        importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);
        assertEquals(3677, store.paragraphs.size());
        assertEquals("READY", store.edition.importState());
        assertEquals(manifest.editionId(), store.work.activeEditionId());
    }

    @Test
    void activationRollbackPreservesPreviousPointerAndConcurrentRunsExposeOnlyReadyEdition() throws Exception {
        ArchiveManifest manifest = new ArchiveManifestLoader().load().manifest();
        MemoryStore store = new MemoryStore();
        store.work = new ArchiveWorkRecord(manifest.workId(), manifest.title(), "previous-edition");
        store.failActivationAfterPointerWrite = true;
        ArchiveContentImporter importer = new ArchiveContentImporter(store, store, 100);

        assertThrows(ArchiveImportException.class,
                () -> importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256));
        assertEquals("previous-edition", store.work.activeEditionId());
        assertEquals("READY", store.edition.importState());

        store.failActivationAfterPointerWrite = false;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    importer.importAndActivate(manifest, ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256);
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(0, store.activeTransactions);
        assertEquals("READY", store.edition.importState());
        assertEquals(manifest.editionId(), store.work.activeEditionId());
    }

    private static final class MemoryStore implements ArchiveContentStore, ArchiveTransactions {
        private ArchiveWorkRecord work;
        private ArchiveEditionRecord edition;
        private final Map<String, ArchiveBlockRecord> blocks = new LinkedHashMap<>();
        private final Map<String, ArchiveParagraphRecord> paragraphs = new LinkedHashMap<>();
        private int successfulInserts;
        private int paragraphInsertAttempts;
        private int failParagraphInsertAt = -1;
        private boolean failActivationAfterPointerWrite;
        private int lastRolledBackParagraphCount = -1;
        private int insertAttempts;
        private int activationMutations;
        private int activeTransactions;

        @Override
        public synchronized <T> T required(java.util.function.Supplier<T> action) {
            Snapshot snapshot = snapshot();
            activeTransactions++;
            try {
                return action.get();
            } catch (RuntimeException failure) {
                restore(snapshot);
                lastRolledBackParagraphCount = snapshot.paragraphs.size();
                throw failure;
            } finally {
                activeTransactions--;
            }
        }

        @Override
        public void insertWork(ArchiveWorkRecord candidate) {
            insertAttempts++;
            if (work != null) throw new DuplicateKeyException("work");
            work = candidate;
            successfulInserts++;
        }

        @Override
        public ArchiveWorkRecord findWork(String workId) {
            return work != null && work.workId().equals(workId) ? work : null;
        }

        @Override
        public void insertEdition(ArchiveEditionRecord candidate) {
            insertAttempts++;
            if (edition != null) throw new DuplicateKeyException("edition");
            edition = candidate;
            successfulInserts++;
        }

        @Override
        public ArchiveEditionRecord findEdition(String editionId) {
            return edition != null && edition.editionId().equals(editionId) ? edition : null;
        }

        @Override
        public void insertBlock(ArchiveBlockRecord candidate) {
            insertAttempts++;
            if (blocks.containsKey(candidate.blockId())) throw new DuplicateKeyException("block");
            blocks.put(candidate.blockId(), candidate);
            successfulInserts++;
        }

        @Override
        public ArchiveBlockRecord findBlock(String editionId, String blockId) {
            return blocks.get(blockId);
        }

        @Override
        public void insertParagraph(ArchiveParagraphRecord candidate) {
            insertAttempts++;
            paragraphInsertAttempts++;
            if (paragraphInsertAttempts == failParagraphInsertAt) {
                throw new IllegalStateException("injected paragraph batch failure");
            }
            if (paragraphs.containsKey(candidate.paragraphId())) throw new DuplicateKeyException("paragraph");
            paragraphs.put(candidate.paragraphId(), candidate);
            successfulInserts++;
        }

        @Override
        public ArchiveParagraphRecord findParagraph(String editionId, String blockId, String paragraphId) {
            return paragraphs.get(paragraphId);
        }

        @Override
        public List<ArchiveBlockRecord> listBlocks(String editionId) {
            return List.copyOf(blocks.values());
        }

        @Override
        public List<ArchiveParagraphRecord> listParagraphs(String editionId, String blockId) {
            return paragraphs.values().stream().filter(row -> row.blockId().equals(blockId)).toList();
        }

        @Override
        public int markReady(String editionId) {
            if (edition == null || "READY".equals(edition.importState())) return 0;
            edition = edition.withImportState("READY");
            return 1;
        }

        @Override
        public ArchiveWorkRecord lockWork(String workId) {
            return findWork(workId);
        }

        @Override
        public ArchiveEditionRecord lockEdition(String editionId) {
            return findEdition(editionId);
        }

        @Override
        public int switchActiveEdition(String workId, String editionId) {
            activationMutations++;
            work = new ArchiveWorkRecord(work.workId(), work.title(), editionId);
            if (failActivationAfterPointerWrite) throw new IllegalStateException("injected activation failure");
            return 1;
        }

        @Override
        public int markActivated(String editionId) {
            activationMutations++;
            return edition == null ? 0 : 1;
        }

        private void resetDiagnostics() {
            insertAttempts = 0;
            activationMutations = 0;
        }

        private Snapshot snapshot() {
            return new Snapshot(work, edition, new LinkedHashMap<>(blocks), new LinkedHashMap<>(paragraphs),
                    successfulInserts, paragraphInsertAttempts);
        }

        private void restore(Snapshot snapshot) {
            work = snapshot.work;
            edition = snapshot.edition;
            blocks.clear(); blocks.putAll(snapshot.blocks);
            paragraphs.clear(); paragraphs.putAll(snapshot.paragraphs);
            successfulInserts = snapshot.successfulInserts;
            paragraphInsertAttempts = snapshot.paragraphInsertAttempts;
        }

        private record Snapshot(ArchiveWorkRecord work, ArchiveEditionRecord edition,
                                Map<String, ArchiveBlockRecord> blocks,
                                Map<String, ArchiveParagraphRecord> paragraphs,
                                int successfulInserts, int paragraphInsertAttempts) { }
    }
}
