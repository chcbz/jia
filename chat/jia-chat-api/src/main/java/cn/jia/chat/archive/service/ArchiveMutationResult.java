package cn.jia.chat.archive.service;

public record ArchiveMutationResult(int status, String contentType, byte[] body, boolean replayed) {
    public ArchiveMutationResult {
        body = body.clone();
    }
    @Override public byte[] body() { return body.clone(); }
}
