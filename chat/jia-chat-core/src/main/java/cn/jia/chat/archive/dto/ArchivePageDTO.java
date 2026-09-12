package cn.jia.chat.archive.dto;

import java.util.List;

public record ArchivePageDTO<T>(List<T> items, String nextCursor) { }
