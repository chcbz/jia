package cn.jia.chat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Default-off, additive-only MySQL8 readiness gate for typed deliberation v3. */
@Component
public final class ChatTypedDeliberationSchemaInitializer implements ApplicationRunner {
    static final String LOCK = "cyf:chat-deliberation:v2";
    static final String RESOURCE = "db/chat-typed-deliberation-schema-v1.sql";
    static final List<String> TABLES = List.of("chat_typed_outcome", "chat_typed_pending_question",
            "chat_typed_proposal", "chat_typed_admission");

    private static final Map<String, List<Column>> COLUMNS = expectedColumns();
    private static final Map<String, Map<String, Index>> INDEXES = expectedIndexes();
    private static final Map<String, ForeignKey> FOREIGN_KEYS = expectedForeignKeys();
    private static final String KIND_CHECK = "chk_chat_typed_outcome_kind";
    private static final String PREVIOUS_KIND = "kind IN ('ANSWER','CLARIFY','EXECUTION_PROPOSAL')";
    private static final String ACTION_KIND = "kind IN ('ANSWER','CLARIFY','EXECUTION_PROPOSAL','ACTION_REQUEST')";
    private static final Map<String, String> CHECKS = expectedChecks();

    private final JdbcTemplate jdbc;
    private final ChatDeliberationSchemaInitializer deliberationSchema;
    private final boolean enabled;
    private final boolean allowMigration;
    private volatile boolean ready;

