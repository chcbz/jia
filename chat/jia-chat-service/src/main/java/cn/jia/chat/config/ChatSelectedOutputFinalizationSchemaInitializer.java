package cn.jia.chat.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Default-off additive schema with an explicit core/deliberation readiness boundary. */
@Component
@ConditionalOnProperty(prefix="chat.selected-output-finalization",name="enabled",havingValue="true")
public final class ChatSelectedOutputFinalizationSchemaInitializer implements InitializingBean {
    static final String RESOURCE="db/chat-selected-output-finalization-v1.sql";
    static final List<String> TABLES=List.of("chat_selected_output_finalization","chat_selected_output_finalization_item");
    private static final Map<String,Set<String>> SOURCE_COLUMNS=Map.of(
            "chat_conversation_asset",Set.of("tenant_id","owner_jiacn","client_id","conversation_id",
                    "conversation_generation","request_id","step_id","execution_id","run_id","output_id",
                    "content_mime_type","sha256","byte_length"),
            "chat_conversation",Set.of("id","tenant_id","jiacn","client_id","deleted_at","lifecycle_generation"),
            "agent_task_meta",Set.of("tenant_id","client_id","owner_jiacn","task_id","reward_status","task_version"));
    private static final Map<String,Set<String>> TABLE_COLUMNS=Map.of(
            "chat_selected_output_finalization",Set.of("operation_id","tenant_id","owner_jiacn","client_id",
                    "task_id","idempotency_key","request_digest","conversation_id","expected_task_version",
                    "expected_assignment_revision","summary","state","stage","state_version","delivery_id",
                    "delivery_state","task_state","task_version","error_code","retryable","created_at","updated_at"),
            "chat_selected_output_finalization_item",Set.of("tenant_id","owner_jiacn","client_id","operation_id",
                    "item_order","request_id","step_id","output_id","sha256","title","purpose"));
    private static final Map<String,Map<String,Index>> INDEXES=Map.of(
            "chat_selected_output_finalization",Map.of(
                    "PRIMARY",new Index(true,List.of("tenant_id","owner_jiacn","client_id","operation_id")),
                    "uk_csof_request",new Index(true,List.of("tenant_id","owner_jiacn","client_id","task_id","idempotency_key")),
                    "idx_csof_task",new Index(false,List.of("tenant_id","owner_jiacn","client_id","task_id","updated_at"))),
            "chat_selected_output_finalization_item",Map.of(
                    "PRIMARY",new Index(true,List.of("tenant_id","owner_jiacn","client_id","operation_id","item_order")),
                    "uk_csofi_source",new Index(true,List.of("tenant_id","owner_jiacn","client_id","operation_id",
                            "request_id","step_id","output_id"))));
    private static final Map<String,Set<String>> CHECKS=Map.of(
            "chat_selected_output_finalization",Set.of("chk_csof_versions","chk_csof_key","chk_csof_delivery_state",
                    "chk_csof_state","chk_csof_stage","chk_csof_progress","chk_csof_outcome","chk_csof_terminal"),
            "chat_selected_output_finalization_item",Set.of("chk_csofi_order","chk_csofi_hash"));
    private static final Map<String,Set<String>> ASCII_COLUMNS=Map.of(
            "chat_selected_output_finalization",Set.of("request_digest"),
            "chat_selected_output_finalization_item",Set.of("sha256"));
    private static final Map<String,Set<String>> BINARY_COLUMNS=Map.of(
            "chat_selected_output_finalization",Set.of("operation_id","tenant_id","owner_jiacn","client_id","task_id",
                    "idempotency_key","conversation_id","state","stage","delivery_id","delivery_state","task_state","error_code"),
            "chat_selected_output_finalization_item",Set.of("tenant_id","owner_jiacn","client_id","operation_id",
                    "request_id","step_id","output_id","title","purpose"));

