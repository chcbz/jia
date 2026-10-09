package cn.jia.chat.archive.conversation;

import java.util.function.Supplier;

/** Short Chat database transaction boundary; callers must not invoke Agent/storage inside it. */
public interface ChatConversationArchiveTransactions {
    <T> T required(Supplier<T> action);
}
