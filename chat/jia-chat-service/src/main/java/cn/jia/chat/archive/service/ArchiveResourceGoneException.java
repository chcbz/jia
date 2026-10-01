package cn.jia.chat.archive.service;

public class ArchiveResourceGoneException extends RuntimeException {
    public ArchiveResourceGoneException() { super("Archive edition was withdrawn"); }
}