    // Evidence: actual MySQL8.0.21 old/repaired finalization checks admitted/rejected NULL
    // differently while sharing constraint names and ENFORCED=YES. Compare only these
    // three proven-sensitive definitions; preserve boolean grouping and binary literals.
    private static final Map<String,String> REQUIRED_CHECK_EXPRESSIONS=Map.of(
            "chk_csof_progress", "(((stagein('PROMOTING','READY_TO_SUBMIT'))and(delivery_idisnull)and(delivery_stateisnull))or((stage='SUBMITTED')and(delivery_idisnotnull)and(delivery_stateisnotnull)and(delivery_statein('submitted','changes_requested')))or((stage='ACCEPTING')and(delivery_idisnotnull)and(delivery_stateisnotnull)and(delivery_statein('submitted','changes_requested')))or((stage='TASK_COMPLETED')and(delivery_idisnotnull)and(delivery_stateisnotnull)and(delivery_state='accepted')))",
            "chk_csof_terminal", "(((state='completed')and(stage='TASK_COMPLETED')and(delivery_idisnotnull)and(delivery_stateisnotnull)and(delivery_state='accepted')and(task_state='completed')and(error_codeisnull)and(retryable=0))or((state<>'completed')and(stage<>'TASK_COMPLETED')))");
    private final JdbcTemplate jdbc;
    private final ChatSchemaReadiness schemaReadiness;

    public ChatSelectedOutputFinalizationSchemaInitializer(JdbcTemplate jdbc,
            ChatSchemaReadiness schemaReadiness) {
        this.jdbc=Objects.requireNonNull(jdbc);this.schemaReadiness=Objects.requireNonNull(schemaReadiness);
    }