    @Autowired
    public ChatTypedDeliberationSchemaInitializer(JdbcTemplate jdbc,
            ChatDeliberationSchemaInitializer deliberationSchema,
            @Value("${chat.typed-deliberation.enabled:false}") boolean enabled,
            @Value("${cyf.chat.typed-deliberation-schema.allow-additive-migration:false}") boolean allowMigration) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.deliberationSchema = Objects.requireNonNull(deliberationSchema);
        this.enabled = enabled;
        this.allowMigration = allowMigration;
    }

    public ChatTypedDeliberationSchemaInitializer(JdbcTemplate jdbc,
            boolean enabled, boolean allowMigration) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.deliberationSchema = null;
        this.enabled = enabled;
        this.allowMigration = allowMigration;
    }

    @Override public void run(ApplicationArguments args) throws Exception { initialize(); }
    public boolean ready() { return enabled && ready; }

    public synchronized void initialize() throws Exception {
        ready = false;
        if (!enabled) return;
        if (deliberationSchema != null) deliberationSchema.ensureInitialized();
        DataSource source = jdbc.getDataSource();
        if (source == null) throw new IllegalStateException("Typed deliberation DataSource unavailable");
        try (Connection connection = source.getConnection()) {
            requireMySql8(connection);
            JdbcTemplate locked = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            acquire(locked);
            try {
                int existing = countTables(locked);
                if (existing != TABLES.size()) {
                    if (existing != 0) throw drift("partial tables");
                    if (!allowMigration) return;
                    for (String statement : ddl()) locked.execute(statement);
                }
                if (!ensureActionCatalog(locked, allowMigration)) return;
                if (allowMigration) {
                    locked.update("""
                            INSERT INTO chat_deliberation_schema_version(version,stage,updated_at)
                            VALUES(3,'APPLIED',?)
                            ON DUPLICATE KEY UPDATE stage=VALUES(stage),updated_at=VALUES(updated_at)
                            """, System.currentTimeMillis());
                }
                if (!versionThreeApplied(locked)) return;
                ready = true;
            } finally {
                release(locked);
            }
        }
    }

    static List<String> ddl() {
        try {
            return parseDdl(new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException("Typed deliberation DDL unavailable", failure);
        }
    }

    static List<String> parseDdl(String source) {
        String clean = source.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (left, right) -> left + right + '\n');
        List<String> statements = new ArrayList<>();
        for (String part : clean.split(";")) if (!part.isBlank()) statements.add(part.strip());
        if (statements.size() != TABLES.size()) throw new IllegalStateException("Typed deliberation DDL statement count");
        for (int i = 0; i < statements.size(); i++) {
            if (!approvedDdlStatement(statements.get(i), TABLES.get(i))) {
                throw new IllegalStateException("Unsafe typed deliberation DDL");
            }
        }
        return List.copyOf(statements);
    }

    static boolean approvedDdlStatement(String statement, String table) {
        if (statement == null || table == null) return false;
        String normalized = statement.toLowerCase(Locale.ROOT);
        String exactForeignKeyAction = "on update restrict on delete restrict";
        if (!normalized.startsWith("create table if not exists " + table + " ")
                || occurrences(normalized, exactForeignKeyAction) != 1) return false;
        String withoutApprovedForeignKeyAction = normalized.replace(exactForeignKeyAction, "");
        return !withoutApprovedForeignKeyAction.matches("(?s).*\\b(alter|drop|delete|insert|replace|trigger)\\b.*");
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        for (int index = source.indexOf(token); index >= 0; index = source.indexOf(token, index + token.length())) count++;
        return count;
    }

    private static int countTables(JdbcTemplate locked) {
        Integer count = locked.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN (?,?,?,?)
                """, Integer.class, TABLES.toArray());
        return count == null ? 0 : count;
    }

    private static boolean versionThreeApplied(JdbcTemplate locked) {
        List<String> rows = locked.queryForList("""
                SELECT stage FROM chat_deliberation_schema_version
                WHERE version=3 LIMIT 2
                """, String.class);
        if (rows.isEmpty()) return false;
        if (rows.size() != 1 || !"APPLIED".equals(rows.getFirst())) throw drift("schema version 3");
        return true;
    }

    /** Called only while holding the shared schema lock on the same connection. */
    static boolean ensureActionCatalog(JdbcTemplate locked, boolean allowMigration) {
        // Validate the complete catalogue first. An unrelated drift must never cause any DDL.
        boolean previousKind = validateCatalog(locked, true);
        if (previousKind) {
            if (!allowMigration) return false;
            locked.execute("ALTER TABLE chat_typed_outcome DROP CHECK " + KIND_CHECK
                    + ", ADD CONSTRAINT " + KIND_CHECK + " CHECK (" + ACTION_KIND + ")");
            validateCatalog(locked);
        }
        return true;
    }

    static void validateCatalog(JdbcTemplate locked) { validateCatalog(locked, false); }

    private static boolean validateCatalog(JdbcTemplate locked, boolean allowPreviousKind) {
        for (String table : TABLES) {
            List<Map<String, Object>> metadata = locked.queryForList("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """, table);
            if (metadata.size() != 1
                    || !"innodb".equals(lower(metadata.getFirst(), "engine"))
                    || !"utf8mb4_0900_bin".equals(lower(metadata.getFirst(), "table_collation"))) {
                throw drift(table);
            }
            validateColumns(locked, table);
            validateIndexes(locked, table);
        }
        boolean previousKind = validateChecks(locked, allowPreviousKind);
        validateForeignKeys(locked);
        return previousKind;
    }

    private static void validateColumns(JdbcTemplate locked, String table) {
        List<Map<String, Object>> rows = locked.queryForList("""
                SELECT column_name,column_type,is_nullable,column_default,extra,collation_name
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position
                """, table);
        List<Column> actual = rows.stream().map(row -> new Column(
                lower(row, "column_name"), lower(row, "column_type"),
                text(row, "is_nullable"), text(row, "column_default"),
                lowerOrEmpty(row, "extra"), lower(row, "collation_name"))).toList();
        if (!COLUMNS.get(table).equals(actual)) throw drift(table + " columns");
    }

    static void validateIndexes(JdbcTemplate locked, String table) {
        record Parts(boolean unique, List<String> columns) { }
        Map<String, Parts> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : locked.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part,expression
                FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """, table)) {
            if (value(row, "sub_part") != null || value(row, "expression") != null) throw drift(table + " prefix/expression index");
            String name = text(row, "index_name");
            boolean unique = number(row, "non_unique") == 0;
            Parts parts = actual.computeIfAbsent(name, ignored -> new Parts(unique, new ArrayList<>()));
            if (parts.unique() != unique) throw drift(name);
            parts.columns().add(lower(row, "column_name"));
        }
        Map<String, Index> expected = INDEXES.get(table);
        if (!actual.keySet().equals(expected.keySet())) throw drift(table + " index set");
        for (Map.Entry<String, Index> entry : expected.entrySet()) {
            Parts found = actual.get(entry.getKey());
            if (found.unique() != entry.getValue().unique()
                    || !found.columns().equals(entry.getValue().columns())) throw drift(entry.getKey());
        }
    }

    static void validateChecks(JdbcTemplate locked) { validateChecks(locked, false); }

    static boolean validateChecks(JdbcTemplate locked, boolean allowPreviousKind) {
        List<Map<String, Object>> rows = locked.queryForList("""
                SELECT tc.constraint_name,tc.enforced,cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
                WHERE tc.constraint_schema=DATABASE() AND tc.table_name IN (?,?,?,?)
                  AND tc.constraint_type='CHECK'
                """, TABLES.toArray());
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "constraint_name");
            if (name == null || actual.put(name, canonicalCheck(text(row, "check_clause"))) != null
                    || !"YES".equalsIgnoreCase(text(row, "enforced"))) throw drift("checks");
        }
        if (!actual.keySet().equals(CHECKS.keySet())) throw drift("check set");
        boolean previousKind = false;
        for (Map.Entry<String, String> entry : CHECKS.entrySet()) {
            String wanted = normalizeAtomicParentheses(stripOuterParentheses(canonicalCheck(entry.getValue())));
            String found = normalizeAtomicParentheses(stripOuterParentheses(actual.get(entry.getKey())));
            if (!wanted.equals(found)) {
                if (allowPreviousKind && KIND_CHECK.equals(entry.getKey())
                        && normalizeAtomicParentheses(stripOuterParentheses(canonicalCheck(PREVIOUS_KIND))).equals(found))
                    previousKind = true;
                else throw drift(entry.getKey());
            }
        }
        return previousKind;
    }

    static void validateForeignKeys(JdbcTemplate locked) {
        List<Map<String, Object>> rows = locked.queryForList("""
                SELECT k.constraint_name,k.table_name,k.column_name,k.ordinal_position,
                       k.referenced_table_name,k.referenced_column_name,
                       r.update_rule,r.delete_rule
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
                  AND r.table_name=k.table_name
                WHERE k.constraint_schema=DATABASE() AND k.table_name IN (?,?,?,?)
                  AND k.referenced_table_name IS NOT NULL
                ORDER BY k.constraint_name,k.ordinal_position
                """, TABLES.toArray());
        record Parts(String table, String referencedTable, String updateRule, String deleteRule,
                List<String> columns, List<String> referencedColumns) { }
        Map<String, Parts> actual = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = text(row, "constraint_name");
            String table = lower(row, "table_name");
            String referenced = lower(row, "referenced_table_name");
            String update = text(row, "update_rule");
            String delete = text(row, "delete_rule");
            Parts parts = actual.computeIfAbsent(name, ignored -> new Parts(table, referenced, update, delete,
                    new ArrayList<>(), new ArrayList<>()));
            if (!Objects.equals(parts.table(), table) || !Objects.equals(parts.referencedTable(), referenced)
                    || !Objects.equals(parts.updateRule(), update) || !Objects.equals(parts.deleteRule(), delete)) {
                throw drift(name);
            }
            parts.columns().add(lower(row, "column_name"));
            parts.referencedColumns().add(lower(row, "referenced_column_name"));
        }
        if (!actual.keySet().equals(FOREIGN_KEYS.keySet())) throw drift("foreign key set");
        for (Map.Entry<String, ForeignKey> entry : FOREIGN_KEYS.entrySet()) {
            Parts found = actual.get(entry.getKey());
            ForeignKey expected = entry.getValue();
            if (!expected.table().equals(found.table())
                    || !expected.referencedTable().equals(found.referencedTable())
                    || !expected.columns().equals(found.columns())
                    || !expected.referencedColumns().equals(found.referencedColumns())
                    || !expected.updateRule().equalsIgnoreCase(found.updateRule())
                    || !expected.deleteRule().equalsIgnoreCase(found.deleteRule())) throw drift(entry.getKey());
        }
    }

    static String canonicalCheck(String source) {
        if (source == null) throw drift("null check");
        String rendered = normalizeQuotes(source);
        StringBuilder out = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < rendered.length(); i++) {
            char ch = rendered.charAt(i);
            if (ch == '\'') {
                out.append(ch);
                if (quote && i + 1 < rendered.length() && rendered.charAt(i + 1) == '\'') out.append(rendered.charAt(++i));
                else quote = !quote;
            } else if (quote) out.append(ch);
            else if (rendered.regionMatches(true, i, "_utf8mb4", 0, 8)
                    && nextNonWhitespaceIsQuote(rendered, i + 8)) i += 7;
            else if (!Character.isWhitespace(ch) && ch != '`') out.append(Character.toLowerCase(ch));
        }
        if (quote) throw drift("unterminated check");
        return out.toString();
    }

    private static boolean nextNonWhitespaceIsQuote(String value, int start) {
        int index=start;
        while(index<value.length()&&Character.isWhitespace(value.charAt(index)))index++;
        return index<value.length()&&value.charAt(index)=='\'';
    }

    private static String normalizeQuotes(String source) {
        int plain = 0, escaped = 0;
        for (int i = 0; i < source.length(); i++) if (source.charAt(i) == '\'') {
            int slashes = 0;
            for (int p = i - 1; p >= 0 && source.charAt(p) == '\\'; p--) slashes++;
            if (slashes == 0) plain++;
            else if (slashes == 1) escaped++;
            else throw drift("ambiguous check quotes");
        }
        if (plain > 0 && escaped > 0) throw drift("mixed check quotes");
        if (escaped == 0) return source;
        if ((escaped & 1) != 0) throw drift("unbalanced check quotes");
        return source.replace("\\'", "'");
    }

    private static String normalizeAtomicParentheses(String value) {
        String current=value;
        boolean changed;
        do {
            changed=false;
            int[] stack=new int[current.length()];int size=0;
            for(int i=0;i<current.length();i++){
                char ch=current.charAt(i);
                if(ch=='\''){while(i+1<current.length()&&current.charAt(i+1)!='\'')i++;continue;}
                if(ch=='(')stack[size++]=i;
                else if(ch==')'&&size>0){int open=stack[--size];
                    if(groupingParenthesis(current,open,i)&&!containsTopLevelBoolean(current,open+1,i)){
                        current=current.substring(0,open)+current.substring(open+1,i)+current.substring(i+1);
                        changed=true;break;
                    }
                }
            }
        }while(changed);
        return current;
    }

    private static boolean groupingParenthesis(String value,int open,int close){
        if(open==0)return true;char previous=value.charAt(open-1);
        return !(Character.isLetterOrDigit(previous)||previous=='_');
    }

    private static boolean containsTopLevelBoolean(String value,int start,int end){
        int depth=0;boolean quote=false;
        for(int i=start;i<end;i++){
            char ch=value.charAt(i);
            if(ch=='\''){if(quote&&i+1<end&&value.charAt(i+1)=='\'')i++;else quote=!quote;continue;}
            if(quote)continue;if(ch=='(')depth++;else if(ch==')')depth--;
            else if(depth==0&&(value.startsWith("and",i)||value.startsWith("or",i)))return true;
        }
        return false;
    }

    private static String stripOuterParentheses(String value) {
        String current = value;
        while (current.length() >= 2 && current.charAt(0) == '(' && current.charAt(current.length() - 1) == ')'
                && outerPairCoversWhole(current)) current = current.substring(1, current.length() - 1);
        return current;
    }

    private static boolean outerPairCoversWhole(String value) {
        int depth = 0;
        boolean quote = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\'') {
                if (quote && i + 1 < value.length() && value.charAt(i + 1) == '\'') i++;
                else quote = !quote;
            } else if (!quote && ch == '(') depth++;
            else if (!quote && ch == ')') {
                depth--;
                if (depth == 0 && i < value.length() - 1) return false;
                if (depth < 0) return false;
            }
        }
        return depth == 0 && !quote;
    }

    private static void acquire(JdbcTemplate locked) {
        Integer value = locked.queryForObject("SELECT GET_LOCK(?, -1)", Integer.class, LOCK);
        if (!Integer.valueOf(1).equals(value)) throw new IllegalStateException("Typed deliberation migration lock unavailable");
    }

    private static void release(JdbcTemplate locked) {
        Integer value = locked.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK);
        if (!Integer.valueOf(1).equals(value)) throw new IllegalStateException("Typed deliberation migration lock lost");
    }

    private static void requireMySql8(Connection connection) {
        try {
            var metadata = connection.getMetaData();
            if (metadata.getDatabaseProductName() == null
                    || !metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || metadata.getDatabaseMajorVersion() < 8) {
                throw new IllegalStateException("Typed deliberation requires MySQL 8");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Typed deliberation database unavailable", failure);
        }
    }

    private static Map<String, List<Column>> expectedColumns() {
        String b = "bigint", v50 = "varchar(50)", v64 = "varchar(64)", v100 = "varchar(100)";
        return Map.of(
                "chat_typed_outcome", List.of(
                        col("outcome_id",v64,false,null), col("tenant_id",v50,false,null), col("owner_jiacn",v50,false,null),
                        col("client_id",v50,false,null), col("conversation_id",v100,false,null), num("conversation_generation",b,false,null),
                        col("request_id",v100,false,null), num("request_revision",b,false,null), col("turn_id",v64,false,null),
                        col("task_id",v100,false,null), num("assignment_revision",b,false,null), num("assistant_message_id",b,false,null),
                        col("final_digest",v100,false,null), col("kind","varchar(30)",false,null), col("text","mediumtext",false,null),
                        col("binding_json","mediumtext",false,null), col("facts_json","mediumtext",false,null),
                        col("outcome_json","mediumtext",false,null), col("source_catalog_json","mediumtext",false,null), num("created_at",b,false,null)),
                "chat_typed_pending_question", List.of(
                        col("pending_question_id",v64,false,null), col("outcome_id",v64,false,null), col("tenant_id",v50,false,null),
                        col("owner_jiacn",v50,false,null), col("client_id",v50,false,null), col("conversation_id",v100,false,null),
                        num("conversation_generation",b,false,null), col("state","varchar(20)",false,null), num("state_version",b,false,"0"),
                        col("question","mediumtext",false,null), col("required_facts_json","text",false,null),
                        col("reply_request_id",v100,true,null), col("reply_idempotency_key",v100,true,null), col("reply_body_digest",v100,true,null),
                        num("created_at",b,false,null), num("updated_at",b,false,null)),
                "chat_typed_proposal", List.of(
                        col("proposal_id",v64,false,null), col("outcome_id",v64,false,null), col("tenant_id",v50,false,null),
                        col("owner_jiacn",v50,false,null), col("client_id",v50,false,null), col("conversation_id",v100,false,null),
                        num("conversation_generation",b,false,null), col("state","varchar(20)",false,null), num("state_version",b,false,"0"),
                        col("operation","varchar(30)",false,null), col("instruction","text",false,null),
                        col("source_ref_ids_json","text",false,null), col("source_selectors_json","mediumtext",false,null),
                        col("parent_request_id",v100,true,null), col("parent_step_id",v64,true,null), num("created_at",b,false,null)),
                "chat_typed_admission", List.of(
                        col("admission_id",v64,false,null), col("tenant_id",v50,false,null), col("owner_jiacn",v50,false,null),
                        col("client_id",v50,false,null), col("conversation_id",v100,false,null), num("conversation_generation",b,false,null),
                        col("idempotency_key",v100,false,null), col("request_digest",v100,false,null), col("body_digest",v100,false,null),
                        col("intent","varchar(30)",false,null), col("task_id",v100,false,null), num("assignment_revision",b,false,null),
                        col("parent_outcome_id",v64,true,null), col("pending_question_id",v64,true,null), col("request_id",v100,false,null),
                        num("request_revision",b,false,null), num("user_message_id",b,false,null), col("turn_ids_json","text",false,null),
                        col("source_catalog_json","mediumtext",false,null), col("state","varchar(30)",false,null),
                        num("state_version",b,false,null), num("event_cursor",b,false,null), num("created_at",b,false,null)));
    }

    private static Map<String, Map<String, Index>> expectedIndexes() {
        List<String> scope = List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation");
        return Map.of(
                "chat_typed_outcome", Map.of(
                        "PRIMARY", idx(true,"outcome_id"),
                        "uk_chat_typed_outcome_scope_id", idx(true,scope,"outcome_id"),
                        "uk_chat_typed_outcome_scope_turn", idx(true,scope,"turn_id"),
                        "uk_chat_typed_outcome_scope_request", idx(true,scope,"request_id","request_revision"),
                        "idx_chat_typed_outcome_message", idx(false,scope,"assistant_message_id"),
                        "idx_chat_typed_outcome_turn_fk", idx(false,"turn_id")),
                "chat_typed_pending_question", Map.of(
                        "PRIMARY", idx(true,"pending_question_id"),
                        "uk_chat_typed_pending_outcome", idx(true,scope,"outcome_id"),
                        "idx_chat_typed_pending_scope_state", idx(false,scope,"state","updated_at")),
                "chat_typed_proposal", Map.of(
                        "PRIMARY", idx(true,"proposal_id"),
                        "uk_chat_typed_proposal_outcome", idx(true,scope,"outcome_id"),
                        "idx_chat_typed_proposal_scope", idx(false,scope,"created_at")),
                "chat_typed_admission", Map.of(
                        "PRIMARY", idx(true,"admission_id"),
                        "uk_chat_typed_admission_scope_key", idx(true,scope,"idempotency_key"),
                        "uk_chat_typed_admission_scope_request", idx(true,scope,"request_id","request_revision"),
                        "idx_chat_typed_admission_parent", idx(false,scope,"parent_outcome_id"),
                        "idx_chat_typed_admission_request_fk", idx(false,"tenant_id","owner_jiacn","client_id","request_id","request_revision")));
    }

    private static Map<String, ForeignKey> expectedForeignKeys() {
        List<String> scopeOutcome = List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","outcome_id");
        return Map.of(
                "fk_chat_typed_outcome_turn", new ForeignKey("chat_typed_outcome",List.of("turn_id"),"chat_turn",List.of("turn_id"),"RESTRICT","RESTRICT"),
                "fk_chat_typed_pending_outcome", new ForeignKey("chat_typed_pending_question",scopeOutcome,"chat_typed_outcome",scopeOutcome,"RESTRICT","RESTRICT"),
                "fk_chat_typed_proposal_outcome", new ForeignKey("chat_typed_proposal",scopeOutcome,"chat_typed_outcome",scopeOutcome,"RESTRICT","RESTRICT"),
                "fk_chat_typed_admission_request", new ForeignKey("chat_typed_admission",List.of("tenant_id","owner_jiacn","client_id","request_id","request_revision"),
                        "chat_request",List.of("tenant_id","owner_jiacn","client_id","request_id","request_revision"),"RESTRICT","RESTRICT"));
    }

    private static Map<String, String> expectedChecks() {
        return Map.ofEntries(
                Map.entry("chk_chat_typed_outcome_generation","conversation_generation>=1"),
                Map.entry("chk_chat_typed_outcome_revision","((request_revision=1) AND (assignment_revision>=0))"),
                Map.entry("chk_chat_typed_outcome_digest","REGEXP_LIKE(final_digest,CAST('^sha256:[0-9a-f]{64}$' AS CHAR CHARSET binary))"),
                Map.entry(KIND_CHECK,ACTION_KIND),
                Map.entry("chk_chat_typed_outcome_json","(json_valid(binding_json) and json_valid(facts_json) and json_valid(outcome_json) and json_valid(source_catalog_json))"),
                Map.entry("chk_chat_typed_pending_state","state IN ('OPEN','ANSWERED')"),
                Map.entry("chk_chat_typed_pending_version","(((state = 'OPEN') and (state_version = 0) and (reply_request_id is null) and (reply_idempotency_key is null) and (reply_body_digest is null)) or ((state = 'ANSWERED') and (state_version = 1) and (reply_request_id is not null) and (reply_idempotency_key is not null) and regexp_like(reply_body_digest,cast('^sha256:[0-9a-f]{64}$' as char charset binary))))"),
                Map.entry("chk_chat_typed_pending_required","JSON_VALID(required_facts_json)"),
                Map.entry("chk_chat_typed_proposal_state","((state='PROPOSED') AND (state_version=0))"),
                Map.entry("chk_chat_typed_proposal_operation","operation IN ('GENERATE_IMAGE','EDIT_IMAGE')"),
                Map.entry("chk_chat_typed_proposal_sources","(json_valid(source_ref_ids_json) and json_valid(source_selectors_json))"),
                Map.entry("chk_chat_typed_proposal_parent","(((parent_request_id is null) and (parent_step_id is null)) or ((parent_request_id is not null) and (parent_step_id is not null)))"),
                Map.entry("chk_chat_typed_admission_generation","conversation_generation>=1"),
                Map.entry("chk_chat_typed_admission_digest","(regexp_like(request_digest,cast('^sha256:[0-9a-f]{64}$' as char charset binary)) and regexp_like(body_digest,cast('^sha256:[0-9a-f]{64}$' as char charset binary)))"),
                Map.entry("chk_chat_typed_admission_intent","intent IN ('DISCUSSION','CLARIFICATION_REPLY')"),
                Map.entry("chk_chat_typed_admission_revision","((request_revision=1) AND (assignment_revision>=0) AND (state_version>=0) AND (event_cursor>=0))"),
                Map.entry("chk_chat_typed_admission_turns","(json_valid(turn_ids_json) and json_valid(source_catalog_json))"),
                Map.entry("chk_chat_typed_admission_reply","(((intent = 'DISCUSSION') and (pending_question_id is null)) or ((intent = 'CLARIFICATION_REPLY') and (parent_outcome_id is not null) and (pending_question_id is not null)))"));
    }

    private static Column col(String name, String type, boolean nullable, String defaultValue) {
        return new Column(name,type,nullable?"YES":"NO",defaultValue,"","utf8mb4_0900_bin");
    }
    private static Column num(String name, String type, boolean nullable, String defaultValue) {
        return new Column(name,type,nullable?"YES":"NO",defaultValue,"",null);
    }
    private static Index idx(boolean unique, String... columns) { return new Index(unique,List.of(columns)); }
    private static Index idx(boolean unique, List<String> prefix, String... tail) {
        List<String> columns=new ArrayList<>(prefix);columns.addAll(List.of(tail));return new Index(unique,List.copyOf(columns));
    }
    private static Object value(Map<String,Object> row,String key) {
        return row.entrySet().stream().filter(entry->entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }
    private static String text(Map<String,Object> row,String key) { Object value=value(row,key);return value==null?null:String.valueOf(value); }
    private static String lower(Map<String,Object> row,String key) { String value=text(row,key);return value==null?null:value.toLowerCase(Locale.ROOT); }
    private static String lowerOrEmpty(Map<String,Object> row,String key) { String value=lower(row,key);return value==null?"":value; }
    private static long number(Map<String,Object> row,String key) { Object value=value(row,key);return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value)); }
    private static IllegalStateException drift(String part) { return new IllegalStateException("Typed deliberation schema drift: "+part); }

    private record Column(String name,String type,String nullable,String defaultValue,String extra,String collation) { }
    private record Index(boolean unique,List<String> columns) { }
    private record ForeignKey(String table,List<String> columns,String referencedTable,List<String> referencedColumns,
            String updateRule,String deleteRule) { }
}
