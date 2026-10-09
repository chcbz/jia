package cn.jia.chat.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Durable, lease-based long-term-memory work item. */
@Data
@Accessors(chain = true)
public class MemoryWorkItem {
    private Long id;
    private String tenantId;
    private String scopeMode;
    private String scopeKey;
    private String ownerJiacn;
    private String conversationId;
    private Long lifecycleGeneration;
    private String state;
    private Long dueAt;
    private String leaseOwner;
    private Long leaseUntil;
    private Integer attempts;
    private Boolean wakeRequested;
    private String lastError;
    private Long createTime;
    private Long updateTime;

    public ConversationDataScope toConversationScope(String callerClientId) {
        String clientId = ConversationDataScope.Mode.LEGACY_CLIENT.name().equals(scopeMode)
                ? scopeKey : callerClientId;
        String domainId = ConversationDataScope.Mode.DOMAIN.name().equals(scopeMode)
                ? scopeKey : "cyf";
        return new ConversationDataScope(
                new cn.jia.core.tenant.BusinessScope(
                        cn.jia.core.tenant.TenantId.of(tenantId),
                        cn.jia.core.tenant.DataDomainId.of(domainId),
                        ownerJiacn, clientId),
                ConversationDataScope.Mode.valueOf(scopeMode));
    }
}
