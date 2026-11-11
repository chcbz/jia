package cn.jia.agent.hosting;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.hosting.HostingRentRenewalService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Explicit operator cutover of existing relationships. No HTTP endpoint, startup scan, enrollment or payment.
 * Writers stay paused by the existing maintenance procedure until every fixed input has completed.
 * A lost ADOPTED reply is retried using the original input; never fabricate service readiness. */
@Service
@Profile("schema-maintenance")
public final class HostingRentTransitionApplication {
    private final HostingRentOwnerResolver owners;
    private final AgentIdentityService identities;
    private final AgentPersonaBindingDao bindings;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeV1Service installations;
    private final ManagedHostingProvisioner provider;
    private final HostingRentRenewalService renewal;
    private final AgentHostingRentProperties prices;
    private final FixedTenantAdmissionPolicy partition;
    private final TransactionTemplate transactions;

    public HostingRentTransitionApplication(HostingRentOwnerResolver owners, AgentIdentityService identities,
            AgentPersonaBindingDao bindings, AgentRuntimeDao runtimes, AgentRuntimeV1Service installations,
            ManagedHostingProvisioner provider, HostingRentRenewalService renewal, AgentHostingRentProperties prices,
            FixedTenantAdmissionPolicy partition, PlatformTransactionManager manager) {
        this.owners = owners; this.identities = identities; this.bindings = bindings; this.runtimes = runtimes;
        this.installations = installations; this.provider = provider; this.renewal = renewal; this.prices = prices;
        this.partition = partition; this.transactions = new TransactionTemplate(manager);
    }
    public record Request(HostingRentHttp.Actor actor, String agentId, String installationId, long cutoverAt) { }
    private record Snapshot(HostingRentRenewalService.Transition terms,
                            ManagedHostingProvisioner.Preparation association, ManagedHostingProvisioner.Candidate candidate) { }

    public String register(Request request) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Transition Runtime adoption must be outside transactions");
        if (request.cutoverAt() <= 0 || request.cutoverAt() > System.currentTimeMillis())
            throw new IllegalArgumentException("Cutover input must contain the actual fixed cutover time");
        var original = transactions.execute(tx -> inspect(request));
        if (!provider.adoptExisting(original.association(), original.candidate()))
            throw new IllegalStateException("Original Runtime adoption unconfirmed; retry the fixed cutover input");
        return transactions.execute(tx -> {
            var current = inspect(request);
            if (!original.equals(current)) throw new IllegalStateException("Original cutover relationship changed");
            return renewal.registerTransition(current.terms());
        });
    }
    private Snapshot inspect(Request request) {
        Objects.requireNonNull(request);
        var actor = Objects.requireNonNull(request.actor());
        partition.requireAdmitted(actor.tenantId(), actor.clientId());
        if (!prices.configured()) throw new IllegalStateException("Hosting prices are not configured");
        HostingRentHttp.exact(request.agentId(), 100); HostingRentHttp.exact(request.installationId(), 100);
        String owner = owners.requireOwner(actor);
        // Same installation -> binding -> rent lock order as normal lifecycle mutations.
        var installation = installations.lockExistingInstallation(actor.tenantId(), actor.clientId(), owner, request.installationId());
        if (installation == null || !request.agentId().equals(installation.canonicalAgentId())) throw changed();
        var identity = identities.requireRegistrationIdentityInScope(actor.tenantId(), actor.clientId(), owner, request.agentId());
        if (!request.agentId().equals(identity.getCanonicalAgentId())) throw changed();
        var binding = bindings.findByIdForUpdate(identity.getBindingId());
        if (binding == null || !Integer.valueOf(1).equals(binding.getStatus()) || !owner.equals(binding.getJiacn())
                || !request.agentId().equals(binding.getAgentId()) || !actor.clientId().equals(binding.getClientId())) throw changed();
        var runtime = runtimes.lockInScope(actor.tenantId(), actor.clientId(), request.agentId());
        if (runtime == null || !Objects.equals(binding.getId(), runtime.getBindingId())
                || !owner.equals(runtime.getOwnerJiacn()) || !request.installationId().equals(runtime.getRuntimeInstallationId())
                || runtime.getRuntimeHostId() == null) throw changed();
        String mode = provider.deploymentMode(actor.tenantId(), actor.clientId(), owner, runtime.getRuntimeHostId());
        if (!"local".equals(mode) && !"server".equals(mode)) throw changed();
        String plan = "local".equals(mode) ? AgentHostingRentProperties.SELF_HOSTED_PLAN_ID : AgentHostingRentProperties.MANAGED_PLAN_ID;
        long version = Long.parseLong("local".equals(mode) ? prices.selfHostedPlanVersion() : prices.planVersion());
        String id = UUID.nameUUIDFromBytes((actor.tenantId() + "\0" + actor.clientId() + "\0" + binding.getId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String lease = "hrl_transition_" + id, intent = "hri_transition_" + id;
        var terms = new HostingRentRenewalService.Transition(actor.scope(), actor.principal(), lease, intent,
                binding.getPersonaCode(), request.agentId(), binding.getId().toString(), plan, version,
                request.cutoverAt(), request.installationId(), installation.manifestSha256());
        var association = new ManagedHostingProvisioner.Preparation(actor.tenantId(), actor.clientId(), owner,
                request.agentId(), intent, lease, binding.getId().toString(), request.cutoverAt());
        var candidate = new ManagedHostingProvisioner.Candidate(request.installationId(), installation.manifestSha256(),
                null, 0, 1, runtime.getRuntimeHostId());
        return new Snapshot(terms, association, candidate);
    }
    private static IllegalStateException changed() { return new IllegalStateException("Existing cutover subject is not authorized"); }
}
