package cn.jia.chat.archive.service;

import java.util.function.Supplier;

public interface ArchiveTransactions {
    <T> T required(Supplier<T> action);
}
