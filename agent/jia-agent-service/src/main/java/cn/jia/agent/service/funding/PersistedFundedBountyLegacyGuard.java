package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.Objects;

/** Allows untouched legacy installations with no W04 table, but never ignores a persisted funded row. */
@Service
public final class PersistedFundedBountyLegacyGuard implements FundedBountyLegacyGuard {
    private static final String TABLE = "agent_task_funding";

    private final AgentTaskFundingMapper mapper;
    private final JdbcTemplate jdbc;

    public PersistedFundedBountyLegacyGuard(AgentTaskFundingMapper mapper, DataSource dataSource) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public void requireAssignmentAllowed(String tenantId, String clientId, String taskId,
            boolean automatic, int targetCount, boolean taskRootLocked) {
        AgentTaskFundingEntity funding = funding(tenantId, clientId, taskId, taskRootLocked);
        if (funding == null) return;
        if (automatic || targetCount != 1) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "FUNDED_TEAM_NOT_SUPPORTED",
                    "Funded V0 tasks support only explicit single-Agent quote and claim");
        }
        throw new FundedBountyException(HttpStatus.CONFLICT, "QUOTE_REQUIRED",
                "Funded task assignment requires an accepted quote and explicit claim");
    }

    @Override
    public void requireLifecycleAllowed(String tenantId, String clientId, String taskId,
            boolean taskRootLocked) {
        if (funding(tenantId, clientId, taskId, taskRootLocked) != null) {
            throw new FundedBountyException(HttpStatus.CONFLICT, "QUOTE_REQUIRED",
                    "Funded task lifecycle changes require the funded quote/settlement APIs");
        }
    }

    private AgentTaskFundingEntity funding(String tenantId, String clientId, String taskId,
            boolean taskRootLocked) {
        if (!fundingTableExists()) return null;
        return taskRootLocked
                ? mapper.selectFundingForUpdate(tenantId, clientId, taskId)
                : mapper.selectFunding(tenantId, clientId, taskId);
    }

    private boolean fundingTableExists() {
        try {
            // Reuse the task-root transaction connection, including for metadata. Borrowing a
            // second connection here deadlocks admission when every pool slot holds a root lock.
            return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
                String catalog = connection.getCatalog();
                try (ResultSet tables = connection.getMetaData().getTables(catalog, null, null,
                        new String[]{"TABLE"})) {
                    while (tables.next()) {
                        if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                    }
                    return false;
                }
            }));
        } catch (DataAccessException failure) {
            throw new FundedBountyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "FUNDED_BOUNTY_GUARD_UNAVAILABLE",
                    "Unable to inspect persisted funded-task admission state", true);
        }
    }
}
