package cn.jia.chat.archive.service;

public class ArchivePersonalDataException extends RuntimeException {
    private final int status;
    private final String code;
    private final String currentVersion;

    public ArchivePersonalDataException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ArchivePersonalDataException(int status, String code, String message, String currentVersion) {
        super(message);
        this.status = status;
        this.code = code;
        this.currentVersion = currentVersion;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String currentVersion() { return currentVersion; }
}
