package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.dto.ArchiveActiveEditionDTO;
import cn.jia.chat.archive.dto.ArchiveBlockDTO;
import cn.jia.chat.archive.dto.ArchiveBlockSummaryDTO;
import cn.jia.chat.archive.dto.ArchiveCatalogDTO;
import cn.jia.chat.archive.dto.ArchiveParagraphDTO;
import cn.jia.chat.archive.model.ArchiveBlockRecord;
import cn.jia.chat.archive.model.ArchiveEditionRecord;
import cn.jia.chat.archive.model.ArchiveParagraphRecord;
import cn.jia.chat.archive.model.ArchiveWorkRecord;
import cn.jia.chat.archive.store.ArchiveContentStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
public class ArchiveReaderServiceImpl implements ArchiveReaderService {
    private static final long CATALOG_BLOCK_CACHE_NANOS = Duration.ofSeconds(30).toNanos();

    private final ArchiveContentStore store;
    private volatile CatalogBlocks cachedCatalogBlocks;

    public ArchiveReaderServiceImpl(ArchiveContentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    @Transactional(readOnly = true)
    public cn.jia.chat.archive.dto.ArchiveShelfDTO books() {
        return new cn.jia.chat.archive.dto.ArchiveShelfDTO(store.listPublishedBooks());
    }

    @Override
    @Transactional(readOnly = true)
    public ArchiveRepresentation<ArchiveCatalogDTO> catalog(String workId) {
        Active active = requireActiveWork(workId, null);
        ArchiveEditionRecord edition = active.edition();
        CatalogBlocks blocks = catalogBlocks(edition);
        ArchiveActiveEditionDTO activeEdition = new ArchiveActiveEditionDTO(
                edition.editionId(), edition.manifestSha256(), edition.sourceSha256(),
                edition.prefaceParagraphCount(), edition.chapterParagraphCount(),
                edition.readerParagraphCount(), edition.readerUtf8ByteLength(),
                blocks.preface(), blocks.chapters());
        ArchiveCatalogDTO dto = new ArchiveCatalogDTO(ArchiveEtags.REPRESENTATION_SCHEMA_VERSION,
                active.work().workId(), active.work().title(), activeEdition);
        return new ArchiveRepresentation<>(ArchiveEtags.catalog(edition.manifestSha256()), dto);
    }

    @Override
    @Transactional(readOnly = true)
    public ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId) {
        Active active = requireActive(editionId);
        ArchiveBlockSummaryDTO preface = catalogBlocks(active.edition()).preface();
        if (preface == null) throw new ArchiveResourceNotFoundException();
        ArchiveBlockRecord block = store.findBlock(editionId, preface.blockId());
        if (block == null || !"PREFACE".equals(block.blockType()) || block.readerOrdinal() != 0
                || block.chapterNumber() != null) {
            throw new ArchiveResourceNotFoundException();
        }
        return representation(active.edition(), block);
    }

    @Override
    @Transactional(readOnly = true)
    public ArchiveRepresentation<ArchiveBlockDTO> chapter(String editionId, String chapterId) {
        Active active = requireActive(editionId);
        ArchiveBlockRecord block = store.findBlock(editionId, chapterId);
        if (block == null || !"CHAPTER".equals(block.blockType()) || block.chapterNumber() == null
                || block.readerOrdinal() != block.chapterNumber()) {
            throw new ArchiveResourceNotFoundException();
        }
        return representation(active.edition(), block);
    }

    private Active requireActive(String requestedEdition) {
        ArchiveEditionRecord edition = store.findEdition(requestedEdition);
        if (edition == null) throw new ArchiveResourceNotFoundException();
        return requireActiveWork(edition.workId(), requestedEdition);
    }

    private Active requireActiveWork(String workId, String requestedEdition) {
        ArchiveContentStore.ActiveContent activeContent = store.findActiveContent(workId);
        if (activeContent == null || activeContent.work() == null || activeContent.edition() == null) {
            throw new ArchiveResourceNotFoundException();
        }
        ArchiveWorkRecord work = activeContent.work();
        ArchiveEditionRecord edition = activeContent.edition();
        if (!Objects.equals(workId, work.workId()) || work.activeEditionId() == null
                || !Objects.equals(work.activeEditionId(), edition.editionId())
                || (requestedEdition != null && !requestedEdition.equals(work.activeEditionId()))
                || !"READY".equals(edition.importState()) || !work.workId().equals(edition.workId())
                || !digest(edition.manifestSha256()) || !digest(edition.manifestFileSha256())
                || !digest(edition.sourceSha256()) || edition.sourceUtf8ByteLength() <= 0
                || edition.chapterCount() < 1 || edition.prefaceParagraphCount() < 0
                || edition.chapterParagraphCount() < 1 || edition.prefaceUtf8ByteLength() < 0
                || (edition.prefaceParagraphCount() == 0) != (edition.prefaceUtf8ByteLength() == 0)
                || edition.chapterUtf8ByteLength() <= 0
                || (long) edition.readerParagraphCount() != (long) edition.prefaceParagraphCount() + edition.chapterParagraphCount()
                || edition.readerUtf8ByteLength() != edition.prefaceUtf8ByteLength() + edition.chapterUtf8ByteLength()) {
            throw new ArchiveResourceNotFoundException();
        }
        return new Active(work, edition);
    }

