package cn.jia.chat.archive.content;

public class ArchiveContentException extends IllegalStateException {
    public ArchiveContentException(String message) {
        super(message);
    }

    public ArchiveContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
