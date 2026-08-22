package cn.jia.chat.archive.service;

public record ArchiveRepresentation<T>(String etag, T data) {
}
