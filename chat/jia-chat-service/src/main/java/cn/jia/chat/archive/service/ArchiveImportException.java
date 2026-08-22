package cn.jia.chat.archive.service;

public class ArchiveImportException extends IllegalStateException {
    public ArchiveImportException(String message) {
        super(message);
    }

    public ArchiveImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
