package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Read-only eligibility lookup; atomic selection and revocation still require a control-plane transaction. */
@Repository
public class JdbcTenantAuthorizationRepository implements TenantAuthorizationVerifier {
    private static final String SELECT_ELIGIBLE = """
            SELECT t.tenant_id, t.tenant_name, t.tenant_kind,
                   c.tenant_id AS control_tenant, c.state AS control_state, c.tenant_session_epoch,
                   m.tenant_id AS member_tenant, m.uid, m.status AS member_status, m.membership_revision,
                   g.tenant_id AS grant_tenant, g.client_id, g.status AS grant_status, g.grant_revision,
                   d.tenant_id AS domain_tenant, d.client_id AS domain_client, d.data_domain_id, d.status AS domain_status
              FROM core_tenant t
              JOIN core_tenant_control c ON c.tenant_id = t.tenant_id
              JOIN core_tenant_membership m ON m.tenant_id = t.tenant_id
              JOIN oauth_client_tenant_grant g ON g.tenant_id = t.tenant_id
              JOIN oauth_client_tenant_domain_grant d
                ON d.client_id = g.client_id AND d.tenant_id = g.tenant_id
             WHERE t.tenant_id = ? AND m.uid = ? AND g.client_id = ? AND d.data_domain_id = ?
               AND m.status = 'ACTIVE'
               AND g.status = 'ACTIVE' AND d.status = 'ACTIVE'
            """;

    private final JdbcOperations jdbc;

    public JdbcTenantAuthorizationRepository(JdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean isCurrent(TenantAuthorizationAttributes selected, long userId, String registeredClientId) {
        Objects.requireNonNull(selected, "selected");
        // Closing new authorization does not revoke existing token reads.
        // Transactional admission still checks each business operation.
        return findSelection(selected.tenantId(), selected.dataDomainId(), userId, registeredClientId, false)
                .filter(selected::equals).isPresent();
    }

    /** Only new code issuance and refresh may use this stricter state policy. */
    public boolean isEligibleForIssuance(TenantAuthorizationAttributes selected,
            long userId, String registeredClientId) {
        Objects.requireNonNull(selected, "selected");
        return findEligibleSelection(selected.tenantId(), selected.dataDomainId(), userId, registeredClientId)
                .filter(selected::equals).isPresent();
    }

    /** Enumerate only areas eligible for this authenticated user, client and fixed product domain. */
    public List<TenantAuthorizationAttributes> listEligibleSelections(
            DataDomainId dataDomainId, long userId, String registeredClientId) {
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (userId <= 0 || registeredClientId == null || registeredClientId.isBlank()) {
            throw new IllegalArgumentException("A valid authenticated user and registered client are required");
        }
        List<String> candidateIds = jdbc.queryForList("""
                SELECT t.tenant_id FROM core_tenant t
                JOIN core_tenant_control c ON c.tenant_id = t.tenant_id
                JOIN core_tenant_membership m ON m.tenant_id = t.tenant_id
                JOIN oauth_client_tenant_grant g ON g.tenant_id = t.tenant_id
                JOIN oauth_client_tenant_domain_grant d
                  ON d.tenant_id = g.tenant_id AND d.client_id = g.client_id
                WHERE m.uid = ? AND g.client_id = ? AND d.data_domain_id = ?
                  AND c.state = 'ENABLED' AND m.status = 'ACTIVE'
                  AND g.status = 'ACTIVE' AND d.status = 'ACTIVE'
                """, String.class, userId, registeredClientId, dataDomainId.value());
        // Do not trust case-insensitive joins, SQL DISTINCT or result ordering as authority.
        // findEligibleSelection checks every joined key byte-for-byte and rejects ambiguity.
        return candidateIds.stream().distinct().map(candidate -> {
            try {
                return findEligibleSelection(TenantId.of(candidate), dataDomainId, userId, registeredClientId);
            } catch (IllegalArgumentException invalidId) {
                return Optional.<TenantAuthorizationAttributes>empty();
            }
        }).flatMap(Optional::stream).sorted((a, b) -> a.tenantId().value()
                .compareTo(b.tenantId().value())).toList();
    }

    public Optional<TenantAuthorizationAttributes> findEligibleSelection(
            TenantId tenantId, DataDomainId dataDomainId, long userId, String registeredClientId) {
        return findSelection(tenantId, dataDomainId, userId, registeredClientId, true);
    }

    private Optional<TenantAuthorizationAttributes> findSelection(
            TenantId tenantId, DataDomainId dataDomainId, long userId, String registeredClientId,
            boolean newAuthorization) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (userId <= 0 || registeredClientId == null || registeredClientId.isBlank()) {
            throw new IllegalArgumentException("A valid authenticated user and registered client are required");
        }
        // Existing oauth_client.client_id has no proven unique index. MySQL collations can
        // fold case/trailing spaces; refuse ambiguous or non-byte-exact registrations.
        List<String> registered = jdbc.queryForList(
                "SELECT client_id FROM oauth_client WHERE client_id = ?", String.class, registeredClientId);
        if (registered.size() != 1 || !registeredClientId.equals(registered.get(0))) {
            return Optional.empty();
        }

        List<TenantAuthorizationAttributes> candidates = jdbc.query(SELECT_ELIGIBLE,
                (rs, row) -> eligibleCandidate(rs, tenantId, dataDomainId, userId, registeredClientId,
                        newAuthorization),
                tenantId.value(), userId, registeredClientId, dataDomainId.value());
        // Missing rows, revoked grants and ambiguous joins never become permissions.
        return candidates.size() == 1 ? Optional.ofNullable(candidates.get(0)) : Optional.empty();
    }

    private static TenantAuthorizationAttributes eligibleCandidate(ResultSet rs, TenantId tenantId,
            DataDomainId dataDomainId, long userId, String clientId, boolean newAuthorization) throws SQLException {
        // Do not trust collations on existing tables to compare authority keys exactly.
        if (!tenantId.value().equals(rs.getString("tenant_id"))
                || !tenantId.value().equals(rs.getString("control_tenant"))
                || !tenantId.value().equals(rs.getString("member_tenant"))
                || !tenantId.value().equals(rs.getString("grant_tenant"))
                || !tenantId.value().equals(rs.getString("domain_tenant"))
                || !clientId.equals(rs.getString("client_id"))
                || !clientId.equals(rs.getString("domain_client"))
                || !dataDomainId.value().equals(rs.getString("data_domain_id"))
                || !("ENABLED".equals(rs.getString("control_state"))
                    || !newAuthorization && ("AUTH_CLOSED".equals(rs.getString("control_state"))
                    || "WRITE_CLOSED_CLAIM_CLOSED".equals(rs.getString("control_state"))))
                || !"ACTIVE".equals(rs.getString("member_status"))
                || !"ACTIVE".equals(rs.getString("grant_status"))
                || !"ACTIVE".equals(rs.getString("domain_status"))
                || rs.getLong("uid") != userId || rs.wasNull()) {
            return null;
        }
        return new TenantAuthorizationAttributes(
                tenantId, dataDomainId, rs.getString("tenant_name"),
                TenantAuthorizationAttributes.TenantKind.valueOf(rs.getString("tenant_kind")),
                revision(rs.getObject("tenant_session_epoch", Long.class)),
                revision(rs.getObject("membership_revision", Long.class)),
                revision(rs.getObject("grant_revision", Long.class)));
    }

    private static String revision(Long revision) {
        if (revision == null || revision < 0) {
            throw new IllegalStateException("Missing or invalid authorization revision");
        }
        return Long.toString(revision);
    }
}
