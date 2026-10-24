package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** Internal transaction primitive. No HTTP exposure: operator ACL, audit and idempotency are still pending. */
final class TenantGrantRevocation {
    private final JdbcOperations jdbc;
    private final TransactionTemplate transactions;

    TenantGrantRevocation(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        if (!(jdbc instanceof JdbcTemplate template) || template.getDataSource() == null) {
            throw new IllegalArgumentException("Revocation requires a transactional DataSource");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(template.getDataSource()));
        // Join the caller's operator-audit transaction once this primitive is wired
        // into a management service; a failed audit must also roll back the grant.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    long revokeMember(TenantId tenant, long uid, long expectedRevision) {
        Objects.requireNonNull(tenant);
        if (uid <= 0) throw new IllegalArgumentException("Invalid uid");
        requireRevision(expectedRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            GrantRow member = lockGrant("SELECT tenant_id, status, membership_revision "
                    + "FROM core_tenant_membership WHERE tenant_id = ? AND uid = ? FOR UPDATE",
                    tenant.value(), tenant.value(), uid);
            long next = nextRevision(member, expectedRevision);
            changed(jdbc.update("UPDATE core_tenant_membership SET status = 'REVOKED', membership_revision = ? "
                    + "WHERE tenant_id = ? AND uid = ? AND membership_revision = ? AND status = 'ACTIVE'",
                    next, tenant.value(), uid, expectedRevision));
            return next;
        });
    }

    long revokeClient(TenantId tenant, String clientId, long expectedRevision) {
        Objects.requireNonNull(tenant);
        requireClientId(clientId);
        requireRevision(expectedRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            lockClient(clientId);
            GrantRow grant = lockGrant("SELECT client_id, status, grant_revision "
                    + "FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, tenant.value());
            long next = nextRevision(grant, expectedRevision);
            revokeActiveDomains(tenant, clientId);
            changed(jdbc.update("UPDATE oauth_client_tenant_grant SET status = 'REVOKED', grant_revision = ? "
                    + "WHERE client_id = ? AND tenant_id = ? AND grant_revision = ? AND status = 'ACTIVE'",
                    next, clientId, tenant.value(), expectedRevision));
            return next;
        });
    }

    /** Child changes invalidate all tokens bound to the parent revision as well. */
    long revokeDomain(TenantId tenant, String clientId, DataDomainId domain,
            long expectedParentRevision, long expectedDomainRevision) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(domain);
        requireClientId(clientId);
        requireRevision(expectedParentRevision);
        requireRevision(expectedDomainRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            lockClient(clientId);
            GrantRow parent = lockGrant("SELECT client_id, status, grant_revision "
                    + "FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, tenant.value());
            GrantRow child = lockDomainGrant(tenant, clientId, domain);
            long parentNext = nextRevision(parent, expectedParentRevision);
            long childNext = nextRevision(child, expectedDomainRevision);
            changed(jdbc.update("UPDATE oauth_client_tenant_domain_grant "
                    + "SET status = 'REVOKED', domain_revision = ? WHERE client_id = ? AND tenant_id = ? "
                    + "AND data_domain_id = ? AND domain_revision = ? AND status = 'ACTIVE'",
                    childNext, clientId, tenant.value(), domain.value(), expectedDomainRevision));
            changed(jdbc.update("UPDATE oauth_client_tenant_grant SET grant_revision = ? "
                    + "WHERE client_id = ? AND tenant_id = ? AND grant_revision = ?",
                    parentNext, clientId, tenant.value(), expectedParentRevision));
            return parentNext;
        });
    }

    private void lockControl(TenantId tenant) {
        requireSingle("SELECT tenant_id FROM core_tenant_control WHERE tenant_id = ? FOR UPDATE",
                tenant.value(), tenant.value());
    }
    /** Re-enable an existing tombstone; creating a first grant requires a separate audited bootstrap. */
    long activateMember(TenantId tenant, long uid, long expectedRevision) {
        Objects.requireNonNull(tenant);
        if (uid <= 0) throw new IllegalArgumentException("Invalid uid");
        requireRevision(expectedRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            GrantRow member = lockGrant("SELECT tenant_id, status, membership_revision "
                    + "FROM core_tenant_membership WHERE tenant_id = ? AND uid = ? FOR UPDATE",
                    tenant.value(), tenant.value(), uid);
            long next = nextRevokedRevision(member, expectedRevision);
            changed(jdbc.update("UPDATE core_tenant_membership SET status = 'ACTIVE', membership_revision = ? "
                    + "WHERE tenant_id = ? AND uid = ? AND membership_revision = ? AND status = 'REVOKED'",
                    next, tenant.value(), uid, expectedRevision));
            return next;
        });
    }

    /** Parent reactivation does not restore previously revoked child domain grants. */
    long activateClient(TenantId tenant, String clientId, long expectedRevision) {
        Objects.requireNonNull(tenant);
        requireClientId(clientId);
        requireRevision(expectedRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            lockClient(clientId);
            GrantRow parent = lockGrant("SELECT client_id, status, grant_revision "
                    + "FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, tenant.value());
            long next = nextRevokedRevision(parent, expectedRevision);
            // A legacy revocation may have left ACTIVE children: never grant those implicitly.
            List<String> active = jdbc.queryForList("SELECT data_domain_id FROM oauth_client_tenant_domain_grant "
                    + "WHERE client_id = ? AND tenant_id = ? AND status = 'ACTIVE' FOR UPDATE",
                    String.class, clientId, tenant.value());
            if (!active.isEmpty()) throw new IllegalStateException("Client reactivation requires revoked child domains");
            changed(jdbc.update("UPDATE oauth_client_tenant_grant SET status = 'ACTIVE', grant_revision = ? "
                    + "WHERE client_id = ? AND tenant_id = ? AND grant_revision = ? AND status = 'REVOKED'",
                    next, clientId, tenant.value(), expectedRevision));
            return next;
        });
    }

    long activateDomain(TenantId tenant, String clientId, DataDomainId domain,
            long expectedParentRevision, long expectedDomainRevision) {
        Objects.requireNonNull(tenant);
        Objects.requireNonNull(domain);
        requireClientId(clientId);
        requireRevision(expectedParentRevision);
        requireRevision(expectedDomainRevision);
        return transactions.execute(status -> {
            lockControl(tenant);
            lockClient(clientId);
            GrantRow parent = lockGrant("SELECT client_id, status, grant_revision "
                    + "FROM oauth_client_tenant_grant WHERE client_id = ? AND tenant_id = ? FOR UPDATE",
                    clientId, clientId, tenant.value());
            GrantRow child = lockDomainGrant(tenant, clientId, domain);
            long parentNext = nextRevision(parent, expectedParentRevision);
            long childNext = nextRevokedRevision(child, expectedDomainRevision);
            changed(jdbc.update("UPDATE oauth_client_tenant_domain_grant "
                    + "SET status = 'ACTIVE', domain_revision = ? WHERE client_id = ? AND tenant_id = ? "
                    + "AND data_domain_id = ? AND domain_revision = ? AND status = 'REVOKED'",
                    childNext, clientId, tenant.value(), domain.value(), expectedDomainRevision));
            changed(jdbc.update("UPDATE oauth_client_tenant_grant SET grant_revision = ? "
                    + "WHERE client_id = ? AND tenant_id = ? AND grant_revision = ? AND status = 'ACTIVE'",
                    parentNext, clientId, tenant.value(), expectedParentRevision));
            return parentNext;
        });
    }

    private void revokeActiveDomains(TenantId tenant, String clientId) {
        List<DomainRow> domains = jdbc.query("SELECT client_id, tenant_id, data_domain_id, status, domain_revision "
                + "FROM oauth_client_tenant_domain_grant WHERE client_id = ? AND tenant_id = ? "
                + "ORDER BY data_domain_id FOR UPDATE",
                (rs, index) -> new DomainRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.wasNull()), clientId, tenant.value());
        for (DomainRow domain : domains) {
            if (!clientId.equals(domain.clientId()) || !tenant.value().equals(domain.tenantId())
                    || domain.nullRevision() || domain.revision() < 0 || domain.revision() == Long.MAX_VALUE) {
                throw new IllegalStateException("Ambiguous or exhausted child grant");
            }
            if ("ACTIVE".equals(domain.status())) {
                changed(jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status = 'REVOKED', domain_revision = ? "
                        + "WHERE client_id = ? AND tenant_id = ? AND data_domain_id = ? "
                        + "AND domain_revision = ? AND status = 'ACTIVE'",
                        domain.revision() + 1, clientId, tenant.value(), domain.domainId(), domain.revision()));
            } else if (!"REVOKED".equals(domain.status())) {
                throw new IllegalStateException("Invalid child grant status");
            }
        }
    }

    private GrantRow lockDomainGrant(TenantId tenant, String clientId, DataDomainId domain) {
        // Legacy collations may fold WEB/web and CYF/cyf: compare every returned key exactly.
        List<DomainRow> rows = jdbc.query("SELECT client_id, tenant_id, data_domain_id, status, domain_revision "
                + "FROM oauth_client_tenant_domain_grant WHERE client_id = ? AND tenant_id = ? "
                + "AND data_domain_id = ? FOR UPDATE",
                (rs, index) -> new DomainRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.wasNull()),
                clientId, tenant.value(), domain.value());
        if (rows.size() != 1) throw new IllegalStateException("Missing or ambiguous domain grant");
        DomainRow row = rows.get(0);
        if (!clientId.equals(row.clientId()) || !tenant.value().equals(row.tenantId())
                || !domain.value().equals(row.domainId()) || row.nullRevision()) {
            throw new IllegalStateException("Domain grant key does not match exactly");
        }
        return new GrantRow(row.domainId(), row.status(), row.revision(), false);
    }

    private void lockClient(String clientId) {
        // Existing oauth_client.client_id has no proven unique binary index.
        requireSingle("SELECT client_id FROM oauth_client WHERE client_id = ? FOR UPDATE", clientId, clientId);
    }

    private void requireSingle(String query, String expected, Object... args) {
        List<String> rows = jdbc.queryForList(query, String.class, args);
        if (rows.size() != 1 || !expected.equals(rows.get(0))) {
            throw new IllegalStateException("Missing or ambiguous authorization lock key");
        }
    }

    private GrantRow lockGrant(String query, String expected, Object... args) {
        List<GrantRow> rows = jdbc.query(query, (rs, index) -> new GrantRow(
                rs.getString(1), rs.getString(2), rs.getLong(3), rs.wasNull()), args);
        if (rows.size() != 1 || !expected.equals(rows.get(0).key()) || rows.get(0).nullRevision()) {
            throw new IllegalStateException("Missing or ambiguous authorization grant");
        }
        return rows.get(0);
    }

    private static long nextRevision(GrantRow row, long expected) {
        if (row.revision() != expected || !"ACTIVE".equals(row.status())) {
            throw new IllegalStateException("Stale or already revoked grant");
        }
        if (expected == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
        return expected + 1;
    }

    private static long nextRevokedRevision(GrantRow row, long expected) {
        if (row.revision() != expected || !"REVOKED".equals(row.status())) {
            throw new IllegalStateException("Stale or already active grant");
        }
        if (expected == Long.MAX_VALUE) throw new IllegalStateException("Revision exhausted");
        return expected + 1;
    }

    private record DomainRow(String clientId, String tenantId, String domainId,
                             String status, long revision, boolean nullRevision) {}

    private static void changed(int rows) {
        if (rows != 1) throw new IllegalStateException("Authorization revision conflict");
    }

    private static void requireRevision(long revision) {
        if (revision < 0) throw new IllegalArgumentException("Invalid expected revision");
    }

    private static void requireClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Invalid clientId");
    }

    private record GrantRow(String key, String status, long revision, boolean nullRevision) {}
}
