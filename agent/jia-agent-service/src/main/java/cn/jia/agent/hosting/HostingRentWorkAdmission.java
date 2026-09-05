package cn.jia.agent.hosting;

import cn.jia.agent.service.AgentHostingWorkAdmission;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
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
        if (!schemaEnabled) return; // No hosting schema/managed leases in unchanged installations.
        HostingRentHttp.exact(tenantId, 50); HostingRentHttp.exact(clientId, 50); HostingRentHttp.exact(agentId, 100);
        var lease = mapper.selectLatestLease(tenantId, clientId, agentId);
        if (lease == null || "REFUNDED".equals(lease.getStatus())) return; // Historical/local Agent is not charged.
        if (!"ACTIVE".equals(lease.getStatus()) || lease.getPaidThrough() == null) {
            throw new HostingRentApplicationException(409, "HOSTING_RENT_NOT_ACTIVE");
        }
        if (lease.getPaidThrough() <= System.currentTimeMillis()) {
            throw new HostingRentApplicationException(409, "HOSTING_RENT_RENEWAL_REQUIRED");
        }
    }
}
