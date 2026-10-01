package cn.jia.agent.config;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
/** Additive controlled authority identity on existing conversation executions. */
public final class ControlledImageExecutionSchemaInitializer implements InitializingBean {
    /** Exact MySQL 8.0.21 CHECK_CLAUSE rendering captured from the additive v1_17 DDL. */
    private static final String EXPECTED_CHECK="((`controlled_consent_id` is null) or ((`execution_mode` = _utf8mb4\\'CONVERSATION\\') and regexp_like(`controlled_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (`permitted_operation` = _utf8mb4\\'GENERATE_IMAGE\\') and (`output_content_mime_type` = _utf8mb4\\'image/png\\')))";
    private final JdbcTemplate jdbc;
    public ControlledImageExecutionSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        Integer table=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution'",Integer.class);
        if(!Objects.equals(table,1))throw new IllegalStateException("Conversation execution schema unavailable");
        Integer column=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' AND column_name='controlled_consent_id'",Integer.class);
        if(Objects.equals(column,0))jdbc.execute(ddl());else if(!Objects.equals(column,1))throw new IllegalStateException("Controlled execution schema partial");
        var definition=jdbc.queryForMap("SELECT column_type,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' AND column_name='controlled_consent_id'");
        if(!"varchar(100)".equalsIgnoreCase(Objects.toString(definition.get("column_type"),""))
                ||!"YES".equalsIgnoreCase(Objects.toString(definition.get("is_nullable"),""))
                ||!"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(definition.get("collation_name"),"")))
            throw new IllegalStateException("Controlled execution column drift");
        var indexes=jdbc.queryForList("SELECT non_unique,seq_in_index,column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' AND index_name='uk_pwex_controlled_consent' ORDER BY seq_in_index");
        List<String> expected=List.of("tenant_id","client_id","owner_jiacn","controlled_consent_id");
        if(indexes.size()!=expected.size())throw new IllegalStateException("Controlled execution unique index drift");
        for(int i=0;i<expected.size();i++){var row=indexes.get(i);if(!expected.get(i).equals(row.get("column_name"))
                ||((Number)row.get("non_unique")).intValue()!=0||((Number)row.get("seq_in_index")).intValue()!=i+1
                ||row.get("sub_part")!=null)throw new IllegalStateException("Controlled execution unique index drift");}
        validateCheck(jdbc.queryForList("SELECT tc.enforced,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name='agent_personal_workspace_execution' AND tc.constraint_name='chk_pwex_controlled_consent' AND tc.constraint_type='CHECK'"));
    }
    static void validateCheck(List<java.util.Map<String,Object>> rows){
        if(rows.size()!=1||!"YES".equalsIgnoreCase(Objects.toString(rows.getFirst().get("enforced"),""))
                ||!canonicalCheck(EXPECTED_CHECK).equals(canonicalCheck(Objects.toString(
                            rows.getFirst().get("check_clause"),""))))
            throw new IllegalStateException("Controlled execution CHECK drift");
    }
    private static String canonicalCheck(String value) {
        try { return AgentTaskCreationOperationSchemaInitializer.canonicalCheckExpression(value); }
        catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Controlled execution malformed CHECK catalog",malformed);
        }
    }
    static String ddl(){try{String s=new ClassPathResource("db/agent-personal-workspace-v1_17-controlled-image.sql").getContentAsString(StandardCharsets.UTF_8).trim();String n=s.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");if(!n.startsWith("alter table agent_personal_workspace_execution ")||!n.contains("add column controlled_consent_id")||!n.contains("add unique key uk_pwex_controlled_consent")||!n.contains("add constraint chk_pwex_controlled_consent check")||n.contains(" insert ")||n.contains(" update ")||n.contains(" delete ")||n.contains(" drop ")||!s.endsWith(";")||s.substring(0,s.length()-1).contains(";"))throw new IllegalStateException("Unsafe controlled execution DDL");return s.substring(0,s.length()-1);}catch(Exception e){throw new IllegalStateException("Invalid controlled execution DDL",e);}}
}
