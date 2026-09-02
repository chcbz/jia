package cn.jia.economy.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Fail-closed initializer and catalog validator for the exact ECO-V0-W02 five-table schema. */
public final class EconomySchemaInitializer implements InitializingBean {
    static final String DDL_RESOURCE = "db/economy-v0-foundation.sql";
    static final List<String> TABLES = List.of(
            "economy_account", "economy_transaction", "economy_entry",
            "economy_escrow", "economy_escrow_funding_lot");
    private static final String BINARY_COLLATION = "utf8mb4_0900_bin";
    static final List<String> TRIGGER_ORDER = List.of(
            "trg_economy_transaction_posted_no_update",
            "trg_economy_transaction_posted_no_delete",
            "trg_economy_entry_no_update",
            "trg_economy_entry_no_delete",
            "trg_economy_funding_lot_no_update",
            "trg_economy_funding_lot_no_delete");

    private final JdbcTemplate jdbcTemplate;

    public EconomySchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public void afterPropertiesSet() {
        requireMySql();
        List<String> present = inspectPresentTables();
        if (present.isEmpty()) {
            for (String statement : tableDdlStatements()) jdbcTemplate.execute(statement);
        } else if (!sameTables(present, TABLES)) {
            throw partialSchema(present);
        }
        present = inspectPresentTables();
        if (!sameTables(present, TABLES)) {
            throw new IllegalStateException("ECO-V0 schema creation did not produce exact 5/5 tables: " + present);
        }
        validateSchema();
    }

    void validateSchema() {
        List<String> present = inspectPresentTables();
        if (!sameTables(present, TABLES)) throw partialSchema(present);
        for (TableExpectation expectation : expectedTables().values()) validateTable(expectation);
        ensureAndValidateTriggers();
    }