    private boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private CatalogBlocks catalogBlocks(ArchiveEditionRecord edition) {
        long now = System.nanoTime();
        CatalogBlocks current = cachedCatalogBlocks;
        if (current != null && current.matches(edition)
                && now - current.loadedAtNanos() >= 0
                && now - current.loadedAtNanos() < CATALOG_BLOCK_CACHE_NANOS) {
            return current;
        }
        List<ArchiveBlockRecord> rows = store.listBlocks(edition.editionId());
        boolean hasPreface = edition.prefaceParagraphCount() > 0;
        require(rows.size() == edition.chapterCount() + (hasPreface ? 1 : 0), "active edition block count drift");
        ArchiveBlockRecord preface = hasPreface ? rows.getFirst() : null;
        require(preface == null || (edition.editionId().equals(preface.editionId()) && "PREFACE".equals(preface.blockType()) && preface.readerOrdinal() == 0
                && preface.chapterNumber() == null && preface.paragraphCount() == edition.prefaceParagraphCount()
                && preface.utf8ByteLength() == edition.prefaceUtf8ByteLength()), "active preface drift");
        List<ArchiveBlockRecord> chapterRows = rows.subList(hasPreface ? 1 : 0, rows.size());
        long paragraphs = 0;
        long bytes = 0;
        for (int index = 0; index < chapterRows.size(); index++) {
            ArchiveBlockRecord chapter = chapterRows.get(index);
            require(edition.editionId().equals(chapter.editionId()) && "CHAPTER".equals(chapter.blockType())
                    && chapter.readerOrdinal() == index + 1 && Integer.valueOf(index + 1).equals(chapter.chapterNumber())
                    && chapter.paragraphCount() > 0 && chapter.utf8ByteLength() > 0, "active chapter order drift");
            paragraphs += chapter.paragraphCount();
            bytes = Math.addExact(bytes, chapter.utf8ByteLength());
        }
        require(paragraphs == edition.chapterParagraphCount() && bytes == edition.chapterUtf8ByteLength(),
                "active chapter metrics drift");
        List<ArchiveBlockSummaryDTO> chapters = chapterRows.stream().map(this::summary).toList();
        CatalogBlocks loaded = new CatalogBlocks(edition.editionId(), edition.manifestSha256(),
                now, preface == null ? null : summary(preface), chapters);
        cachedCatalogBlocks = loaded;
        return loaded;
    }

    private ArchiveRepresentation<ArchiveBlockDTO> representation(
            ArchiveEditionRecord edition, ArchiveBlockRecord block) {
        List<ArchiveParagraphRecord> rows = store.listParagraphs(edition.editionId(), block.blockId());
        require(rows.size() == block.paragraphCount(), "active paragraph count drift");
        List<ArchiveParagraphDTO> paragraphs = rows.stream().map(row -> new ArchiveParagraphDTO(
                row.paragraphId(), row.ordinal(), row.text(), row.utf8ByteLength(), row.sha256())).toList();
        for (int index = 0; index < rows.size(); index++) {
            require(rows.get(index).ordinal() == index + 1, "active paragraph order drift");
        }
        ArchiveBlockDTO dto = new ArchiveBlockDTO(ArchiveEtags.REPRESENTATION_SCHEMA_VERSION,
                edition.editionId(), edition.manifestSha256(), block.blockType(), block.blockId(),
                block.chapterNumber(), block.title(), block.paragraphCount(), block.utf8ByteLength(), paragraphs);
        return new ArchiveRepresentation<>(ArchiveEtags.blockFromDigest(block.blockContentSha256()), dto);
    }

    private ArchiveBlockSummaryDTO summary(ArchiveBlockRecord block) {
        return new ArchiveBlockSummaryDTO(block.blockType(), block.blockId(), block.chapterNumber(),
                block.title(), block.paragraphCount(), block.utf8ByteLength(),
                ArchiveEtags.blockFromDigest(block.blockContentSha256()));
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Active(ArchiveWorkRecord work, ArchiveEditionRecord edition) { }

    private record CatalogBlocks(String editionId, String manifestSha256, long loadedAtNanos,
            ArchiveBlockSummaryDTO preface, List<ArchiveBlockSummaryDTO> chapters) {
        private CatalogBlocks {
            chapters = List.copyOf(chapters);
        }

        private boolean matches(ArchiveEditionRecord edition) {
            return Objects.equals(editionId, edition.editionId())
                    && Objects.equals(manifestSha256, edition.manifestSha256());
        }
    }
}
