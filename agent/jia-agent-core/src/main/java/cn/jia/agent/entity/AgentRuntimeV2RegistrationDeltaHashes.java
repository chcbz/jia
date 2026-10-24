package cn.jia.agent.entity;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed §15 hash input orders, independent of JDBC/JSON map iteration order. */
public final class AgentRuntimeV2RegistrationDeltaHashes {
    private AgentRuntimeV2RegistrationDeltaHashes() {}
    public static final List<String> OPERATION = List.of("drain_operation_id", "operation_scope", "tenant_id",
        "module_id", "operator_id", "audit_reason", "source_tenant_operator_id", "source_tenant_idempotency_key",
        "tenant_state", "tenant_control_revision", "tenant_session_epoch", "tenant_admission_epoch", "tenant_drain_epoch",
        "module_mode_state", "module_mode_revision", "module_migration_epoch", "module_schema_version", "module_mapping_digest",
        "tenant_cutoff_at", "tenant_deadline_at", "module_cutoff_at", "module_deadline_at", "cutoff_at", "deadline_at", "issued_at");
    public static final List<String> COMPLETION = List.of("drain_operation_id", "runtime_binding_id", "tenant_id", "module_id",
        "registration_subject_sha256", "registration_authority_id", "runtime_instance_id", "process_public_key_sha256",
        "claimant_deployment_id", "claimant_worker_generation", "frozen_connected_fact_id", "frozen_connected_fact_sha256",
        "frozen_registration_receipt_id", "frozen_registration_receipt_sha256", "operation_snapshot_sha256", "issued_at");
    public static final List<String> CONTEXT = List.of("registration_subject_sha256", "runtime_binding_id",
        "prior_registration_receipt_id", "prior_registration_receipt_sha256", "runtime_instance_id",
        "provisional_session_fence", "issuer_socket_owner_instance_id", "issuer_socket_owner_epoch");
    public static byte[] operation(Map<String, Object> row) { return hash("CYF-RUNTIME-V2-DRAIN-OPERATION-V1", OPERATION, row); }
    public static byte[] completion(Map<String, Object> row) { return hash("CYF-RUNTIME-V2-DRAIN-COMPLETION-V1", COMPLETION, row); }
    public static byte[] context(Map<String, Object> row) { return hash("CYF-RUNTIME-V2-CHALLENGE-REQUEST-CONTEXT-V1", CONTEXT, row); }
    public static List<String> fields(String domain, List<String> order, Map<String, Object> row) {
        List<String> fields = new ArrayList<>(); fields.add(domain);
        for (String name : order) {
            if (!row.containsKey(name)) throw new IllegalArgumentException("missing hash field: " + name);
            Object value = row.get(name);
            fields.add(value == null ? null : value instanceof byte[] bytes ? AgentRuntimeV2RegistrationFingerprints.hex(bytes)
                : value instanceof Timestamp stamp ? Long.toString(stamp.toInstant().toEpochMilli()) : value.toString());
        }
        return fields;
    }
    private static byte[] hash(String domain, List<String> order, Map<String, Object> row) {
        return AgentRuntimeV2RegistrationFingerprints.sha256(AgentRuntimeV2RegistrationFingerprints.lp(fields(domain, order, row)));
    }
}
