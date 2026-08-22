package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifest;
import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;
import cn.jia.chat.archive.store.ArchiveContentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class ArchiveContentImporter {
    public static final int DEFAULT_PARAGRAPH_BATCH_SIZE = 100;

    private final ArchiveContentStore store;
    private final ArchiveTransactions transactions;
    private final int paragraphBatchSize;

    @Autowired
    public ArchiveContentImporter(ArchiveContentStore store, ArchiveTransactions transactions) {
        this(store, transactions, DEFAULT_PARAGRAPH_BATCH_SIZE);
    }

    public ArchiveContentImporter(ArchiveContentStore store, ArchiveTransactions transactions,
                                  int paragraphBatchSize) {
        this.store = Objects.requireNonNull(store, "store");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (paragraphBatchSize < 1 || paragraphBatchSize > 500) {
            throw new IllegalArgumentException("paragraphBatchSize must be between 1 and 500");
        }
        this.paragraphBatchSize = paragraphBatchSize;
    }

    public void importAndActivate(ArchiveManifest manifest, String manifestFileSha256) {
        Objects.requireNonNull(manifest, "manifest");
        try {
            ArchiveEditionRecord expectedEdition = expectedEdition(manifest, manifestFileSha256);
            transactions.required(() -> {
                ensureWork(manifest);
                ensureEdition(expectedEdition);
                return null;
            });
            for (ArchiveManifest.Block block : manifest.blocksInReaderOrder()) {
                ArchiveBlockRecord expectedBlock = expectedBlock(manifest, block);
                transactions.required(() -> {
                    ensureBlock(expectedBlock);
                    return null;
                });
                List<ArchiveParagraphRecord> paragraphs = expectedParagraphs(manifest, block);
                for (int start = 0; start < paragraphs.size(); start += paragraphBatchSize) {
                    int from = start;
                    int to = Math.min(start + paragraphBatchSize, paragraphs.size());
                    transactions.required(() -> {
                        for (ArchiveParagraphRecord paragraph : paragraphs.subList(from, to)) {
                            ensureParagraph(paragraph);
                        }
                        return null;
                    });
                }
            }
            transactions.required(() -> {
                // Serialize the final persisted re-read/READY transition on the edition only.
                // The work row is intentionally not locked until the short activation transaction.
                ArchiveEditionRecord current = store.lockEdition(manifest.editionId());
                requireEditionMetadata(expectedEdition, current);
                verifyPersistedContent(manifest);
                if ("STAGING".equals(current.importState())) {
                    if (store.markReady(manifest.editionId()) != 1) {
                        throw mismatch("edition READY transition");
                    }
                } else if (!"READY".equals(current.importState())) {
                    throw mismatch("edition import state");
                }
                return null;
            });
            activate(manifest, expectedEdition);
        } catch (ArchiveImportException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ArchiveImportException("Archive import failed closed", failure);
        }
    }

    private void activate(ArchiveManifest manifest, ArchiveEditionRecord expectedEdition) {
        transactions.required(() -> {
            // Frozen lock order: work row first, then candidate edition row.
            ArchiveWorkRecord work = store.lockWork(manifest.workId());
            if (work == null || !manifest.workId().equals(work.workId())
                    || !manifest.title().equals(work.title())) {
                throw mismatch("activation work row");
            }
            ArchiveEditionRecord candidate = store.lockEdition(manifest.editionId());
            requireEditionMetadata(expectedEdition, candidate);
            if (!"READY".equals(candidate.importState())) {
                throw mismatch("activation candidate state");
            }
            if (store.switchActiveEdition(manifest.workId(), manifest.editionId()) != 1) {
                throw mismatch("active pointer switch");
            }
            if (store.markActivated(manifest.editionId()) != 1) {
                throw mismatch("activation timestamp");
            }
            return null;
        });
    }

    private void ensureWork(ArchiveManifest manifest) {
        ArchiveWorkRecord expected = new ArchiveWorkRecord(manifest.workId(), manifest.title(), null);
        try {
            store.insertWork(expected);
        } catch (DuplicateKeyException duplicate) {
            ArchiveWorkRecord actual = store.findWork(manifest.workId());
            if (actual == null || !expected.workId().equals(actual.workId())
                    || !expected.title().equals(actual.title())) {
                throw mismatch("work row");
            }
        }
    }

    private void ensureEdition(ArchiveEditionRecord expected) {
        try {
            store.insertEdition(expected);
        } catch (DuplicateKeyException duplicate) {
            requireEditionMetadata(expected, store.findEdition(expected.editionId()));
        }
    }

    private void ensureBlock(ArchiveBlockRecord expected) {
        try {
            store.insertBlock(expected);
        } catch (DuplicateKeyException duplicate) {
            ArchiveBlockRecord actual = store.findBlock(expected.editionId(), expected.blockId());
            if (!expected.equals(actual)) {
                throw mismatch("block " + expected.blockId());
            }
        }
    }

    private void ensureParagraph(ArchiveParagraphRecord expected) {
        try {
            store.insertParagraph(expected);
        } catch (DuplicateKeyException duplicate) {
            ArchiveParagraphRecord actual = store.findParagraph(
                    expected.editionId(), expected.blockId(), expected.paragraphId());
            if (!expected.equals(actual)) {
                throw mismatch("paragraph " + expected.paragraphId());
            }
        }
    }

    private void verifyPersistedContent(ArchiveManifest manifest) {
        List<ArchiveBlockRecord> expectedBlocks = manifest.blocksInReaderOrder().stream()
                .map(block -> expectedBlock(manifest, block)).toList();
        List<ArchiveBlockRecord> actualBlocks = store.listBlocks(manifest.editionId());
        if (!expectedBlocks.equals(actualBlocks)) {
            throw mismatch("persisted block set/order");
        }
        int persistedParagraphs = 0;
        for (int index = 0; index < expectedBlocks.size(); index++) {
            ArchiveManifest.Block manifestBlock = manifest.blocksInReaderOrder().get(index);
            List<ArchiveParagraphRecord> expectedParagraphs = expectedParagraphs(manifest, manifestBlock);
            List<ArchiveParagraphRecord> actualParagraphs = store.listParagraphs(
                    manifest.editionId(), manifestBlock.blockId());
            if (!expectedParagraphs.equals(actualParagraphs)) {
                throw mismatch("persisted paragraph set/order for " + manifestBlock.blockId());
            }
            persistedParagraphs += actualParagraphs.size();
        }
        if (persistedParagraphs != 3_677 || persistedParagraphs != manifest.readerParagraphCount()) {
            throw mismatch("persisted reader paragraph count");
        }
    }

    private ArchiveEditionRecord expectedEdition(ArchiveManifest manifest, String manifestFileSha256) {
        if (!cn.jia.chat.archive.content.ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256
                .equals(manifestFileSha256)
                || !cn.jia.chat.archive.content.ArchiveManifestLoader.EDITION_ID.equals(manifest.editionId())
                || !cn.jia.chat.archive.content.ArchiveManifestLoader.WORK_ID.equals(manifest.workId())
                || !cn.jia.chat.archive.content.ArchiveManifestLoader.WORK_TITLE.equals(manifest.title())
                || !cn.jia.chat.archive.content.ArchiveManifestLoader.EXPECTED_MANIFEST_SHA256
                .equals(manifest.manifestSha256())
                || !cn.jia.chat.archive.content.ArchiveManifestLoader.EXPECTED_SOURCE_SHA256
                .equals(manifest.source().sha256())
                || manifest.chapterCount() != 120 || manifest.prefaceParagraphCount() != 11
                || manifest.chapterParagraphCount() != 3_666 || manifest.readerParagraphCount() != 3_677) {
            throw mismatch("frozen manifest metadata");
        }
        return new ArchiveEditionRecord(manifest.editionId(), manifest.workId(), "STAGING",
                manifest.source().sha256(), manifest.manifestSha256(), manifestFileSha256,
                manifest.source().utf8ByteLength(), manifest.chapterCount(),
                manifest.prefaceParagraphCount(), manifest.chapterParagraphCount(),
                manifest.readerParagraphCount(), manifest.prefaceUtf8ByteLength(),
                manifest.chapterUtf8ByteLength(), manifest.readerUtf8ByteLength());
    }

    private ArchiveBlockRecord expectedBlock(ArchiveManifest manifest, ArchiveManifest.Block block) {
        return new ArchiveBlockRecord(manifest.editionId(), block.blockId(), block.blockType(),
                block.readerOrdinal(), block.number(), block.title(), block.paragraphCount(),
                block.utf8ByteLength(), ArchiveEtags.blockContentSha256(manifest.manifestSha256(), block));
    }

    private List<ArchiveParagraphRecord> expectedParagraphs(
            ArchiveManifest manifest, ArchiveManifest.Block block) {
        List<ArchiveParagraphRecord> rows = new ArrayList<>(block.paragraphs().size());
        for (int index = 0; index < block.paragraphs().size(); index++) {
            ArchiveManifest.Paragraph paragraph = block.paragraphs().get(index);
            rows.add(new ArchiveParagraphRecord(manifest.editionId(), block.blockId(),
                    paragraph.paragraphId(), index + 1, paragraph.text(), paragraph.utf8ByteLength(),
                    paragraph.sha256()));
        }
        return List.copyOf(rows);
    }

    private ArchiveEditionRecord requireEdition(String editionId) {
        ArchiveEditionRecord edition = store.findEdition(editionId);
        if (edition == null) {
            throw mismatch("edition row");
        }
        return edition;
    }

    private void requireEditionMetadata(ArchiveEditionRecord expected, ArchiveEditionRecord actual) {
        if (actual == null
                || !expected.editionId().equals(actual.editionId())
                || !expected.workId().equals(actual.workId())
                || !("STAGING".equals(actual.importState()) || "READY".equals(actual.importState()))
                || !expected.sourceSha256().equals(actual.sourceSha256())
                || !expected.manifestSha256().equals(actual.manifestSha256())
                || !expected.manifestFileSha256().equals(actual.manifestFileSha256())
                || expected.sourceUtf8ByteLength() != actual.sourceUtf8ByteLength()
                || expected.chapterCount() != actual.chapterCount()
                || expected.prefaceParagraphCount() != actual.prefaceParagraphCount()
                || expected.chapterParagraphCount() != actual.chapterParagraphCount()
                || expected.readerParagraphCount() != actual.readerParagraphCount()
                || expected.prefaceUtf8ByteLength() != actual.prefaceUtf8ByteLength()
                || expected.chapterUtf8ByteLength() != actual.chapterUtf8ByteLength()
                || expected.readerUtf8ByteLength() != actual.readerUtf8ByteLength()) {
            throw mismatch("edition metadata");
        }
    }

    private ArchiveImportException mismatch(String item) {
        return new ArchiveImportException("Archive content mismatch: " + item + "; existing data was not overwritten");
    }
}
