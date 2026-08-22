package cn.jia.chat.archive.service;

public class ArchiveQuestionProviderException extends Exception {
    private final String code;
    private final boolean retryable;

    public ArchiveQuestionProviderException(String code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
