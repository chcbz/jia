package cn.jia.chat.archive.maintenance.config;

import cn.jia.chat.archive.maintenance.model.ArchiveManagerGrantRecord;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ArchiveMaintenanceSchemaInitializer {
    private final JdbcTemplate jdbc;
    private final ArchiveMaintenanceStore store;
    private static final Set<String> MANAGER_PERMISSIONS = Set.of("appoint", "source.prepare",
            "job.create", "job.manage", "draft.write", "validate", "publish", "edition.withdraw");
    private final ArchiveMaintenanceProperties properties;
    private final ArchiveTransactions transactions;

    @Autowired
    public ArchiveMaintenanceSchemaInitializer(JdbcTemplate jdbc, ArchiveMaintenanceStore store,
                                               ArchiveMaintenanceProperties properties,
                                               ArchiveTransactions transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Direct seam for schema-only tests; production always selects the annotated transactional constructor. */
    public ArchiveMaintenanceSchemaInitializer(JdbcTemplate jdbc, ArchiveMaintenanceStore store,
                                               ArchiveMaintenanceProperties properties) {
        this(jdbc, store, properties, new ArchiveTransactions() {
            @Override public <T> T required(java.util.function.Supplier<T> action) { return action.get(); }
        });
    }

    public void initialize() {
        ArchiveMaintenanceSchemaCatalog.Definition expected = ArchiveMaintenanceSchemaCatalog.expected();
        Set<String> existing = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN (%s)
                """.formatted(placeholders(expected.tables().size())), String.class, expected.tables().keySet().toArray()));
        // Exact additive chain: 18d66419 is the complete eighteen-table predecessor
        // without archive_business_outbox. Older accepted predecessors remove readback, exact-admin,
        // and withdrawal in that order; arbitrary partial sets remain fail-closed.
        Set<String> currentTables = expected.tables().keySet();
        // RECOVERY-13 predecessor is byte-bound in tests to the exact 659b66c Git blob.
        // All older supported shapes branch from that nineteen-table schema; accepting a
        // shape that already contains only part of RECOVERY-13 would hide interrupted DDL.
        Set<String> recovery13PredecessorTables = new LinkedHashSet<>(currentTables);
        recovery13PredecessorTables.remove("archive_execution_failure");
        Set<String> businessOutboxPredecessorTables = new LinkedHashSet<>(recovery13PredecessorTables);
        businessOutboxPredecessorTables.remove("archive_business_outbox");
        Set<String> predecessorTables = new LinkedHashSet<>(businessOutboxPredecessorTables);
        predecessorTables.remove("archive_publication_readback");
        Set<String> exactAdminPredecessorTables = new LinkedHashSet<>(predecessorTables);
        exactAdminPredecessorTables.remove("archive_admin_operation_receipt");
        Set<String> legacyPredecessorTables = new LinkedHashSet<>(exactAdminPredecessorTables);
        legacyPredecessorTables.remove("archive_edition_withdrawal");
        if (!existing.isEmpty() && !existing.equals(currentTables)
                && !existing.equals(recovery13PredecessorTables)
                && !existing.equals(businessOutboxPredecessorTables)
                && !existing.equals(predecessorTables)
                && !existing.equals(exactAdminPredecessorTables)
                && !existing.equals(legacyPredecessorTables)) {
            throw new IllegalStateException("Archive maintenance schema is partial or not an exact supported predecessor");
        }
        boolean needsBusinessOutboxUpgrade = !existing.isEmpty()
                && !existing.contains("archive_business_outbox");
        if (!existing.isEmpty()) {
            if (needsBusinessOutboxUpgrade) {
                validateAndUpgradeBusinessOutboxPredecessor(existing, expected);
            } else {
                WaitingShape waiting = validateWaitingShape(existing, expected, Map.of());
                upgradeWaitingShape(waiting);
            }
            validate(existing, expected);
        }
        if (!existing.equals(currentTables)) {
            DataSource ds = Objects.requireNonNull(jdbc.getDataSource(), "archive dataSource");
            new ResourceDatabasePopulator(new ClassPathResource("db/archive-maintenance-schema.sql")).execute(ds);
        }
        validate(expected.tables().keySet(), expected);
        backfillPublicationReadbacks();
        backfillBusinessOutbox();
        reconcileConfiguredManagers();
    }

    private void validateAndUpgradeBusinessOutboxPredecessor(Set<String> existing,
            ArchiveMaintenanceSchemaCatalog.Definition expected) {
        List<OutboxPredecessorShape> shapes = new ArrayList<>();
        Map<String, ArchiveMaintenanceSchemaCatalog.Table> allOld = new LinkedHashMap<>();
        allOld.put("archive_event",
                ArchiveMaintenanceSchemaCatalog.predecessorOutboxSourceTable(expected, "archive_event"));
        if (existing.contains("archive_edition_withdrawal")) {
            allOld.put("archive_edition_withdrawal",
                    ArchiveMaintenanceSchemaCatalog.predecessorOutboxSourceTable(
                            expected, "archive_edition_withdrawal"));
        }
        shapes.add(new OutboxPredecessorShape(Map.copyOf(allOld), true,
                existing.contains("archive_edition_withdrawal")));
        if (existing.contains("archive_edition_withdrawal")) {
            shapes.add(new OutboxPredecessorShape(Map.of("archive_edition_withdrawal",
                    ArchiveMaintenanceSchemaCatalog.predecessorOutboxSourceTable(
                            expected, "archive_edition_withdrawal")), false, true));
        }
        shapes.add(new OutboxPredecessorShape(Map.of(), false, false));

        IllegalStateException rejected = null;
        for (OutboxPredecessorShape shape : shapes) {
            try {
                WaitingShape waiting = validateWaitingShape(existing, expected, shape.overrides());
                upgradeWaitingShape(waiting);
                upgradeBusinessOutboxSources(existing, shape.upgradeEvent(),
                        shape.upgradeWithdrawal());
                return;
            } catch (IllegalStateException drift) {
                rejected = drift;
            }
        }
        throw Objects.requireNonNull(rejected,
                "archive business outbox predecessor validation failure");
    }

    private WaitingShape validateWaitingShape(Set<String> existing,
            ArchiveMaintenanceSchemaCatalog.Definition expected,
            Map<String, ArchiveMaintenanceSchemaCatalog.Table> baseOverrides) {
        try {
            validate(existing, expected, baseOverrides);
            return WaitingShape.CURRENT;
        } catch (IllegalStateException currentDrift) {
            if (!existing.contains("archive_maintenance_job")) throw currentDrift;
            try {
                Map<String, ArchiveMaintenanceSchemaCatalog.Table> previous =
                        new LinkedHashMap<>(baseOverrides);
                previous.put("archive_maintenance_job",
                        ArchiveMaintenanceSchemaCatalog.previousWaitingShapeJobTable(expected));
                validate(existing, expected, previous);
                return WaitingShape.PREVIOUS;
            } catch (IllegalStateException previousWaitingDrift) {
                Map<String, ArchiveMaintenanceSchemaCatalog.Table> legacy =
                        new LinkedHashMap<>(baseOverrides);
                legacy.put("archive_maintenance_job",
                        ArchiveMaintenanceSchemaCatalog.legacyWaitingJobTable(expected));
                validate(existing, expected, legacy);
                return WaitingShape.LEGACY;
            }
        }
    }

    private void upgradeWaitingShape(WaitingShape waiting) {
        if (waiting == WaitingShape.PREVIOUS) upgradePreviousWaitingShape();
        if (waiting == WaitingShape.LEGACY) upgradeLegacyWaitingJob();
    }

    private void upgradeBusinessOutboxSources(Set<String> existing, boolean upgradeEvent,
            boolean upgradeWithdrawal) {
        if (upgradeEvent && existing.contains("archive_event")) {
            jdbc.execute("""
                    ALTER TABLE archive_event
                      DROP CHECK chk_archive_event_outbox,
                      ADD CONSTRAINT chk_archive_event_outbox CHECK (outbox_state IN ('PENDING','DELIVERED','NO_TARGET'))
                    """);
        }
        if (upgradeWithdrawal && existing.contains("archive_edition_withdrawal")) {
            jdbc.execute("""
                    ALTER TABLE archive_edition_withdrawal
                      DROP CHECK chk_archive_withdrawal_outbox,
                      ADD CONSTRAINT chk_archive_withdrawal_outbox CHECK (outbox_state IN ('PENDING','DELIVERED','NO_TARGET'))
                    """);
        }
    }

    private enum WaitingShape { CURRENT, PREVIOUS, LEGACY }

    private record OutboxPredecessorShape(
            Map<String, ArchiveMaintenanceSchemaCatalog.Table> overrides,
            boolean upgradeEvent, boolean upgradeWithdrawal) { }

    private void backfillBusinessOutbox() {
        transactions.required(() -> {
            jdbc.update("""
                    UPDATE archive_edition_withdrawal w
                    JOIN archive_publication p ON p.publication_id=w.publication_id
                    SET w.outbox_state='NO_TARGET'
                    WHERE w.outbox_state='PENDING' AND p.job_id IS NULL
                    """);
            Integer unsupported = jdbc.queryForObject("""
                    SELECT (SELECT COUNT(*) FROM archive_event WHERE outbox_state<>'PENDING')
                         + (SELECT COUNT(*) FROM archive_edition_withdrawal w
                            JOIN archive_publication p ON p.publication_id=w.publication_id
                            WHERE p.job_id IS NOT NULL AND w.outbox_state<>'PENDING')
                         + (SELECT COUNT(*) FROM archive_edition_withdrawal w
                            JOIN archive_publication p ON p.publication_id=w.publication_id
                            WHERE p.job_id IS NULL AND w.outbox_state<>'NO_TARGET')
                    """, Integer.class);
            Integer outboxRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM archive_business_outbox", Integer.class);
            if (unsupported == null || outboxRows == null) {
                throw new IllegalStateException("Archive business outbox backfill state is unavailable");
            }
            if (outboxRows == 0 && unsupported != 0) {
                throw new IllegalStateException("Archive business outbox predecessor contains unsupported delivery state");
            }
            jdbc.update("""
                    INSERT IGNORE INTO archive_business_outbox(
                        projection_key,source_type,job_id,event_sequence,withdrawal_id,state,
                        attempt_count,fencing_token,available_at,lease_until,last_error_code,projected_message_id)
                    SELECT CONCAT('EVENT:',e.job_id,':',e.sequence),'JOB_EVENT',e.job_id,e.sequence,NULL,
                           'READY',0,0,e.occurred_at,NULL,NULL,NULL
                    FROM archive_event e
                    LEFT JOIN archive_business_outbox o
                      ON o.projection_key=CONCAT('EVENT:',e.job_id,':',e.sequence)
                     AND o.job_id=e.job_id AND o.event_sequence=e.sequence
                    WHERE e.outbox_state='PENDING' AND o.projection_key IS NULL
                    """);
            jdbc.update("""
                    INSERT IGNORE INTO archive_business_outbox(
                        projection_key,source_type,job_id,event_sequence,withdrawal_id,state,
                        attempt_count,fencing_token,available_at,lease_until,last_error_code,projected_message_id)
                    SELECT CONCAT('WITHDRAWAL:',w.withdrawal_id),'WITHDRAWAL',p.job_id,NULL,w.withdrawal_id,
                           'READY',0,0,w.withdrawn_at,NULL,NULL,NULL
                    FROM archive_edition_withdrawal w
                    JOIN archive_publication p ON p.publication_id=w.publication_id
                    LEFT JOIN archive_business_outbox o
                      ON o.projection_key=CONCAT('WITHDRAWAL:',w.withdrawal_id)
                     AND o.withdrawal_id=w.withdrawal_id
                    WHERE w.outbox_state='PENDING' AND p.job_id IS NOT NULL
                      AND o.projection_key IS NULL
                    """);
            Integer eventMissing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM archive_event e
                    LEFT JOIN archive_business_outbox o
                      ON o.projection_key=CONCAT('EVENT:',e.job_id,':',e.sequence)
                     AND o.job_id=e.job_id AND o.event_sequence=e.sequence
                    WHERE o.projection_key IS NULL
                    """, Integer.class);
            Integer withdrawalMissing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM archive_edition_withdrawal w
                    JOIN archive_publication p ON p.publication_id=w.publication_id
                    LEFT JOIN archive_business_outbox o
                      ON o.projection_key=CONCAT('WITHDRAWAL:',w.withdrawal_id)
                     AND o.withdrawal_id=w.withdrawal_id
                    WHERE (p.job_id IS NOT NULL AND o.projection_key IS NULL)
                       OR (p.job_id IS NULL
                           AND (w.outbox_state<>'NO_TARGET' OR o.projection_key IS NOT NULL))
                    """, Integer.class);
            if (eventMissing == null || eventMissing != 0
                    || withdrawalMissing == null || withdrawalMissing != 0) {
                throw new IllegalStateException("Archive business outbox backfill is incomplete");
            }
            Integer inconsistent = jdbc.queryForObject("""
                    SELECT
                      (SELECT COUNT(*) FROM archive_event e
                       JOIN archive_business_outbox o
                         ON o.projection_key=CONCAT('EVENT:',e.job_id,':',e.sequence)
                        AND o.job_id=e.job_id AND o.event_sequence=e.sequence
                       WHERE NOT ((e.outbox_state='PENDING'
                                   AND o.state IN ('READY','LEASED','WAITING_RETRY'))
                              OR (e.outbox_state='DELIVERED' AND o.state='DELIVERED')
                              OR (e.outbox_state='NO_TARGET' AND o.state='NO_TARGET')))
                    + (SELECT COUNT(*) FROM archive_edition_withdrawal w
                       JOIN archive_business_outbox o
                         ON o.projection_key=CONCAT('WITHDRAWAL:',w.withdrawal_id)
                        AND o.withdrawal_id=w.withdrawal_id
                       WHERE NOT ((w.outbox_state='PENDING'
                                   AND o.state IN ('READY','LEASED','WAITING_RETRY'))
                              OR (w.outbox_state='DELIVERED' AND o.state='DELIVERED')
                              OR (w.outbox_state='NO_TARGET' AND o.state='NO_TARGET')))
                    """, Integer.class);
            if (inconsistent == null || inconsistent != 0) {
                throw new IllegalStateException("Archive business outbox source acknowledgement is inconsistent");
            }
            return null;
        });
    }

    private void backfillPublicationReadbacks() {
        transactions.required(() -> {
            jdbc.update("""
                    INSERT IGNORE INTO archive_publication_readback(
                        publication_id,state,revision,verification_digest,findings_json,checked_at)
                    SELECT p.publication_id,'PENDING',1,NULL,'[]',NULL
                    FROM archive_publication p
                    LEFT JOIN archive_publication_readback r ON r.publication_id=p.publication_id
                    WHERE r.publication_id IS NULL
                    """);
            Integer missing = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM archive_publication p
                    LEFT JOIN archive_publication_readback r ON r.publication_id=p.publication_id
                    WHERE r.publication_id IS NULL
                    """, Integer.class);
            if (missing == null || missing != 0) {
                throw new IllegalStateException("Archive publication readback backfill is incomplete");
            }
            return null;
        });
    }

    void validate(Set<String> tables, ArchiveMaintenanceSchemaCatalog.Definition expected) {
        validate(tables, expected, Map.of());
    }

    private void validate(Set<String> tables, ArchiveMaintenanceSchemaCatalog.Definition expected,
                          Map<String, ArchiveMaintenanceSchemaCatalog.Table> overrides) {
        if (tables.isEmpty()) return;
        Object[] args = tables.toArray();
        String in = placeholders(args.length);
        Map<String, Map<String, ArchiveMaintenanceSchemaCatalog.Column>> columns = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT table_name,column_name,column_type,is_nullable,collation_name
                FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN (%s)
                ORDER BY table_name,ordinal_position
                """.formatted(in), args)) {
            columns.computeIfAbsent(text(row, "table_name"), ignored -> new LinkedHashMap<>())
                    .put(text(row, "column_name"), new ArchiveMaintenanceSchemaCatalog.Column(
                            text(row, "column_type").toLowerCase(Locale.ROOT),
                            "YES".equalsIgnoreCase(text(row, "is_nullable")),
                            nullable(row, "collation_name")));
        }
        Map<String, String> engines = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT table_name,engine,table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN (%s)
                """.formatted(in), args)) {
            String name = text(row, "table_name");
            engines.put(name, text(row, "engine") + ":" + text(row, "table_collation"));
        }
        Map<String, Map<String, String>> indexes = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT table_name,index_name,non_unique,seq_in_index,column_name
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN (%s)
                ORDER BY table_name,index_name,seq_in_index
                """.formatted(in), args)) {
            String table = text(row, "table_name");
            String name = text(row, "index_name");
            Map<String, String> found = indexes.computeIfAbsent(table, ignored -> new LinkedHashMap<>());
            String value = found.get(name);
            String marker = String.valueOf(row.get("non_unique"));
            found.put(name, value == null ? marker + ":" + text(row, "column_name")
                    : value + "," + text(row, "column_name"));
        }
        Map<String, Map<String, String>> foreignKeys = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT table_name,constraint_name,column_name,referenced_table_name,referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema=DATABASE() AND table_name IN (%s) AND referenced_table_name IS NOT NULL
                ORDER BY table_name,constraint_name,ordinal_position
                """.formatted(in), args)) {
            String table = text(row, "table_name");
            String name = text(row, "constraint_name");
            Map<String, String> found = foreignKeys.computeIfAbsent(table, ignored -> new LinkedHashMap<>());
            String part = text(row, "column_name") + ">" + text(row, "referenced_table_name")
                    + "." + text(row, "referenced_column_name");
            found.merge(name, part, (left, right) -> left + "," + right);
        }
        Map<String, Map<String, String>> checks = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT t.table_name,t.constraint_name,t.enforced,c.check_clause
                FROM information_schema.table_constraints t
                JOIN information_schema.check_constraints c
                  ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.table_schema=DATABASE() AND t.table_name IN (%s) AND t.constraint_type='CHECK'
                """.formatted(in), args)) {
            checks.computeIfAbsent(text(row, "table_name"), ignored -> new LinkedHashMap<>())
                    .put(text(row, "constraint_name"), text(row, "enforced") + ":"
                            + ArchiveMaintenanceSchemaCatalog.normalizeCheck(text(row, "check_clause")));
        }
        for (String table : tables) {
            ArchiveMaintenanceSchemaCatalog.verify(table,
                    overrides.getOrDefault(table, expected.tables().get(table)),
                    new ArchiveMaintenanceSchemaCatalog.Table(columns.getOrDefault(table, Map.of()),
                            indexes.getOrDefault(table, Map.of()), foreignKeys.getOrDefault(table, Map.of()),
                            checks.getOrDefault(table, Map.of())), engines.get(table));
        }
    }

    private void upgradePreviousWaitingShape() {
        jdbc.execute("""
                ALTER TABLE archive_maintenance_job
                  DROP CHECK chk_archive_job_waiting_shape,
                  ADD CONSTRAINT chk_archive_job_waiting_shape CHECK (((state='WAITING_INPUT') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (publication_id IS NULL) AND ((source_id IS NULL) OR (work_id IS NULL))) OR ((state='WAITING_ASSIGNEE') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL) AND (publication_id IS NULL)) OR ((state='CANCELLED') AND (publication_id IS NULL) AND (((run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL)) OR ((run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))) OR ((state NOT IN ('WAITING_INPUT','WAITING_ASSIGNEE','CANCELLED')) AND (run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))
                """);
    }
    private void upgradeLegacyWaitingJob() {
        jdbc.execute("""
                ALTER TABLE archive_maintenance_job
                  DROP CHECK chk_archive_job_revision,
                  ADD COLUMN target_agent_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER owner_jiacn,
                  MODIFY COLUMN run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN appointment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN appointment_revision BIGINT NULL,
                  MODIFY COLUMN agent_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN binding_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN permission_profile VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN work_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN canonical_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN title VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN source_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  MODIFY COLUMN source_summary VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN rights_basis VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
                  MODIFY COLUMN draft_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                  ADD CONSTRAINT chk_archive_job_state CHECK (state IN ('WAITING_INPUT','WAITING_ASSIGNEE','WAITING_SKILL','EXECUTION_REQUESTED','RUNNING','NEEDS_CHANGES','AWAITING_PUBLISH','PUBLISHING','PUBLISHED','SUSPENDED_AUTH','FAILED','CANCELLED')),
                  ADD CONSTRAINT chk_archive_job_revision CHECK ((revision >= 1) AND (manager_authorization_revision >= 1)),
                  ADD CONSTRAINT chk_archive_job_assignment CHECK (((appointment_id IS NULL) AND (appointment_revision IS NULL) AND (agent_id IS NULL) AND (binding_version IS NULL) AND (permission_profile IS NULL)) OR ((appointment_id IS NOT NULL) AND (appointment_revision >= 1) AND (agent_id IS NOT NULL) AND (binding_version IS NOT NULL) AND (permission_profile IN ('DRAFT_ONLY','PUBLISH_VALIDATED')))),
                  ADD CONSTRAINT chk_archive_job_source_shape CHECK (((source_id IS NULL) AND (source_sha256 IS NULL) AND (source_summary IS NULL) AND (rights_basis IS NULL)) OR ((source_id IS NOT NULL) AND (source_sha256 IS NOT NULL) AND (source_summary IS NOT NULL) AND (rights_basis IS NOT NULL))),
                  ADD CONSTRAINT chk_archive_job_work_shape CHECK (((work_id IS NULL) AND (canonical_key IS NULL) AND (title IS NULL)) OR ((work_id IS NOT NULL) AND (canonical_key IS NOT NULL) AND (title IS NOT NULL))),
                  ADD CONSTRAINT chk_archive_job_candidate_shape CHECK (((run_id IS NULL) AND (draft_id IS NULL)) OR ((run_id IS NOT NULL) AND (draft_id IS NOT NULL))),
                  ADD CONSTRAINT chk_archive_job_waiting_shape CHECK (((state='WAITING_INPUT') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (publication_id IS NULL) AND ((source_id IS NULL) OR (work_id IS NULL))) OR ((state='WAITING_ASSIGNEE') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL) AND (publication_id IS NULL)) OR ((state='CANCELLED') AND (publication_id IS NULL) AND (((run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL)) OR ((run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))) OR ((state NOT IN ('WAITING_INPUT','WAITING_ASSIGNEE','CANCELLED')) AND (run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))
                """);
    }

    private static String placeholders(int count) { return String.join(",", java.util.Collections.nCopies(count, "?")); }
    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase(Locale.ROOT));
        return value == null ? null : value.toString();
    }
    private static String nullable(Map<String, Object> row, String key) { return text(row, key); }

    /**
     * V1 contract: every archive_collection_manager row is configuration-owned. Reconciliation
     * deliberately does not call the Agent port: manager -> run -> execution-grant remains the
     * complete lock order for this startup path, so it can never invert an Agent-root transaction.
     */
    void reconcileConfiguredManagers() {
        TreeMap<ManagerKey, ArchiveManagerGrantRecord> desired = configuredManagers();
        transactions.required(() -> {
            TreeMap<ManagerKey, ArchiveManagerGrantRecord> current = new TreeMap<>();
            for (ArchiveManagerGrantRecord grant : store.lockManagerGrants()) {
                ManagerKey key = ManagerKey.of(grant);
                if (current.putIfAbsent(key, grant) != null) {
                    throw new IllegalStateException("Duplicate persisted archive manager authorization");
                }
                normalizedPermissions(grant.permissions());
                if (grant.revision() < 1 || !("ACTIVE".equals(grant.state()) || "REVOKED".equals(grant.state()))) {
                    throw new IllegalStateException("Invalid persisted archive manager authorization");
                }
            }
            TreeMap<ManagerKey, Boolean> keys = new TreeMap<>();
            current.keySet().forEach(key -> keys.put(key, Boolean.TRUE));
            desired.keySet().forEach(key -> keys.put(key, Boolean.TRUE));
            for (ManagerKey key : keys.keySet()) {
                reconcileManager(key, current.get(key), desired.get(key));
            }
            return null;
        });
    }

    private void reconcileManager(ManagerKey key, ArchiveManagerGrantRecord current,
                                  ArchiveManagerGrantRecord desired) {
        ArchiveActorScope actor = key.actor();
        if (current == null) {
            auditConfigurationChange(actor, key.collectionId(), "PUT", desired.revision(),
                    desired.permissions(), "ACTIVE", () -> store.insertManagerGrant(desired));
            return;
        }
        if (desired == null) {
            if (!"ACTIVE".equals(current.state())) return;
            long revokedRevision = Math.addExact(current.revision(), 1);
            auditConfigurationChange(actor, key.collectionId(), "DELETE", revokedRevision,
                    current.permissions(), "REVOKED", () -> {
                        fenceManagerRuns(actor, key.collectionId());
                        if (store.revokeManagerGrant(actor, key.collectionId(), current.revision()) != 1) {
                            throw new IllegalStateException("Archive manager configuration removal raced");
                        }
                    });
            return;
        }
        String currentPermissions = normalizedPermissions(current.permissions());
        String desiredPermissions = normalizedPermissions(desired.permissions());
        if (desired.revision() < current.revision()) return;
        if (desired.revision() == current.revision()) {
            if (!currentPermissions.equals(desiredPermissions)) {
                throw new IllegalStateException("Archive manager permission change requires a higher authorizationRevision");
            }
            // A stale/same revision can observe a prior API/config revocation but can never revive it.
            return;
        }
        ArchiveManagerGrantRecord activated = new ArchiveManagerGrantRecord(key.collectionId(),
                key.tenantId(), key.clientId(), key.ownerJiacn(), desiredPermissions,
                desired.revision(), "ACTIVE");
        auditConfigurationChange(actor, key.collectionId(), "PUT", desired.revision(),
                desiredPermissions, "ACTIVE", () -> {
                    fenceManagerRuns(actor, key.collectionId());
                    if (store.activateConfiguredManagerGrant(activated, current.revision()) != 1) {
                        throw new IllegalStateException("Archive manager configuration revision raced");
                    }
                });
    }

    private void fenceManagerRuns(ArchiveActorScope actor, String collectionId) {
        for (String runId : store.lockRunIdsForManager(actor, collectionId)) {
            if (store.fenceRun(runId) != 1) {
                throw new IllegalStateException("Archive manager run changed during configuration reconciliation");
            }
        }
    }

    private void auditConfigurationChange(ArchiveActorScope actor, String collectionId,
                                          String method, long revision, String permissions,
                                          String state, Runnable mutation) {
        String material = collectionId + "\0" + actor.tenantId() + "\0" + actor.clientId()
                + "\0" + actor.ownerJiacn() + "\0" + revision + "\0" + state + "\0"
                + normalizedPermissions(permissions);
        String requestSha = sha256(material);
        String key = "cfg-manager-" + requestSha;
        String path = "/internal/archive/config/v1/manager-grants/" + requestSha.substring(0, 32);
        ArchiveMaintenanceStore.Operation operation = store.beginOperation(actor, key, method, path,
                requestSha, "MANAGER_CONFIG", collectionId);
        if (!operation.httpMethod().equals(method) || !operation.canonicalPath().equals(path)
                || !operation.requestSha256().equals(requestSha)
                || !operation.targetType().equals("MANAGER_CONFIG")
                || !operation.targetId().equals(collectionId)) {
            throw new IllegalStateException("Archive manager configuration audit conflict");
        }
        if (!operation.created()) {
            throw new IllegalStateException("Archive manager configuration audit already committed without matching state");
        }
        mutation.run();
        store.commitOperation(actor, key, collectionId);
    }

    private TreeMap<ManagerKey, ArchiveManagerGrantRecord> configuredManagers() {
        TreeMap<ManagerKey, ArchiveManagerGrantRecord> result = new TreeMap<>();
        for (var grant : properties.getManagerGrants()) {
            if (grant == null || !exact(grant.getCollectionId(), 64) || !"0".equals(grant.getTenantId())
                    || !exact(grant.getClientId(), 50) || !exact(grant.getOwnerJiacn(), 50)
                    || "0".equals(grant.getClientId()) || "0".equals(grant.getOwnerJiacn())
                    || grant.getAuthorizationRevision() < 1) {
                throw new IllegalStateException("Invalid configured archive manager grant");
            }
            String permissions = normalizedPermissions(grant.getPermissions());
            ManagerKey key = new ManagerKey(grant.getCollectionId(), grant.getTenantId(),
                    grant.getClientId(), grant.getOwnerJiacn());
            ArchiveManagerGrantRecord value = new ArchiveManagerGrantRecord(key.collectionId(),
                    key.tenantId(), key.clientId(), key.ownerJiacn(), permissions,
                    grant.getAuthorizationRevision(), "ACTIVE");
            if (result.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("Duplicate configured archive manager grant");
            }
        }
        return result;
    }

    private static String normalizedPermissions(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Invalid archive manager permissions");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            String permission = token.strip();
            if (!MANAGER_PERMISSIONS.contains(permission) || !unique.add(permission)) {
                throw new IllegalStateException("Invalid archive manager permissions");
            }
        }
        List<String> ordered = new ArrayList<>(unique);
        ordered.sort(Comparator.naturalOrder());
        return String.join(",", ordered);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ManagerKey(String collectionId, String tenantId, String clientId,
                              String ownerJiacn) implements Comparable<ManagerKey> {
        static ManagerKey of(ArchiveManagerGrantRecord value) {
            return new ManagerKey(value.collectionId(), value.tenantId(), value.clientId(), value.ownerJiacn());
        }
        ArchiveActorScope actor() { return new ArchiveActorScope(tenantId, clientId, ownerJiacn); }
        @Override public int compareTo(ManagerKey other) {
            int result = collectionId.compareTo(other.collectionId);
            if (result == 0) result = tenantId.compareTo(other.tenantId);
            if (result == 0) result = clientId.compareTo(other.clientId);
            if (result == 0) result = ownerJiacn.compareTo(other.ownerJiacn);
            return result;
        }
    }

    private boolean exact(String v, int max) {
        return v != null && !v.isBlank() && v.equals(v.strip()) && v.length() <= max
                && v.codePoints().noneMatch(Character::isISOControl);
    }
}
