package cn.jia.agent.skill;

import org.h2.api.Trigger;

import java.sql.Connection;
import java.sql.SQLException;

/** H2 equivalent of the two production D09 append-only audit mutation fences. */
public final class SkillAuditMutationRejectTrigger implements Trigger {
    private String rejectionMessage;

    @Override
    public void init(Connection connection, String schemaName, String triggerName, String tableName,
            boolean before, int type) throws SQLException {
        if (!before || !"agent_command_operation_audit".equalsIgnoreCase(tableName)) {
            throw drift(triggerName);
        }
        if ("trg_command_operation_audit_no_update".equalsIgnoreCase(triggerName)
                && type == Trigger.UPDATE) {
            rejectionMessage = "D09: operation audit rows are immutable after insert";
        } else if ("trg_command_operation_audit_no_delete".equalsIgnoreCase(triggerName)
                && type == Trigger.DELETE) {
            rejectionMessage = "D09: physical delete of operation audit rows is forbidden";
        } else {
            throw drift(triggerName);
        }
    }

    @Override
    public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
        if (rejectionMessage == null) throw drift("uninitialized");
        throw new SQLException(rejectionMessage, "45000");
    }

    @Override
    public void close() {
    }

    @Override
    public void remove() {
    }

    private static SQLException drift(String triggerName) {
        return new SQLException("D09 fixture audit trigger definition drift: " + triggerName, "45000");
    }
}
