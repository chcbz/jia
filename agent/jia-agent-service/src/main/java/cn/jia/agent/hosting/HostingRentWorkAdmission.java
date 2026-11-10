package cn.jia.agent.hosting;

import cn.jia.agent.service.AgentHostingWorkAdmission;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.entity.EconomyHostingLeaseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class HostingRentWorkAdmission implements AgentHostingWorkAdmission {
    private final EconomyHostingRentMapper mapper;
    private final boolean schemaEnabled;
    public HostingRentWorkAdmission(EconomyHostingRentMapper mapper,
            @Value("${economy.hosting-rent.schema-enabled:false}") boolean schemaEnabled) {
        this.mapper = mapper;
        this.schemaEnabled = schemaEnabled;
    }
    @Override
    public void requireNewWork(String tenantId, String clientId, String agentId) {
        requirePaidPeriod(lease(tenantId, clientId, agentId));
    }

    /** New skill intents only. Accepted operations/recovery do not recheck rental eligibility. */
    public void requireSkillChange(String tenantId, String clientId, String agentId, String action) {
        // All new mutations stop as soon as rent expires, including enable/disable/uninstall.
        // Existing accepted commands continue through their original result/recovery paths.
        requirePaidPeriod(lease(tenantId, clientId, agentId));
    }

    /** Existing operations may finish during arrears/FINISHING. Only a committed real stop
     * fact fences subsequent activation; callers already hold the original runtime/binding roots. */
    public boolean executorStopped(String tenantId, String clientId, String agentId) {
        var current = lease(tenantId, clientId, agentId);
        return current != null && current.getExecutorStoppedAt() != null;
    }

    private EconomyHostingLeaseEntity lease(String tenantId, String clientId, String agentId) {
        if (!schemaEnabled) return null;
        HostingRentHttp.exact(tenantId, 50); HostingRentHttp.exact(clientId, 50); HostingRentHttp.exact(agentId, 100);
        return mapper.selectLatestLeaseForUpdate(tenantId, clientId, agentId);
    }

    private void requirePaidPeriod(EconomyHostingLeaseEntity lease) {
        if (lease == null || "REFUNDED".equals(lease.getStatus())) return;
        if (!"ACTIVE".equals(lease.getStatus()) || lease.getPaidThrough() == null) {
            throw new HostingRentApplicationException(409, "HOSTING_RENT_NOT_ACTIVE");
        }
        if (lease.getPaidThrough() <= System.currentTimeMillis()) {
            throw new HostingRentApplicationException(409, "HOSTING_RENT_RENEWAL_REQUIRED");
        }
    }
}
