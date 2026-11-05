package cn.jia.chat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** One snapshot for a message window and its display associations; no write-side replay. */
@Service
@ConditionalOnProperty(prefix = "chat.bounty-media", name = "enabled", havingValue = "true")
public class ChatHallMessagePageService {
    private final ChatConversationService conversations;
    private final ChatBountyRequestIndexService requests;
    private final ChatMessageOutcomeReadService outcomes;

    public ChatHallMessagePageService(ChatConversationService conversations, ChatBountyRequestIndexService requests, ChatMessageOutcomeReadService outcomes) {
        this.conversations = conversations;
        this.requests = requests;
        this.outcomes = outcomes;
    }

    public record Page(ChatConversationService.MessagePage messages, ChatBountyRequestIndexService.Page requestPage, java.util.List<java.util.Map<String, Object>> outcomeSummaries) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page read(String conversationId, String cursor) {
        var page = conversations.findMessagePage(conversationId, cursor);
        var requestPage = requests.messageWindow(page, cursor == null);
        if (requestPage == null) return new Page(page, null, java.util.List.of());
        var conversation = page.conversation();
        var scope = new cn.jia.chat.deliberation.ChatBountyRequestIndexStore.Scope(conversation.getTenantId(),
                conversation.getJiacn(), conversation.getClientId(), conversationId, conversation.getLifecycleGeneration());
        var messageIds = page.messages().stream().map(message -> Long.toString(message.getId())).collect(java.util.stream.Collectors.toSet());
        var typed = outcomes.read(scope, requestPage.entries().stream().map(entry -> entry.request().requestId()).toList())
                .stream().filter(projection -> !(projection.get("outcome") instanceof java.util.Map<?, ?> outcome)
                        || messageIds.contains(outcome.get("assistantMessageId"))).toList();
        var children = new java.util.LinkedHashSet<String>();
        for (var projection : typed) for (var name : java.util.List.of("actionProgress", "reviewProgress")) {
            if (projection.get(name) instanceof java.util.Map<?, ?> progress && progress.get("childRequestId") instanceof String child)
                children.add(child);
        }
        requestPage = requests.withDirectChildren(scope, requestPage, java.util.List.copyOf(children));
        return new Page(page, requestPage, typed);
    }
}
