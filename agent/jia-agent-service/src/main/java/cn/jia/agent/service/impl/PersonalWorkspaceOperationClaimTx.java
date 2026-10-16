package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService.Claim;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService.Scope;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Separate proxy boundaries ensure a uniqueness race never reuses a stale RR snapshot. */
@Named
public class PersonalWorkspaceOperationClaimTx {
    private final PersonalWorkspaceDao dao;

    @Inject public PersonalWorkspaceOperationClaimTx(PersonalWorkspaceDao dao) { this.dao = dao; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Claim claimOnce(Scope scope, String type, String key, String requestHash) {
        var existing = dao.findOperationByIdempotency(scope.tenantId(), scope.clientId(),
                scope.ownerJiacn(), type, key);
        if (existing != null) return existingClaim(existing, requestHash);
        var operation = new PersonalWorkspaceOperationEntity()
                .setOperationId("pwo_" + UUID.randomUUID().toString().replace("-", ""))
                .setOwnerJiacn(scope.ownerJiacn()).setOperationType(type).setIdempotencyKey(key)
                .setRequestHash(requestHash).setState("PROCESSING").setCreatedAt(System.currentTimeMillis());
        operation.setTenantId(scope.tenantId());
        operation.setClientId(scope.clientId());
        dao.insertOperation(operation);
        return new Claim(operation, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PersonalWorkspaceOperationEntity findAfterRace(Scope scope, String type, String key) {
        return dao.findOperationByIdempotency(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), type, key);
    }

    static Claim existingClaim(PersonalWorkspaceOperationEntity existing, String requestHash) {
        if (!requestHash.equals(existing.getRequestHash())) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.IDEMPOTENCY_CONFLICT);
        }
        return new Claim(existing, false);
    }
}
