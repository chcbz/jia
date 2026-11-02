package cn.jia.agent.service.impl;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import cn.jia.agent.mapper.NativeConversationWorkMapper;
import cn.jia.agent.service.NativeConversationWorkDiscovery;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

@Slf4j
@Named
public final class NativeConversationWorkDiscoveryImpl implements NativeConversationWorkDiscovery {
    private final NativeConversationWorkMapper mapper;
    private final FixedTenantAdmissionPolicy partition;
    private final ApplicationEventPublisher events;

    @Inject
    public NativeConversationWorkDiscoveryImpl(NativeConversationWorkMapper mapper,
            FixedTenantAdmissionPolicy partition, ApplicationEventPublisher events) {
        this.mapper=mapper; this.partition=partition; this.events=events;
    }
    @Override public void afterCommit(NativeConversationWorkAvailable target) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { events.publishEvent(target); }
                catch (RuntimeException failure) { log.warn("Native work hint unavailable; durable discovery retained", failure); }
            }
        });
    }
    @Override public List<NativeConversationWorkAvailable> pendingFor(List<NativeConversationWorkAvailable> targets) {
        var admitted=targets.stream().filter(target -> partition.businessTenantId().equals(target.tenantId())
                && (partition.clientId()==null || partition.clientId().equals(target.clientId()))).distinct().toList();
        if (admitted.isEmpty()) return List.of();
        return mapper.pendingFor(partition.businessTenantId(),admitted);
    }
}
