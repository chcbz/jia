package cn.jia.chat.archive.service;

public class ArchiveResourceNotFoundException extends RuntimeException {
    public ArchiveResourceNotFoundException() {
        super("Archive resource is not available");
    }
}
