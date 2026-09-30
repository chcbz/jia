package cn.jia.chat.archive.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "chat.conversation-archive", name = "enabled", havingValue = "true")
public final class SpringChatConversationArchiveTransactions implements ChatConversationArchiveTransactions {
    private final TransactionTemplate transactions;

    public SpringChatConversationArchiveTransactions(PlatformTransactionManager manager) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
    }

    @Override
    public <T> T required(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
