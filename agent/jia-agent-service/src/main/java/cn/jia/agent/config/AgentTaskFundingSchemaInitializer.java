package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Exact, additive W04 catalog initializer. It never rewrites task or economy foundation tables. */
public final class AgentTaskFundingSchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/agent-task-funding-v0.sql";
    static final List<String> TABLES = List.of("agent_task_funding_operation", "agent_task_funding");
    private static final String COLLATION = "utf8mb4_0900_bin";
    private static final String LOCK = "cyf:agent-v0:task-funding-schema";

    static final String LEGACY_FUNDING_STATE = "(funding_status='RESERVING' AND version=0 AND remaining_micro=gross_bounty_amount_micro AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL AND cancel_task_version IS NULL AND refunded_at IS NULL) OR (funding_status='FUNDS_HELD' AND version>=1 AND remaining_micro=gross_bounty_amount_micro AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version>0 AND reserve_transaction_id IS NOT NULL AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL AND cancel_task_version IS NULL AND refunded_at IS NULL) OR (funding_status='REFUNDED' AND version>=2 AND remaining_micro=0 AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version>1 AND reserve_transaction_id IS NOT NULL AND cancel_idempotency_key IS NOT NULL AND octet_length(cancel_idempotency_key)=36 AND cancel_request_hash IS NOT NULL AND octet_length(cancel_request_hash)=32 AND refund_transaction_id IS NOT NULL AND cancel_refunded_micro IS NOT NULL AND cancel_refunded_micro>0 AND cancel_refunded_micro<=gross_bounty_amount_micro AND cancel_task_version IS NOT NULL AND cancel_task_version>0 AND refunded_at IS NOT NULL AND refunded_at>0)";
    static final String SETTLED_FUNDING_STATE = "(funding_status='SETTLED' AND version>=2 AND remaining_micro=0 AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version>1 AND reserve_transaction_id IS NOT NULL AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL AND cancel_task_version IS NULL AND refunded_at IS NULL)";

    private final JdbcTemplate jdbc;

    public AgentTaskFundingSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        withLock(() -> {
            List<String> present = presentTables();
            if (present.isEmpty()) {
                for (String statement : tableDdlStatements()) jdbc.execute(statement);
            } else if (!Set.copyOf(present).equals(Set.copyOf(TABLES))) {
                throw new IllegalStateException("Partial ECO-V0 funded-task schema: " + present);
            }
            // Explicit W04-R3 -> W06 migration, NOT drift repair. Validate the entire exact
            // accepted old catalog before atomically replacing its one state CHECK.
            if (check(LEGACY_FUNDING_STATE).equals(inspectChecks("agent_task_funding").get("chk_agent_task_funding_state"))) {
                validateCatalog(true);
                jdbc.execute("ALTER TABLE agent_task_funding DROP CHECK chk_agent_task_funding_state, "
                        + "ADD CONSTRAINT chk_agent_task_funding_state CHECK ("
                        + LEGACY_FUNDING_STATE + " OR " + SETTLED_FUNDING_STATE + ")");
            }
            validateCatalog();
        });
    }

    void validateCatalog() { validateCatalog(false); }

    private void validateCatalog(boolean legacyW04) {
        if (!Set.copyOf(presentTables()).equals(Set.copyOf(TABLES))) {
            throw new IllegalStateException("ECO-V0 funded-task schema must contain exact 2/2 tables");
        }
        for (TableSpec expected : expectedTables().values()) {
            List<Map<String, Object>> tableRows = jdbc.queryForList("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, expected.name());
            if (tableRows.size() != 1
                    || !"InnoDB".equalsIgnoreCase(text(tableRows.getFirst(), "engine"))
                    || !COLLATION.equalsIgnoreCase(text(tableRows.getFirst(), "table_collation"))) {
                throw new IllegalStateException("Invalid funded-task engine/collation: " + expected.name());
            }

            List<ColumnSpec> columns = jdbc.queryForList("""
                    SELECT column_name,column_type,is_nullable,column_default,collation_name,extra
                    FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                    """, expected.name()).stream().map(AgentTaskFundingSchemaInitializer::column).toList();
            if (!expected.columns().equals(columns)) {
                throw new IllegalStateException("Invalid funded-task columns for " + expected.name());
            }

            Map<String, IndexSpec> indexes = inspectIndexes(expected.name());
            if (!expected.indexes().equals(indexes)) {
                throw new IllegalStateException("Invalid funded-task indexes for " + expected.name()
                        + ": " + indexes);
            }

            Map<String, CheckSpec> checks = inspectChecks(expected.name());
            Map<String, CheckSpec> expectedChecks = new TreeMap<>(expected.checks());
            if (legacyW04 && "agent_task_funding".equals(expected.name())) {
                expectedChecks.put("chk_agent_task_funding_state", check(LEGACY_FUNDING_STATE));
            }
            if (!expectedChecks.equals(checks)) {
                throw new IllegalStateException("Invalid funded-task checks for " + expected.name()
                        + ": " + checks);
            }

            Integer foreignKeys = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                    WHERE (constraint_schema=DATABASE() AND table_name=?)
                       OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                    """, Integer.class, expected.name(), expected.name());
            if (foreignKeys == null || foreignKeys != 0) {
                throw new IllegalStateException("Funded-task tables must not use database foreign keys");
            }
        }
        rejectUnexpectedTriggers();
    }

    private Map<String, IndexSpec> inspectIndexes(String table) {
        Map<String, List<IndexPart>> grouped = new TreeMap<>();
        jdbc.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,
                       index_type,is_visible,collation
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, (RowCallbackHandler) rs -> {
            if (rs.getObject("sub_part") != null
                    || !"BTREE".equalsIgnoreCase(rs.getString("index_type"))
                    || !"YES".equalsIgnoreCase(rs.getString("is_visible"))
                    || !"A".equalsIgnoreCase(rs.getString("collation"))) {
                throw new IllegalStateException("Invalid funded-task index component for " + table);
            }
            grouped.computeIfAbsent(rs.getString("index_name"), ignored -> new ArrayList<>())
                    .add(new IndexPart(rs.getInt("non_unique") == 0,
                            rs.getInt("seq_in_index"), rs.getString("column_name")));
        }, table);
        Map<String, IndexSpec> result = new TreeMap<>();
        grouped.forEach((name, parts) -> {
            boolean unique = parts.getFirst().unique();
            List<String> columns = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                IndexPart part = parts.get(index);
                if (part.sequence() != index + 1 || part.unique() != unique || part.column() == null) {
                    throw new IllegalStateException("Invalid funded-task index ordering for " + table);
                }
                columns.add(part.column());
            }
            result.put(name, new IndexSpec(unique, List.copyOf(columns)));
        });
        return result;
    }

    private Map<String, CheckSpec> inspectChecks(String table) {
        Map<String, CheckSpec> result = new TreeMap<>();
        jdbc.query("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                ORDER BY tc.constraint_name
                """, (RowCallbackHandler) rs -> {
            String name = rs.getString("constraint_name");
            CheckSpec check = new CheckSpec(normalizeCheck(rs.getString("check_clause")),
                    "YES".equalsIgnoreCase(rs.getString("enforced")));
            if (result.put(name, check) != null) {
                throw new IllegalStateException("Ambiguous funded-task CHECK catalog for " + table);
            }
        }, table);
        return result;
    }

    private void rejectUnexpectedTriggers() {
        List<String> triggers = jdbc.queryForList("""
                SELECT trigger_name FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN ('agent_task_funding_operation','agent_task_funding')
                       OR trigger_name LIKE 'trg_agent_task_funding%')
                ORDER BY trigger_name
                """, String.class);
        if (!triggers.isEmpty()) {
            throw new IllegalStateException("Funded-task tables must not have triggers: " + triggers);
        }
    }

    private List<String> presentTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('agent_task_funding_operation','agent_task_funding')
                ORDER BY table_name
                """, String.class);
    }

    static List<String> tableDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Missing funded-task DDL resource", failure);
        }
        String uncommented = sql.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String part : uncommented.split(";")) {
            if (!part.isBlank()) statements.add(part.strip());
        }
        if (statements.size() != TABLES.size()) {
            throw new IllegalStateException("Funded-task DDL must contain exactly two statements");
        }
        for (int index = 0; index < statements.size(); index++) {
            String normalized = statements.get(index).toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || normalized.contains(" alter table ") || normalized.contains(" insert ")
                    || normalized.contains(" update ") || normalized.contains(" delete ")) {
                throw new IllegalStateException("Unsafe funded-task DDL statement");
            }
        }
        return List.copyOf(statements);
    }

    private void requireMySql() {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Funded-task schema requires DataSource");
        try (Connection connection = source.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("Funded-task schema requires MySQL; got " + product);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to inspect funded-task database", failure);
        }
    }

    private void withLock(Runnable action) {
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Funded-task schema requires DataSource");
        try (Connection connection = source.getConnection();
             PreparedStatement acquire = connection.prepareStatement("SELECT GET_LOCK(?,10)")) {
            acquire.setString(1, LOCK);
            try (ResultSet result = acquire.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                    throw new IllegalStateException("Timed out acquiring funded-task schema lock");
                }
            }
            try {
                action.run();
            } finally {
                try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    release.setString(1, LOCK);
                    try (ResultSet result = release.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1 || result.wasNull()) {
                            throw new IllegalStateException("Funded-task schema lock was not held");
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to serialize funded-task schema initialization", failure);
        }
    }

    private static Map<String, TableSpec> expectedTables() {
        Map<String, TableSpec> tables = new LinkedHashMap<>();
        tables.put(TABLES.get(0), new TableSpec(TABLES.get(0), List.of(
                c("id","bigint","NO",null,null,"auto_increment"),
                c("principal_type","varchar(20)","NO",null,COLLATION,""),
                c("principal_id","varchar(100)","NO",null,COLLATION,""),
                c("idempotency_key","varbinary(36)","NO",null,null,""),
                c("request_hash","binary(32)","NO",null,null,""),
                c("task_id","varchar(100)","NO",null,COLLATION,""),
                c("status","varchar(16)","NO","POSTING",COLLATION,""),
                c("reserve_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("receipt_task_version","bigint","YES",null,null,""),
                c("receipt_created_at","bigint","YES",null,null,""),
                c("receipt_updated_at","bigint","YES",null,null,""),
                c("tenant_id","varchar(50)","NO",null,COLLATION,""),
                c("client_id","varchar(50)","NO",null,COLLATION,""),
                c("create_time","bigint","NO",null,null,""),
                c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id",
                "uk_task_funding_operation_actor_key",true,"tenant_id,client_id,principal_type,principal_id,idempotency_key",
                "uk_task_funding_operation_task",true,"tenant_id,client_id,task_id"), Map.of(
                "chk_task_funding_operation_hash", check("octet_length(request_hash)=32"),
                "chk_task_funding_operation_key", check("octet_length(idempotency_key)=36"),
                "chk_task_funding_operation_status", check("(status='POSTING' AND reserve_transaction_id IS NULL AND receipt_task_version IS NULL AND receipt_created_at IS NULL AND receipt_updated_at IS NULL) OR (status='COMPLETED' AND reserve_transaction_id IS NOT NULL AND receipt_task_version IS NOT NULL AND receipt_task_version=0 AND receipt_created_at IS NOT NULL AND receipt_created_at>0 AND receipt_updated_at IS NOT NULL AND receipt_updated_at>0)"))));
        tables.put(TABLES.get(1), new TableSpec(TABLES.get(1), List.of(
                c("id","bigint","NO",null,null,"auto_increment"), c("task_id","varchar(100)","NO",null,COLLATION,""),
                c("funding_mode","varchar(32)","NO",null,COLLATION,""), c("funding_status","varchar(24)","NO",null,COLLATION,""),
                c("payer_principal_type","varchar(20)","NO",null,COLLATION,""), c("payer_principal_id","varchar(100)","NO",null,COLLATION,""),
                c("settlement_policy","varchar(32)","NO",null,COLLATION,""), c("gross_bounty_amount_micro","bigint","NO",null,null,""),
                c("remaining_micro","bigint","NO",null,null,""), c("escrow_id","varchar(100)","YES",null,COLLATION,""),
                c("escrow_version","bigint","YES",null,null,""), c("reserve_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("required_skill_requirements","text","NO",null,COLLATION,""), c("cancel_idempotency_key","varbinary(36)","YES",null,null,""),
                c("cancel_request_hash","binary(32)","YES",null,null,""), c("refund_transaction_id","varchar(100)","YES",null,COLLATION,""),
                c("cancel_refunded_micro","bigint","YES",null,null,""), c("cancel_task_version","bigint","YES",null,null,""),
                c("refunded_at","bigint","YES",null,null,""), c("version","bigint","NO","0",null,""),
                c("tenant_id","varchar(50)","NO",null,COLLATION,""), c("client_id","varchar(50)","NO",null,COLLATION,""),
                c("create_time","bigint","NO",null,null,""), c("update_time","bigint","NO",null,null,"")), indexes(
                "PRIMARY",true,"id", "idx_agent_task_funding_payer",false,"tenant_id,client_id,payer_principal_type,payer_principal_id,funding_status,id",
                "uk_agent_task_funding_escrow",true,"tenant_id,client_id,escrow_id",
                "uk_agent_task_funding_task",true,"tenant_id,client_id,task_id"), Map.of(
                "chk_agent_task_funding_amount", check("gross_bounty_amount_micro>0 AND remaining_micro>=0 AND remaining_micro<=gross_bounty_amount_micro"),
                "chk_agent_task_funding_mode", check("funding_mode='FUNDED_SINGLE_AGENT'"),
                "chk_agent_task_funding_policy", check("settlement_policy='GROSS_INCLUSIVE'"),
                "chk_agent_task_funding_state", check(LEGACY_FUNDING_STATE + " OR " + SETTLED_FUNDING_STATE))));
        return Map.copyOf(tables);
    }

    private static Map<String, IndexSpec> indexes(Object... values) {
        Map<String, IndexSpec> result = new TreeMap<>();
        for (int i = 0; i < values.length; i += 3) {
            result.put((String) values[i], new IndexSpec((Boolean) values[i + 1],
                    new ArrayList<>(List.of(((String) values[i + 2]).split(",")))));
        }
        return result;
    }

    private static ColumnSpec c(String name, String type, String nullable, String defaultValue,
            String collation, String extra) {
        return new ColumnSpec(name, type, nullable, defaultValue, collation, extra);
    }

    private static ColumnSpec column(Map<String, Object> row) {
        Object defaultValue = value(row, "column_default");
        Object collation = value(row, "collation_name");
        return c(text(row, "column_name"), text(row, "column_type").toLowerCase(Locale.ROOT),
                text(row, "is_nullable"), defaultValue == null ? null : defaultValue.toString(),
                collation == null ? null : collation.toString(), text(row, "extra"));
    }

    static String normalizeCheck(String clause) {
        String normalized = normalizeEscapedCheckQuotes(clause == null ? "" : clause);
        normalized = normalizeCheckIdentifiers(normalized);
        normalized = normalizeCheckSpacing(normalizeBinaryLengthFunction(normalized));
        return normalizeCheckSpacing(removeRedundantCheckParentheses(normalized));
    }

    private static String normalizeCheckIdentifiers(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (pendingSpace && !normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != ' ') {
                    normalized.append(' ');
                }
                pendingSpace = false;
                normalized.append(character);
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    normalized.append(value.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (quoted) {
                normalized.append(character);
            } else if (character == '`') {
                continue;
            } else if (Character.isWhitespace(character)) {
                pendingSpace = true;
            } else {
                if (pendingSpace && !normalized.isEmpty()
                        && normalized.charAt(normalized.length() - 1) != ' ') normalized.append(' ');
                pendingSpace = false;
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString().trim();
    }

    private static String normalizeEscapedCheckQuotes(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        boolean escapedDelimiter = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!quoted && character == '\\' && index + 1 < value.length()
                    && value.charAt(index + 1) == '\'') {
                normalized.append('\'');
                quoted = true;
                escapedDelimiter = true;
                index++;
            } else if (quoted && escapedDelimiter && character == '\\'
                    && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                if (index + 3 < value.length() && value.charAt(index + 2) == '\\'
                        && value.charAt(index + 3) == '\'') {
                    normalized.append("''");
                    index += 3;
                } else {
                    normalized.append('\'');
                    quoted = false;
                    escapedDelimiter = false;
                    index++;
                }
            } else {
                normalized.append(character);
                if (character == '\'') {
                    if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                        normalized.append(value.charAt(++index));
                    } else {
                        quoted = !quoted;
                        escapedDelimiter = false;
                    }
                }
            }
        }
        return normalized.toString();
    }

    private static String normalizeBinaryLengthFunction(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                normalized.append(character);
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    normalized.append(value.charAt(++index));
                } else {
                    quoted = !quoted;
                }
            } else if (quoted || !isSqlWordCharacter(character)) {
                normalized.append(character);
            } else {
                int end = index + 1;
                while (end < value.length() && isSqlWordCharacter(value.charAt(end))) end++;
                String word = value.substring(index, end);
                int next = end;
                while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
                normalized.append(("length".equals(word) || "octet_length".equals(word))
                        && next < value.length() && value.charAt(next) == '('
                        ? "octet_length" : word);
                index = end - 1;
            }
        }
        return normalized.toString();
    }

    private static String normalizeCheckSpacing(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                appendCheckSpace(normalized, pendingSpace);
                pendingSpace = false;
                normalized.append(character);
                while (++index < value.length()) {
                    character = value.charAt(index);
                    normalized.append(character);
                    if (character == '\'' && index + 1 < value.length()
                            && value.charAt(index + 1) == '\'') {
                        normalized.append(value.charAt(++index));
                    } else if (character == '\'') {
                        break;
                    }
                }
            } else if (Character.isWhitespace(character)) {
                pendingSpace = true;
            } else if (isCharsetIntroducer(value, index)) {
                index += "_utf8mb4".length() - 1;
                while (index + 1 < value.length() && Character.isWhitespace(value.charAt(index + 1))) index++;
            } else if (character == '(') {
                trimTrailingSpace(normalized);
                if (requiresSpaceBeforeOpenParenthesis(normalized)) normalized.append(' ');
                normalized.append(character);
                pendingSpace = false;
            } else if (character == ')' || character == ',') {
                trimTrailingSpace(normalized);
                normalized.append(character);
                pendingSpace = false;
            } else if (isComparisonOperator(character)) {
                trimTrailingSpace(normalized);
                normalized.append(character);
                if (index + 1 < value.length() && isComparisonPair(character, value.charAt(index + 1))) {
                    normalized.append(value.charAt(++index));
                }
                pendingSpace = false;
            } else {
                appendCheckSpace(normalized, pendingSpace);
                pendingSpace = false;
                if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == ')'
                        && isSqlWordCharacter(character)) normalized.append(' ');
                normalized.append(character);
            }
        }
        return normalized.toString().trim();
    }

    private static void appendCheckSpace(StringBuilder normalized, boolean pendingSpace) {
        if (!pendingSpace || normalized.isEmpty()) return;
        char previous = normalized.charAt(normalized.length() - 1);
        if (previous != '(' && previous != ',' && !isComparisonOperator(previous)) normalized.append(' ');
    }

    private static void trimTrailingSpace(StringBuilder value) {
        while (!value.isEmpty() && Character.isWhitespace(value.charAt(value.length() - 1))) {
            value.setLength(value.length() - 1);
        }
    }

    private static boolean requiresSpaceBeforeOpenParenthesis(StringBuilder value) {
        int end = value.length();
        int start = end;
        while (start > 0 && isSqlWordCharacter(value.charAt(start - 1))) start--;
        if (start == end) return false;
        String word = value.substring(start, end);
        return "in".equals(word) || "and".equals(word) || "or".equals(word)
                || "not".equals(word) || "exists".equals(word);
    }

    private static boolean isCharsetIntroducer(String value, int index) {
        String introducer = "_utf8mb4";
        if (!value.regionMatches(true, index, introducer, 0, introducer.length())
                || index > 0 && isSqlWordCharacter(value.charAt(index - 1))) return false;
        int next = index + introducer.length();
        while (next < value.length() && Character.isWhitespace(value.charAt(next))) next++;
        return next < value.length() && value.charAt(next) == '\'';
    }

    private static boolean isSqlWordCharacter(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static boolean isComparisonOperator(char character) {
        return character == '<' || character == '>' || character == '=' || character == '!';
    }

    private static boolean isComparisonPair(char first, char second) {
        return second == '=' && (first == '<' || first == '>' || first == '!')
                || first == '<' && second == '>';
    }

    private static String removeRedundantCheckParentheses(String value) {
        String current = stripOuterParentheses(value);
        boolean changed;
        do {
            changed = false;
            StringBuilder next = new StringBuilder(current);
            List<Integer> stack = new ArrayList<>();
            boolean quoted = false;
            for (int index = 0; index < current.length(); index++) {
                char character = current.charAt(index);
                if (character == '\'') {
                    if (quoted && index + 1 < current.length() && current.charAt(index + 1) == '\'') {
                        index++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (!quoted && character == '(') {
                    stack.add(index);
                } else if (!quoted && character == ')' && !stack.isEmpty()) {
                    int open = stack.removeLast();
                    if (!isFunctionOrInParenthesis(current, open)
                            && !containsTopLevelBoolean(current, open + 1, index)) {
                        next.setCharAt(open, ' ');
                        next.setCharAt(index, ' ');
                        changed = true;
                    }
                }
            }
            current = stripOuterParentheses(normalizeCheckSpacing(next.toString()));
        } while (changed);
        return current;
    }

    private static boolean isFunctionOrInParenthesis(String value, int open) {
        int cursor = open - 1;
        while (cursor >= 0 && Character.isWhitespace(value.charAt(cursor))) cursor--;
        int end = cursor + 1;
        while (cursor >= 0 && isSqlWordCharacter(value.charAt(cursor))) cursor--;
        if (end == cursor + 1) return false;
        String word = value.substring(cursor + 1, end);
        return "in".equals(word) || "octet_length".equals(word);
    }

    private static boolean containsTopLevelBoolean(String value, int start, int end) {
        int depth = 0;
        boolean quoted = false;
        for (int index = start; index < end; index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < end && value.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && character == '(') {
                depth++;
            } else if (!quoted && character == ')') {
                depth--;
            } else if (!quoted && depth == 0 && Character.isLetter(character)) {
                int wordStart = index;
                while (index < end && Character.isLetter(value.charAt(index))) index++;
                String word = value.substring(wordStart, index);
                if ("and".equals(word) || "or".equals(word)) return true;
                index--;
            }
        }
        return false;
    }

    private static String stripOuterParentheses(String value) {
        String current = value.trim();
        while (current.startsWith("(") && current.endsWith(")")
                && matchingParenthesis(current, 0) == current.length() - 1) {
            current = current.substring(1, current.length() - 1).trim();
        }
        return current;
    }

    private static int matchingParenthesis(String value, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\'') {
                if (quoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && character == '(') {
                depth++;
            } else if (!quoted && character == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static Object value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = value(row, key);
        return value == null ? "" : value.toString();
    }

    private static CheckSpec check(String clause) {
        return new CheckSpec(normalizeCheck(clause), true);
    }

    private record TableSpec(String name, List<ColumnSpec> columns,
            Map<String, IndexSpec> indexes, Map<String, CheckSpec> checks) { }
    private record ColumnSpec(String name, String type, String nullable,
            String defaultValue, String collation, String extra) { }
    private record IndexSpec(boolean unique, List<String> columns) { }
    private record IndexPart(boolean unique, int sequence, String column) { }
    private record CheckSpec(String expression, boolean enforced) { }
}