    @Override public void afterPropertiesSet() throws Exception {
        schemaReadiness.ensureInitialized();
        validateSources();
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            String product=connection.getMetaData().getDatabaseProductName();
            if(product==null||!product.toLowerCase(Locale.ROOT).contains("mysql")
                    ||connection.getMetaData().getDatabaseMajorVersion()<8)
                throw new IllegalStateException("Finalization schema requires MySQL 8");
            JdbcTemplate locked=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            Integer acquired=locked.queryForObject("SELECT GET_LOCK('cyf:chat:selected-output-finalization:v1',-1)",Integer.class);
            if(!Integer.valueOf(1).equals(acquired))throw new IllegalStateException("Finalization schema lock unavailable");
            try {for(String statement:ddl())locked.execute(statement);validate(locked);}
            finally {
                Integer released=locked.queryForObject("SELECT RELEASE_LOCK('cyf:chat:selected-output-finalization:v1')",Integer.class);
                if(!Integer.valueOf(1).equals(released))throw new IllegalStateException("Finalization schema lock lost");
            }
            return null;
        });
    }

    private void validateSources() {
        for(var required:SOURCE_COLUMNS.entrySet()) {
            Set<String> actual=Set.copyOf(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=?
                    """,String.class,required.getKey()).stream().map(String::toLowerCase).toList());
            if(!actual.containsAll(required.getValue()))throw new IllegalStateException(
                    "Finalization source schema unavailable: "+required.getKey()+" missing="+difference(required.getValue(),actual));
        }
    }

    private static void validate(JdbcTemplate jdbc) {
        for(String table:TABLES) {
            List<Map<String,Object>> metadata=jdbc.queryForList("""
                    SELECT engine,table_collation FROM information_schema.tables
                    WHERE table_schema=DATABASE() AND table_name=?
                    """,table);
            if(metadata.size()!=1||!"innodb".equals(lower(metadata.getFirst(),"engine"))
                    ||!"utf8mb4_0900_bin".equals(lower(metadata.getFirst(),"table_collation")))
                throw new IllegalStateException("Finalization table drift: "+table);
            Set<String> columns=Set.copyOf(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema=DATABASE() AND table_name=?
                    """,String.class,table).stream().map(String::toLowerCase).toList());
            if(!columns.containsAll(TABLE_COLUMNS.get(table)))throw new IllegalStateException(
                    "Finalization columns missing: "+table+" "+difference(TABLE_COLUMNS.get(table),columns));
            validateIndexes(jdbc,table);validateChecks(jdbc,table);validateBinary(jdbc,table);
        }
    }

    private static void validateIndexes(JdbcTemplate jdbc,String table) {
        record Parts(boolean unique,List<String> columns) { }
        Map<String,Parts> actual=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """,table)) {
            if(value(row,"sub_part")!=null)throw new IllegalStateException("Finalization prefix index drift: "+table);
            String name=text(row,"index_name");boolean unique=number(row,"non_unique")==0;
            Parts parts=actual.computeIfAbsent(name,ignored->new Parts(unique,new ArrayList<>()));
            if(parts.unique()!=unique)throw new IllegalStateException("Finalization index drift: "+name);
            parts.columns().add(text(row,"column_name").toLowerCase(Locale.ROOT));
        }
        for(var expected:INDEXES.get(table).entrySet()) {
            Parts found=actual.get(expected.getKey());
            if(found==null||found.unique()!=expected.getValue().unique()
                    ||!found.columns().equals(expected.getValue().columns()))
                throw new IllegalStateException("Finalization index drift: "+expected.getKey());
        }
    }

    private static void validateChecks(JdbcTemplate jdbc,String table) {
        Map<String,String> actual=new LinkedHashMap<>();
        Map<String,String> clauses=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT t.constraint_name,t.enforced,c.check_clause
                FROM information_schema.table_constraints t
                JOIN information_schema.check_constraints c
                  ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
                WHERE t.constraint_schema=DATABASE() AND t.table_name=? AND t.constraint_type='CHECK'
                """,table)){
            String name=text(row,"constraint_name");
            actual.put(name,text(row,"enforced"));clauses.put(name,text(row,"check_clause"));
        }
        for(String check:CHECKS.get(table))if(!"YES".equalsIgnoreCase(actual.get(check)))
            throw new IllegalStateException("Finalization check drift: "+check);
        for(var required:REQUIRED_CHECK_EXPRESSIONS.entrySet())if(CHECKS.get(table).contains(required.getKey()))
            if(!required.getValue().equals(compactCheckExpression(clauses.get(required.getKey()))))
                throw new IllegalStateException("Finalization check definition drift: "+required.getKey());
    }

    private static String compactCheckExpression(String source){
        if(source==null)return null;
        String value=source.replace("\\'","'");StringBuilder result=new StringBuilder();boolean quoted=false;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='\''){quoted=!quoted;result.append(c);}
            else if(quoted)result.append(c);
            else if(value.regionMatches(true,i,"_utf8mb4",0,8))i+=7;
            else if(!Character.isWhitespace(c)&&c!='`')result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }

    private static void validateBinary(JdbcTemplate jdbc,String table) {
        Map<String,String> actual=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT column_name,collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND collation_name IS NOT NULL
                """,table))actual.put(text(row,"column_name").toLowerCase(Locale.ROOT),text(row,"collation_name"));
        for(String column:BINARY_COLUMNS.get(table))if(!"utf8mb4_0900_bin".equalsIgnoreCase(actual.get(column)))
            throw new IllegalStateException("Finalization binary scope drift: "+table+"."+column);
        for(String column:ASCII_COLUMNS.get(table))if(!"ascii_bin".equalsIgnoreCase(actual.get(column)))
            throw new IllegalStateException("Finalization digest collation drift: "+table+"."+column);
    }

    static List<String> ddl() {
        try {
            String source=new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
            String clean=source.lines().filter(v->!v.stripLeading().startsWith("--")).reduce("",(a,b)->a+b+'\n');
            List<String> statements=new ArrayList<>();
            for(String part:clean.split(";"))if(!part.isBlank())statements.add(part.strip());
            if(statements.size()!=2)throw new IllegalStateException("Finalization DDL statement count");
            for(int i=0;i<2;i++) {
                String normalized=statements.get(i).toLowerCase(Locale.ROOT);
                if(!normalized.startsWith("create table if not exists "+TABLES.get(i)+" ")
                        ||normalized.matches("(?s).*\\b(alter|insert|update|delete|drop|trigger)\\b.*"))
                    throw new IllegalStateException("Unsafe finalization DDL");
            }
            return List.copyOf(statements);
        } catch(Exception failure) {throw new IllegalStateException("Finalization DDL unavailable",failure);}
    }

    private static Set<String> difference(Set<String> expected,Set<String> actual) {
        var missing=new java.util.LinkedHashSet<>(expected);missing.removeAll(actual);return Set.copyOf(missing);
    }
    private static Object value(Map<String,Object> row,String key) {
        return row.entrySet().stream().filter(entry->entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }
    private static String text(Map<String,Object> row,String key) {
        Object value=value(row,key);return value==null?null:String.valueOf(value);
    }
    private static String lower(Map<String,Object> row,String key) {
        String value=text(row,key);return value==null?null:value.toLowerCase(Locale.ROOT);
    }
    private static long number(Map<String,Object> row,String key) {
        Object value=value(row,key);return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));
    }
    private record Index(boolean unique,List<String> columns) { }
}
