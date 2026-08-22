package cn.jia.chat.archive.service;

import cn.jia.chat.archive.content.ArchiveEtags;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.dto.ArchiveAnchorSegmentDTO;
import cn.jia.chat.archive.dto.ArchiveTextAnchorDTO;
import cn.jia.chat.archive.store.ArchivePersonalDataStore;
import cn.jia.chat.archive.store.ArchivePersonalDataStore.ActiveEdition;
import cn.jia.chat.archive.store.ArchivePersonalDataStore.ContentPoint;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ArchiveTextSelectionValidator {
    private final ArchivePersonalDataStore content;

    ArchiveTextSelectionValidator(ArchivePersonalDataStore content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    Selection reconstruct(ArchiveTextAnchorDTO anchor) {
        requireShape(anchor);
        String editionId = ArchiveManifestLoader.EDITION_ID;
        ActiveEdition active = content.lockActiveEdition(editionId);
        require(active != null, 404, "ARCHIVE_RESOURCE_NOT_FOUND", "Archive resource is not available");
        require(active.manifestSha256().equals(anchor.editionManifestSha256()),
                422, "CONTENT_HASH_MISMATCH", "Edition manifest hash mismatch");
        String expectedBlock = "PREFACE".equals(anchor.blockType())
                ? editionId + "-preface" : anchor.blockId();
        require(expectedBlock.equals(anchor.blockId())
                        && ("PREFACE".equals(anchor.blockType())
                        || anchor.blockId().matches(java.util.regex.Pattern.quote(editionId) + "-c(?:00[1-9]|0[1-9][0-9]|1[01][0-9]|120)")),
                422, "INVALID_TEXT_ANCHOR", "Text anchor block is invalid");

        List<ContentPoint> rows = content.lockBlockParagraphs(editionId, anchor.blockId(),
                anchor.segments().stream().map(ArchiveAnchorSegmentDTO::paragraphId).toList());
        Map<String, ContentPoint> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.paragraphId(), row));
        List<byte[]> slices = new ArrayList<>();
        int previousOrdinal = -1;
        int total = 0;
        for (int index = 0; index < anchor.segments().size(); index++) {
            ArchiveAnchorSegmentDTO segment = anchor.segments().get(index);
            ContentPoint point = byId.get(segment.paragraphId());
            require(point != null && point.editionId().equals(editionId)
                            && point.manifestSha256().equals(active.manifestSha256())
                            && point.blockId().equals(anchor.blockId())
                            && point.blockType().equals(anchor.blockType())
                            && point.paragraphSha256().equals(segment.paragraphSha256())
                            && (previousOrdinal < 0 || point.paragraphOrdinal() == previousOrdinal + 1),
                    422, "INVALID_TEXT_ANCHOR", "Text anchor paragraph sequence is invalid");
            byte[] authoritative = point.text().getBytes(StandardCharsets.UTF_8);
            require(authoritative.length == point.utf8ByteLength()
                            && ArchiveEtags.sha256(authoritative).equals(point.paragraphSha256()),
                    422, "CONTENT_HASH_MISMATCH", "Authoritative paragraph hash mismatch");
            require(boundary(authoritative, segment.startByte()) && boundary(authoritative, segment.endByte())
                            && segment.startByte() < segment.endByte(),
                    422, "INVALID_TEXT_ANCHOR", "Text anchor byte range is invalid");
            if (index > 0 && index < anchor.segments().size() - 1) {
                require(segment.startByte() == 0 && segment.endByte() == authoritative.length,
                        422, "INVALID_TEXT_ANCHOR", "Intermediate segments must be complete paragraphs");
            }
            byte[] slice = java.util.Arrays.copyOfRange(authoritative,
                    Math.toIntExact(segment.startByte()), Math.toIntExact(segment.endByte()));
            int separatorBytes = index == 0 ? 0 : 2;
            require(slice.length <= 8192 - total - separatorBytes,
                    422, "INVALID_TEXT_ANCHOR", "Selected text exceeds 8192 UTF-8 bytes");
            total += slice.length + separatorBytes;
            slices.add(slice);
            previousOrdinal = point.paragraphOrdinal();
        }
        byte[] joined = new byte[total];
        int offset = 0;
        for (int index = 0; index < slices.size(); index++) {
            if (index > 0) { joined[offset++] = '\n'; joined[offset++] = '\n'; }
            byte[] slice = slices.get(index);
            System.arraycopy(slice, 0, joined, offset, slice.length);
            offset += slice.length;
        }
        require(joined.length > 0, 422, "INVALID_TEXT_ANCHOR", "Selected text is empty");
        require(ArchiveEtags.sha256(joined).equals(anchor.selectionSha256()),
                422, "CONTENT_HASH_MISMATCH", "Selection hash mismatch");
        return new Selection(editionId, active.manifestSha256(), anchor.blockType(), anchor.blockId(),
                new String(joined, StandardCharsets.UTF_8));
    }

    private void requireShape(ArchiveTextAnchorDTO anchor) {
        require(anchor != null && anchor.editionManifestSha256() != null && anchor.blockType() != null
                        && anchor.blockId() != null && anchor.segments() != null
                        && !anchor.segments().isEmpty() && anchor.segments().size() <= 16
                        && anchor.selectionSha256() != null,
                422, "INVALID_REQUEST_JSON", "Text anchor is incomplete");
        for (ArchiveAnchorSegmentDTO segment : anchor.segments()) {
            require(segment != null && segment.paragraphId() != null && segment.startByte() != null
                            && segment.endByte() != null && segment.paragraphSha256() != null,
                    422, "INVALID_REQUEST_JSON", "Text anchor segment is incomplete");
        }
    }

    private boolean boundary(byte[] bytes, long offset) {
        return offset >= 0 && offset <= bytes.length
                && (offset == bytes.length || (bytes[Math.toIntExact(offset)] & 0xc0) != 0x80);
    }

    private void require(boolean condition, int status, String code, String message) {
        if (!condition) throw new ArchivePersonalDataException(status, code, message);
    }

    record Selection(String editionId, String manifestSha256, String blockType,
                     String blockId, String selectedText) { }
}