    private void requireMySql() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) throw new IllegalStateException("ECO-V0 schema requires a JDBC DataSource");
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new IllegalStateException("ECO-V0 schema requires MySQL; got " + product);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to determine ECO-V0 database dialect", exception);
        }
    }

    private List<String> inspectPresentTables() {
        return jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema=DATABASE()
                  AND table_name IN ('economy_account','economy_transaction','economy_entry',
                                     'economy_escrow','economy_escrow_funding_lot')
                ORDER BY table_name
                """, String.class);
    }

    private void validateTable(TableExpectation expected) {
        List<TableDefinition> tables = jdbcTemplate.query("""
                SELECT engine,table_collation
                FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """, (rs, rowNum) -> new TableDefinition(
                rs.getString("engine"), rs.getString("table_collation")), expected.name());
        if (tables.size() != 1
                || !"InnoDB".equalsIgnoreCase(tables.getFirst().engine())
                || !BINARY_COLLATION.equalsIgnoreCase(tables.getFirst().collation())) {
            throw new IllegalStateException("ECO-V0 table " + expected.name()
                    + " must be exact InnoDB/" + BINARY_COLLATION + ", got " + tables);
        }
        List<ColumnDefinition> columns = jdbcTemplate.query("""
                SELECT column_name,data_type,column_type,is_nullable,column_default,collation_name,extra
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY ordinal_position
                """, (rs, rowNum) -> new ColumnDefinition(
                rs.getString("column_name"), rs.getString("data_type"), rs.getString("column_type"),
                "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                rs.getString("column_default"), rs.getString("collation_name"), rs.getString("extra")),
                expected.name());
        if (!expected.columns().equals(columns)) {
            throw new IllegalStateException("ECO-V0 table " + expected.name()
                    + " has incompatible columns: " + columns);
        }
        Map<String, IndexDefinition> indexes = inspectIndexes(expected.name());
        if (!expected.indexes().equals(indexes)) {
            throw new IllegalStateException("ECO-V0 table " + expected.name()
                    + " has incompatible indexes: " + indexes);
        }
        Map<String, CheckDefinition> checks = inspectChecks(expected.name());
        if (!expected.checks().equals(checks)) {
            throw new IllegalStateException("ECO-V0 table " + expected.name()
                    + " has incompatible CHECK constraints: " + checks);
        }
        Integer foreignKeys = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.referential_constraints
                WHERE (constraint_schema=DATABASE() AND table_name=?)
                   OR (unique_constraint_schema=DATABASE() AND referenced_table_name=?)
                """, Integer.class, expected.name(), expected.name());
        if (foreignKeys == null || foreignKeys != 0) {
            throw new IllegalStateException("ECO-V0 table " + expected.name()
                    + " must not participate in database foreign keys");
        }
    }

    private Map<String, CheckDefinition> inspectChecks(String table) {
        List<CheckDefinition> rows = jdbcTemplate.query("""
                SELECT tc.constraint_name,cc.check_clause,tc.enforced
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_catalog=tc.constraint_catalog
                 AND cc.constraint_schema=tc.constraint_schema
                 AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name=?
                  AND tc.constraint_type='CHECK'
                ORDER BY tc.constraint_name
                """, (rs, rowNum) -> new CheckDefinition(
                rs.getString("constraint_name"),
                normalizeCheckClause(rs.getString("check_clause")),
                "YES".equalsIgnoreCase(rs.getString("enforced"))), table);
        Map<String, CheckDefinition> result = new TreeMap<>();
        for (CheckDefinition row : rows) {
            if (result.put(row.name(), row) != null) {
                throw new IllegalStateException("ECO-V0 table " + table
                        + " has an ambiguous CHECK catalog");
            }
        }
        return result;
    }

    private Map<String, IndexDefinition> inspectIndexes(String table) {
        List<IndexColumn> rows = jdbcTemplate.query("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type,is_visible
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, (rs, rowNum) -> new IndexColumn(
                rs.getString("index_name"), rs.getInt("non_unique") != 0,
                rs.getInt("seq_in_index"), rs.getString("column_name"),
                (Integer) rs.getObject("sub_part"), rs.getString("index_type"),
                rs.getString("is_visible")), table);
        Map<String, List<IndexColumn>> grouped = new TreeMap<>();
        for (IndexColumn row : rows) {
            if (row.subPart() != null || !"BTREE".equalsIgnoreCase(row.indexType())
                    || !"YES".equalsIgnoreCase(row.visible())) {
                throw new IllegalStateException("ECO-V0 table " + table
                        + " has incompatible index component: " + row);
            }
            grouped.computeIfAbsent(row.name(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, IndexDefinition> result = new TreeMap<>();
        for (Map.Entry<String, List<IndexColumn>> entry : grouped.entrySet()) {
            List<IndexColumn> parts = entry.getValue();
            boolean nonUnique = parts.getFirst().nonUnique();
            List<String> columns = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                IndexColumn part = parts.get(index);
                if (part.sequence() != index + 1 || part.nonUnique() != nonUnique) {
                    throw new IllegalStateException("ECO-V0 table " + table
                            + " has incompatible index ordering: " + parts);
                }
                columns.add(part.column());
            }
            result.put(entry.getKey(), new IndexDefinition(entry.getKey(), !nonUnique, List.copyOf(columns)));
        }
        return result;
    }

    private void ensureAndValidateTriggers() {
        Map<String, TriggerDefinition> expected = expectedTriggers();
        Map<String, TriggerDefinition> actual = inspectTriggers();
        for (TriggerDefinition found : actual.values()) {
            TriggerDefinition required = expected.get(found.name());
            if (required == null || !triggerMatches(required, found)) {
                throw new IllegalStateException("ECO-V0 trigger " + found.name() + " is incompatible");
            }
        }
        for (TriggerDefinition required : expected.values()) {
            if (!actual.containsKey(required.name())) jdbcTemplate.execute(createTriggerSql(required));
        }
        actual = inspectTriggers();
        if (actual.size() != expected.size()) {
            throw new IllegalStateException("ECO-V0 requires six exact immutable-row triggers");
        }
        for (TriggerDefinition required : expected.values()) {
            TriggerDefinition found = actual.get(required.name());
            if (found == null || !triggerMatches(required, found)) {
                throw new IllegalStateException("ECO-V0 trigger " + required.name() + " is incompatible");
            }
        }
    }

    private Map<String, TriggerDefinition> inspectTriggers() {
        List<TriggerDefinition> rows = jdbcTemplate.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN ('economy_transaction','economy_entry','economy_escrow_funding_lot')
                       OR trigger_name LIKE 'trg_economy_%')
                ORDER BY trigger_name
                """, (rs, rowNum) -> new TriggerDefinition(
                rs.getString("trigger_name"), rs.getString("event_object_table"),
                rs.getString("action_timing"), rs.getString("event_manipulation"),
                rs.getString("action_statement")));
        Map<String, TriggerDefinition> result = new TreeMap<>();
        for (TriggerDefinition row : rows) {
            if (result.put(row.name(), row) != null) {
                throw new IllegalStateException("ECO-V0 trigger catalog is ambiguous");
            }
        }
        return result;
    }

    static List<String> tableDdlStatements() {
        List<String> all = allDdlStatements();
        if (all.size() != TABLES.size() + 12) {
            throw new IllegalStateException("ECO-V0 DDL must contain five tables and six trigger replacements");
        }
        List<String> tables = all.subList(0, TABLES.size());
        for (int index = 0; index < tables.size(); index++) {
            String normalized = normalizeSql(tables.get(index));
            if (!normalized.startsWith("create table if not exists " + TABLES.get(index) + " ")
                    || containsDml(normalized)) {
                throw new IllegalStateException("ECO-V0 DDL contains unsafe or reordered table SQL");
            }
        }
        List<String> expectedTriggerMigration = new ArrayList<>();
        for (String name : TRIGGER_ORDER) {
            expectedTriggerMigration.add("DROP TRIGGER IF EXISTS " + name);
        }
        for (String name : TRIGGER_ORDER) {
            expectedTriggerMigration.add(createTriggerSql(expectedTriggers().get(name)));
        }
        List<String> actualMigration = all.subList(TABLES.size(), all.size());
        for (int index = 0; index < actualMigration.size(); index++) {
            if (!normalizeTriggerSql(actualMigration.get(index))
                    .equals(normalizeTriggerSql(expectedTriggerMigration.get(index)))) {
                throw new IllegalStateException("ECO-V0 trigger migration drift at statement "
                        + (TABLES.size() + index + 1));
            }
        }
        return List.copyOf(tables);
    }

    private static List<String> allDdlStatements() {
        String sql;
        try {
            sql = new ClassPathResource(DDL_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing ECO-V0 DDL resource " + DDL_RESOURCE, exception);
        }
        return splitSql(sql);
    }

    static List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean lineComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (lineComment) {
                if (character == '\n' || character == '\r') {
                    lineComment = false;
                    current.append(' ');
                }
                continue;
            }
            if (!quoted && character == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                lineComment = true;
                index++;
                continue;
            }
            if (character == '\'' && (index == 0 || sql.charAt(index - 1) != '\\')) quoted = !quoted;
            if (character == ';' && !quoted) {
                String statement = current.toString().trim();
                if (isOpenTriggerBody(statement)) {
                    current.append(character);
                } else {
                    if (!statement.isEmpty()) statements.add(statement);
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) statements.add(tail);
        return List.copyOf(statements);
    }

    static Map<String, TriggerDefinition> expectedTriggers() {
        Map<String, TriggerDefinition> triggers = new TreeMap<>();
        triggers.put("trg_economy_transaction_posted_no_update", new TriggerDefinition(
                "trg_economy_transaction_posted_no_update", "economy_transaction", "BEFORE", "UPDATE",
                "BEGIN IF OLD.status = 'POSTED' THEN SIGNAL SQLSTATE '45000' "
                        + "SET MESSAGE_TEXT = 'ECO-V0: POSTED economy transactions are immutable'; END IF; END"));
        triggers.put("trg_economy_transaction_posted_no_delete", signal(
                "trg_economy_transaction_posted_no_delete", "economy_transaction", "DELETE",
                "ECO-V0: economy transaction deletion is forbidden"));
        triggers.put("trg_economy_entry_no_update", signal(
                "trg_economy_entry_no_update", "economy_entry", "UPDATE",
                "ECO-V0: POSTED economy entries are immutable"));
        triggers.put("trg_economy_entry_no_delete", signal(
                "trg_economy_entry_no_delete", "economy_entry", "DELETE",
                "ECO-V0: economy entry deletion is forbidden"));
        triggers.put("trg_economy_funding_lot_no_update", signal(
                "trg_economy_funding_lot_no_update", "economy_escrow_funding_lot", "UPDATE",
                "ECO-V0: escrow funding lots are immutable"));
        triggers.put("trg_economy_funding_lot_no_delete", signal(
                "trg_economy_funding_lot_no_delete", "economy_escrow_funding_lot", "DELETE",
                "ECO-V0: escrow funding lot deletion is forbidden"));
        return Map.copyOf(triggers);
    }

    private static TriggerDefinition signal(String name, String table, String event, String message) {
        return new TriggerDefinition(name, table, "BEFORE", event,
                "BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + message + "'; END");
    }

    private static String createTriggerSql(TriggerDefinition trigger) {
        return "CREATE TRIGGER " + trigger.name() + " " + trigger.timing() + " " + trigger.event()
                + " ON " + trigger.table() + " FOR EACH ROW " + trigger.statement();
    }

    private static boolean triggerMatches(TriggerDefinition expected, TriggerDefinition actual) {
        return expected.table().equalsIgnoreCase(actual.table())
                && expected.timing().equalsIgnoreCase(actual.timing())
                && expected.event().equalsIgnoreCase(actual.event())
                && normalizeTriggerSql(expected.statement()).equals(normalizeTriggerSql(actual.statement()));
    }

    static String normalizeSql(String sql) {
        return sql.replaceAll("(?m)^\\s*--.*$", " ")
                .replace("`", "")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSqlPreservingLiterals(String sql) {
        StringBuilder normalized = new StringBuilder(sql.length());
        boolean quoted = false;
        boolean pendingSpace = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (character == '\'') {
                if (pendingSpace && !normalized.isEmpty()
                        && normalized.charAt(normalized.length() - 1) != ' ') normalized.append(' ');
                pendingSpace = false;
                normalized.append(character);
                if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    normalized.append(sql.charAt(++index));
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

    private static boolean isOpenTriggerBody(String statement) {
        String normalized = normalizeSql(statement);
        return normalized.startsWith("create trigger ") && !normalized.endsWith(" end");
    }

    static String normalizeCheckClause(String sql) {
        if (sql == null) return null;
        String normalized = normalizeCheckSpacing(normalizeSqlPreservingLiterals(sql));
        return normalizeCheckSpacing(removeRedundantCheckParentheses(normalized));
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
        if (!value.regionMatches(index, introducer, 0, introducer.length())
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
        while (cursor >= 0 && (Character.isLetterOrDigit(value.charAt(cursor))
                || value.charAt(cursor) == '_')) cursor--;
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
        for (int index = open; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '(') depth++;
            else if (character == ')' && --depth == 0) return index;
        }
        return -1;
    }

    private static String normalizeTriggerSql(String sql) {
        return normalizeSqlPreservingLiterals(sql).replace("_utf8mb4", "")
                .replace("(", "").replace(")", "")
                .replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean containsDml(String sql) {
        return sql.matches("(?s).*\\binsert\\s+into\\b.*")
                || sql.matches("(?s).*\\bupdate\\s+[`a-z0-9_]+\\s+set\\b.*")
                || sql.matches("(?s).*\\bdelete\\s+from\\b.*")
                || sql.matches("(?s).*\\breplace\\s+into\\b.*")
                || sql.matches("(?s).*\\bmerge\\s+into\\b.*");
    }

    static Map<String, TableExpectation> expectedTables() {
        Map<String, TableExpectation> tables = new LinkedHashMap<>();
        tables.put("economy_account", table("economy_account", List.of(
                id(), varchar("account_id", 100, false, null), varchar("owner_type", 20, false, null),
                varchar("owner_id", 100, false, null), varchar("purpose", 32, false, null),
                varchar("currency", 16, false, null), bigint("balance_micro", false, "0"),
                tinyint("allow_negative", false, "0"), varchar("status", 16, false, "ACTIVE"),
                bigint("version", false, "0"), varchar("tenant_id", 50, false, null),
                varchar("client_id", 50, false, null), bigint("create_time", false, null),
                bigint("update_time", false, null)), List.of(
                index("PRIMARY", true, "id"),
                index("uk_economy_account_id", true, "tenant_id", "client_id", "account_id"),
                index("uk_economy_account_key", true, "tenant_id", "client_id", "currency", "owner_type", "owner_id", "purpose"),
                index("idx_economy_account_owner", false, "tenant_id", "client_id", "owner_type", "owner_id", "currency", "purpose")),
                check("chk_economy_account_currency", "currency = 'SILVER'"),
                check("chk_economy_account_negative", "allow_negative IN (0,1) "
                        + "AND (owner_type <> 'USER' OR purpose <> 'AVAILABLE' OR allow_negative = 0) "
                        + "AND (allow_negative = 1 OR balance_micro >= 0)"),
                check("chk_economy_account_status", "status IN ('ACTIVE','FROZEN','CLOSED')"),
                check("chk_economy_account_version", "version >= 0")));
        tables.put("economy_transaction", table("economy_transaction", List.of(
                id(), varchar("transaction_id", 100, false, null), varchar("principal_type", 20, false, null),
                varchar("principal_id", 100, false, null), varbinary("idempotency_key", 36), binary("request_hash", 32),
                varchar("business_type", 32, false, null), varchar("business_id", 100, false, null),
                varchar("currency", 16, false, null), varchar("status", 16, false, "POSTING"),
                integer("entry_count", false, "0"), bigint("debit_total_micro", false, "0"),
                bigint("credit_total_micro", false, "0"), bigint("posted_at", true, null),
                varchar("tenant_id", 50, false, null), varchar("client_id", 50, false, null),
                bigint("create_time", false, null), bigint("update_time", false, null)), List.of(
                index("PRIMARY", true, "id"),
                index("uk_economy_transaction_id", true, "tenant_id", "client_id", "transaction_id"),
                index("uk_economy_transaction_idempotency", true, "tenant_id", "client_id", "principal_type", "principal_id", "idempotency_key"),
                index("idx_economy_transaction_business", false, "tenant_id", "client_id", "business_type", "business_id", "posted_at", "id")),
                check("chk_economy_transaction_currency", "currency = 'SILVER'"),
                check("chk_economy_transaction_hash", "OCTET_LENGTH(request_hash) = 32"),
                check("chk_economy_transaction_key", "OCTET_LENGTH(idempotency_key) = 36"),
                check("chk_economy_transaction_state", "(status = 'POSTING' AND posted_at IS NULL "
                        + "AND entry_count = 0 AND debit_total_micro = 0 AND credit_total_micro = 0) OR "
                        + "(status = 'POSTED' AND posted_at IS NOT NULL AND entry_count >= 2 "
                        + "AND debit_total_micro > 0 AND debit_total_micro = credit_total_micro)"),
                check("chk_economy_transaction_totals", "entry_count >= 0 AND debit_total_micro >= 0 "
                        + "AND credit_total_micro >= 0")));
        tables.put("economy_entry", table("economy_entry", List.of(
                id(), varchar("entry_id", 140, false, null), varchar("transaction_id", 100, false, null),
                varchar("account_id", 100, false, null), integer("entry_sequence", false, null),
                bigint("signed_amount_micro", false, null), bigint("balance_after_micro", false, null),
                varchar("currency", 16, false, null), varchar("status", 16, false, "POSTED"),
                bigint("posted_at", false, null), varchar("tenant_id", 50, false, null),
                varchar("client_id", 50, false, null), bigint("create_time", false, null)), List.of(
                index("PRIMARY", true, "id"),
                index("uk_economy_entry_id", true, "tenant_id", "client_id", "entry_id"),
                index("uk_economy_entry_sequence", true, "tenant_id", "client_id", "transaction_id", "entry_sequence"),
                index("idx_economy_entry_account", false, "tenant_id", "client_id", "account_id", "posted_at", "id"),
                index("idx_economy_entry_transaction", false, "tenant_id", "client_id", "transaction_id", "id")),
                check("chk_economy_entry_amount", "signed_amount_micro <> 0"),
                check("chk_economy_entry_currency", "currency = 'SILVER'"),
                check("chk_economy_entry_sequence", "entry_sequence > 0"),
                check("chk_economy_entry_status", "status = 'POSTED'")));
        tables.put("economy_escrow", table("economy_escrow", List.of(
                id(), varchar("escrow_id", 100, false, null), varchar("business_type", 32, false, null),
                varchar("business_id", 100, false, null), varchar("payer_account_id", 100, false, null),
                varchar("escrow_account_id", 100, false, null), varchar("currency", 16, false, null),
                bigint("gross_micro", false, null), bigint("captured_micro", false, "0"),
                bigint("refunded_micro", false, "0"), varchar("status", 24, false, "ACTIVE"),
                bigint("version", false, "1"), varchar("tenant_id", 50, false, null),
                varchar("client_id", 50, false, null), bigint("create_time", false, null),
                bigint("update_time", false, null)), List.of(
                index("PRIMARY", true, "id"),
                index("uk_economy_escrow_id", true, "tenant_id", "client_id", "escrow_id"),
                index("uk_economy_escrow_business", true, "tenant_id", "client_id", "business_type", "business_id"),
                index("uk_economy_escrow_account", true, "tenant_id", "client_id", "escrow_account_id"),
                index("idx_economy_escrow_payer", false, "tenant_id", "client_id", "payer_account_id", "status", "id")),
                check("chk_economy_escrow_amounts", "gross_micro > 0 AND captured_micro >= 0 "
                        + "AND refunded_micro >= 0 AND captured_micro <= gross_micro - refunded_micro"),
                check("chk_economy_escrow_currency", "currency = 'SILVER'"),
                check("chk_economy_escrow_status", "status IN ('ACTIVE','PARTIALLY_CAPTURED','CAPTURED',"
                        + "'REFUNDED','EXPIRED')"),
                check("chk_economy_escrow_version", "version > 0")));
        tables.put("economy_escrow_funding_lot", table("economy_escrow_funding_lot", List.of(
                id(), varchar("escrow_id", 100, false, null), integer("funding_sequence", false, null),
                varchar("reserve_transaction_id", 100, false, null), varchar("payer_account_id", 100, false, null),
                bigint("amount_micro", false, null), bigint("escrow_gross_after_micro", false, null),
                bigint("escrow_version_after", false, null), varchar("currency", 16, false, null),
                varchar("tenant_id", 50, false, null), varchar("client_id", 50, false, null),
                bigint("created_at", false, null)), List.of(
                index("PRIMARY", true, "id"),
                index("uk_economy_funding_sequence", true, "tenant_id", "client_id", "escrow_id", "funding_sequence"),
                index("uk_economy_funding_transaction", true, "tenant_id", "client_id", "reserve_transaction_id"),
                index("idx_economy_funding_escrow", false, "tenant_id", "client_id", "escrow_id", "id")),
                check("chk_economy_funding_amount", "amount_micro > 0 "
                        + "AND escrow_gross_after_micro >= amount_micro"),
                check("chk_economy_funding_currency", "currency = 'SILVER'"),
                check("chk_economy_funding_sequence", "funding_sequence > 0"),
                check("chk_economy_funding_version", "escrow_version_after > 0")));
        return Map.copyOf(tables);
    }

    private static TableExpectation table(String name, List<ColumnDefinition> columns,
                                          List<IndexDefinition> indexes, CheckDefinition... checks) {
        Map<String, IndexDefinition> byName = new TreeMap<>();
        indexes.forEach(index -> byName.put(index.name(), index));
        Map<String, CheckDefinition> checksByName = new TreeMap<>();
        for (CheckDefinition check : checks) checksByName.put(check.name(), check);
        return new TableExpectation(name, columns, Map.copyOf(byName), Map.copyOf(checksByName));
    }

    private static CheckDefinition check(String name, String clause) {
        return new CheckDefinition(name, normalizeCheckClause(clause), true);
    }

    private static ColumnDefinition id() {
        return new ColumnDefinition("id", "bigint", "bigint", false, null, null, "auto_increment");
    }

    private static ColumnDefinition varchar(String name, int length, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "varchar", "varchar(" + length + ")", nullable,
                defaultValue, BINARY_COLLATION, "");
    }

    private static ColumnDefinition bigint(String name, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "bigint", "bigint", nullable, defaultValue, null, "");
    }

    private static ColumnDefinition integer(String name, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "int", "int", nullable, defaultValue, null, "");
    }

    private static ColumnDefinition tinyint(String name, boolean nullable, String defaultValue) {
        return new ColumnDefinition(name, "tinyint", "tinyint", nullable, defaultValue, null, "");
    }

    private static ColumnDefinition binary(String name, int length) {
        return new ColumnDefinition(name, "binary", "binary(" + length + ")", false, null, null, "");
    }

    private static ColumnDefinition varbinary(String name, int length) {
        return new ColumnDefinition(name, "varbinary", "varbinary(" + length + ")", false, null, null, "");
    }

    private static IndexDefinition index(String name, boolean unique, String... columns) {
        return new IndexDefinition(name, unique, List.of(columns));
    }

    private static boolean sameTables(List<String> present, List<String> expected) {
        return present.size() == expected.size() && Set.copyOf(present).equals(Set.copyOf(expected));
    }

    private static IllegalStateException partialSchema(List<String> present) {
        return new IllegalStateException("ECO-V0 economy schema must be exact 0/5 or 5/5; found "
                + present.size() + "/5: " + present);
    }

    record TableDefinition(String engine, String collation) {
    }

    record ColumnDefinition(String name, String dataType, String columnType, boolean nullable,
                            String defaultValue, String collation, String extra) {
    }

    record IndexDefinition(String name, boolean unique, List<String> columns) {
    }

    record IndexColumn(String name, boolean nonUnique, int sequence, String column,
                       Integer subPart, String indexType, String visible) {
    }

    record CheckDefinition(String name, String normalizedClause, boolean enforced) {
    }

    record TableExpectation(String name, List<ColumnDefinition> columns,
                            Map<String, IndexDefinition> indexes, Map<String, CheckDefinition> checks) {
    }

    record TriggerDefinition(String name, String table, String timing, String event, String statement) {
    }
}
