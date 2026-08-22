package cn.jia.chat.archive.service;

import java.util.function.Supplier;

public interface ArchiveTransactions {
    <T> T required(Supplier<T> action);

    default void afterCommit(Runnable action) {
        throw new IllegalStateException("Archive afterCommit is not supported by this transaction seam");
    }
}
