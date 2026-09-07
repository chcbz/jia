package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
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

import java.util.List;
import java.util.Objects;

@Service
public class ArchiveReaderServiceImpl implements ArchiveReaderService {
    private final ArchiveContentStore store;

    public ArchiveReaderServiceImpl(ArchiveContentStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    @Transactional(readOnly = true)
    public ArchiveRepresentation<ArchiveCatalogDTO> catalog() {
        Active active = requireActive(null);
        List<ArchiveBlockRecord> blocks = store.listBlocks(active.edition().editionId());
        require(blocks.size() == 121, "active edition block count drift");
        ArchiveBlockRecord preface = blocks.getFirst();
        require("PREFACE".equals(preface.blockType()) && preface.readerOrdinal() == 0, "active preface drift");
        List<ArchiveBlockSummaryDTO> chapters = blocks.subList(1, blocks.size()).stream()
                .map(this::summary)
                .toList();
        for (int index = 0; index < chapters.size(); index++) {
            ArchiveBlockSummaryDTO chapter = chapters.get(index);
            require("CHAPTER".equals(chapter.blockType())
                    && Integer.valueOf(index + 1).equals(chapter.number()), "active chapter order drift");
        }
        ArchiveEditionRecord edition = active.edition();
        ArchiveActiveEditionDTO activeEdition = new ArchiveActiveEditionDTO(
                edition.editionId(), edition.manifestSha256(), edition.sourceSha256(),
                edition.prefaceParagraphCount(), edition.chapterParagraphCount(),
                edition.readerParagraphCount(), edition.readerUtf8ByteLength(), summary(preface), chapters);
        ArchiveCatalogDTO dto = new ArchiveCatalogDTO(ArchiveEtags.REPRESENTATION_SCHEMA_VERSION,
                active.work().workId(), active.work().title(), activeEdition);
        return new ArchiveRepresentation<>(ArchiveEtags.catalog(edition.manifestSha256()), dto);
    }

    @Override
    @Transactional(readOnly = true)
    public ArchiveRepresentation<ArchiveBlockDTO> preface(String editionId) {
        Active active = requireActive(editionId);
        ArchiveBlockRecord block = store.findBlock(editionId, editionId + "-preface");
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
        ArchiveWorkRecord work = store.findWork(ArchiveManifestLoader.WORK_ID);
        if (work == null || !ArchiveManifestLoader.WORK_TITLE.equals(work.title())
                || work.activeEditionId() == null
                || (requestedEdition != null && !requestedEdition.equals(work.activeEditionId()))) {
            throw new ArchiveResourceNotFoundException();
        }
        ArchiveEditionRecord edition = store.findEdition(work.activeEditionId());
        if (edition == null || !"READY".equals(edition.importState())
                || !work.workId().equals(edition.workId())
                || !ArchiveManifestLoader.EXPECTED_MANIFEST_SHA256.equals(edition.manifestSha256())
                || !ArchiveManifestLoader.EXPECTED_MANIFEST_FILE_SHA256.equals(edition.manifestFileSha256())
                || !ArchiveManifestLoader.EXPECTED_SOURCE_SHA256.equals(edition.sourceSha256())
                || edition.sourceUtf8ByteLength() != 2_716_495L
                || edition.chapterCount() != 120 || edition.prefaceParagraphCount() != 11
                || edition.chapterParagraphCount() != 3_666 || edition.readerParagraphCount() != 3_677
                || edition.prefaceUtf8ByteLength() != 4_035L
                || edition.chapterUtf8ByteLength() != 2_680_317L
                || edition.readerUtf8ByteLength() != 2_684_352L) {
            throw new ArchiveResourceNotFoundException();
        }
        return new Active(work, edition);
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

    private record Active(ArchiveWorkRecord work, ArchiveEditionRecord edition) {
    }
}
