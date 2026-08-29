package cn.jia.chat.archive.model;

/** Public immutable-work row. Deliberately not tenant/client scoped and not a BaseEntity. */
public record ArchiveWorkRecord(String workId, String title, String activeEditionId) {
}
