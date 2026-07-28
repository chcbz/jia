package cn.jia.chat.service.impl;

import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.memory.MemoryDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/** Fail-closed barrier between task-thread content and the legacy jiacn-only memory store. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskThreadMemoryGuard {
    private final AgentTaskThreadDao taskThreadDao;

    public boolean isProtectedConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId)
                || !conversationId.equals(conversationId.strip())
                || conversationId.chars().anyMatch(Character::isISOControl)) {
            return true;
        }
        try {
            AgentTaskThreadEntity binding = taskThreadDao.findAnyByConversationId(conversationId);
            return binding != null;
        } catch (RuntimeException exception) {
            log.warn("Unable to prove conversation is outside task-thread scope; denying memory access. conversationId={}",
                    conversationId, exception);
            return true;
        }
    }

    public List<MemoryDocument> excludeProtected(List<MemoryDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .filter(document -> document != null)
                .filter(document -> !isProtectedMemoryDocument(document))
                .toList();
    }

    private boolean isProtectedMemoryDocument(MemoryDocument document) {
        String conversationId = document.getConversationId();
        return StringUtils.hasText(conversationId) && isProtectedConversation(conversationId);
    }
}
