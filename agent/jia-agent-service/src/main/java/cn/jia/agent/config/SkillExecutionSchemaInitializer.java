package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Read-only latest-contract detector. Explicit operator installation owns all DDL. */
@Component
@ConditionalOnProperty(prefix="agent.personal-workspace-storage",name="enabled",havingValue="true")
@DependsOn("personalWorkspaceExecutionSchemaInitializer")
public final class SkillExecutionSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    public SkillExecutionSchemaInitializer(JdbcTemplate jdbc) {this.jdbc=Objects.requireNonNull(jdbc);}
    private record Column(String type,String nullable,String defaultValue,String collation,String extra) { }
    static final String MIME_CATALOG_CHECK="(((execution_protocol_version is not null) and (execution_protocol_version = 4)) or ((output_content_mime_type is not null) and (output_content_mime_type in ("+PersonalWorkspaceExecutionSchemaInitializer.OUTPUT_MIMES+"))))";
    static final String STATE_CATALOG_CHECK="((execution_state in ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')) or ((execution_state = 'RESULT_COMMITTED') and (execution_protocol_version is not null) and (execution_protocol_version = 4)))";
    static String protocolCatalogCheck() {
        String legacy=ControlledImageFollowupV3SchemaInitializer.EXECUTION_PROTOCOL_CATALOG_CHECK;
        return legacy.substring(0,legacy.length()-1)+" or ((execution_protocol_version = 4) and (execution_mode = 'CONVERSATION') and (permitted_operation = 'EXECUTE_SKILL') and (controlled_consent_id is null) and (operation_grant_id is null) and (runtime_input_snapshot_digest is not null) and regexp_like(runtime_input_snapshot_digest,cast('^[0-9a-f]{64}$' as char charset binary))))";
    }
    @Override public void afterPropertiesSet() {
        try(var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata=connection.getMetaData();if(!metadata.getDatabaseProductName().equalsIgnoreCase("MySQL")||metadata.getDatabaseMajorVersion()<8)throw new IllegalStateException("Skill schema requires MySQL8");
        }catch(java.sql.SQLException failure){throw new IllegalStateException("Skill schema discovery unavailable",failure);}
        var tables=jdbc.queryForList("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='agent_skill_execution'");
        if(tables.size()!=1||!"InnoDB".equals(text(tables.getFirst(),"engine"))||!"utf8mb4_0900_bin".equals(text(tables.getFirst(),"table_collation")))throw new IllegalStateException("Explicit skill schema installation missing or drifted");
        validateColumns();validateIndexes();
        validateChecks("agent_skill_execution",attachmentChecks(),true);
        validateChecks("agent_personal_workspace_execution",Map.of("chk_pwex_output_mime",MIME_CATALOG_CHECK,"chk_pwex_state",STATE_CATALOG_CHECK,"chk_pwex_execution_protocol",protocolCatalogCheck()),false);
        var mime=jdbc.queryForList("SELECT column_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_personal_workspace_execution' AND column_name='output_content_mime_type'");
        if(mime.size()!=1||!"varchar(127)".equals(text(mime.getFirst(),"column_type"))||!"YES".equals(text(mime.getFirst(),"is_nullable"))||value(mime.getFirst(),"column_default")!=null)throw new IllegalStateException("Skill output MIME column drift");
        Integer missing=jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution e LEFT JOIN agent_skill_execution s ON BINARY s.tenant_id=BINARY e.tenant_id AND BINARY s.client_id=BINARY e.client_id AND BINARY s.owner_jiacn=BINARY e.owner_jiacn AND BINARY s.execution_id=BINARY e.execution_id WHERE e.execution_protocol_version=4 AND s.id IS NULL",Integer.class);
        Integer orphan=jdbc.queryForObject("SELECT COUNT(*) FROM agent_skill_execution s LEFT JOIN agent_personal_workspace_execution e ON BINARY s.tenant_id=BINARY e.tenant_id AND BINARY s.client_id=BINARY e.client_id AND BINARY s.owner_jiacn=BINARY e.owner_jiacn AND BINARY s.execution_id=BINARY e.execution_id WHERE e.id IS NULL OR e.execution_protocol_version<>4 OR (s.phase IN ('ARCHIVING','COMPLETE') AND e.execution_state<>'RESULT_COMMITTED') OR (s.phase='FAILED' AND e.execution_state<>'FAILED') OR (s.phase='CANCELLED' AND e.execution_state<>'INPUTS_REVOKED')",Integer.class);
        if(!Objects.equals(missing,0)||!Objects.equals(orphan,0))throw new IllegalStateException("Skill execution paired catalog integrity drift");
    }
    private void validateColumns() {
        Map<String,Column> expected=new LinkedHashMap<>();
        expected.put("id",new Column("bigint","NO",null,null,"auto_increment"));
        for(String name:List.of("tenant_id","client_id","owner_jiacn"))expected.put(name,new Column("varchar(50)","NO",null,"utf8mb4_0900_bin",""));
        expected.put("execution_id",new Column("varchar(100)","NO",null,"utf8mb4_0900_bin",""));
        for(String name:List.of("skill_key","skill_version"))expected.put(name,new Column("varchar(64)","NO",null,"utf8mb4_0900_bin",""));
        for(String name:List.of("package_sha256","manifest_sha256","snapshot_sha256"))expected.put(name,new Column("char(64)","NO",null,"ascii_bin",""));
        for(String name:List.of("snapshot_json","effects_json"))expected.put(name,new Column("json","NO",null,null,""));
        expected.put("result_manifest_json",new Column("json","YES",null,null,""));
        expected.put("last_error_code",new Column("varchar(64)","YES",null,"utf8mb4_0900_bin",""));
        expected.put("phase",new Column("varchar(24)","NO",null,"utf8mb4_0900_bin",""));
        expected.put("version",new Column("bigint","NO","0",null,""));
        for(String name:List.of("created_at","updated_at"))expected.put(name,new Column("bigint","NO",null,null,""));
        var rows=jdbc.queryForList("SELECT column_name,column_type,is_nullable,column_default,collation_name,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agent_skill_execution'");
        if(rows.size()!=expected.size())throw new IllegalStateException("Skill columns partial/unknown");
        var seen=new HashSet<String>();for(var row:rows){String name=text(row,"column_name");var wanted=expected.get(name);if(!seen.add(name)||wanted==null||!wanted.equals(new Column(text(row,"column_type"),text(row,"is_nullable"),text(row,"column_default"),text(row,"collation_name"),text(row,"extra"))))throw new IllegalStateException("Skill column drift: "+name);}
    }
    private void validateIndexes() {
        var rows=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part,index_type FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='agent_skill_execution' ORDER BY index_name,seq_in_index");
        var expected=Map.of("PRIMARY",List.of("id"),"uk_skill_execution_scope",List.of("tenant_id","client_id","owner_jiacn","execution_id"));
        if(rows.size()!=5)throw new IllegalStateException("Skill index partial/unknown");
        for(var entry:expected.entrySet()) {var found=rows.stream().filter(r->entry.getKey().equals(text(r,"index_name"))).toList();if(found.size()!=entry.getValue().size())throw new IllegalStateException("Skill index drift");for(int i=0;i<found.size();i++){var row=found.get(i);if(!"0".equals(text(row,"non_unique"))||!Integer.toString(i+1).equals(text(row,"seq_in_index"))||!entry.getValue().get(i).equals(text(row,"column_name"))||value(row,"sub_part")!=null||!"BTREE".equals(text(row,"index_type")))throw new IllegalStateException("Skill index drift");}}
    }
    static Map<String,String> attachmentChecks() {return Map.of(
        "chk_skill_execution_scope","((tenant_id = '0') and (owner_jiacn <> '0'))",
        "chk_skill_execution_version","(version >= 0)",
        "chk_skill_execution_hashes","(regexp_like(package_sha256,'^[0-9a-f]{64}$') and regexp_like(manifest_sha256,'^[0-9a-f]{64}$') and regexp_like(snapshot_sha256,'^[0-9a-f]{64}$'))",
        "chk_skill_execution_json","((json_type(snapshot_json) = 'OBJECT') and (json_type(effects_json) = 'OBJECT') and ((result_manifest_json is null) or (json_type(result_manifest_json) = 'OBJECT')))",
        "chk_skill_execution_phase","(phase in ('READY','RUNNING','WAITING_PROVIDER','RESULT_UNKNOWN','RESULT_READY','ARCHIVING','COMPLETE','FAILED','CANCELLED'))",
        "chk_skill_execution_result","((phase not in ('RESULT_READY','ARCHIVING','COMPLETE')) or (result_manifest_json is not null))");}
    private void validateChecks(String table,Map<String,String> expected,boolean exact) {
        var rows=jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name AND cc.constraint_catalog=tc.constraint_catalog WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'",table);
        if(exact&&rows.size()!=expected.size())throw new IllegalStateException("Skill CHECK partial/unknown");
        for(var entry:expected.entrySet()){var found=rows.stream().filter(r->entry.getKey().equals(text(r,"constraint_name"))).toList();if(found.size()!=1||!"YES".equals(text(found.getFirst(),"enforced"))||!canonical(entry.getValue()).equals(canonical(text(found.getFirst(),"check_clause"))))throw new IllegalStateException("Skill CHECK drift: "+table+"."+entry.getKey());}
    }
    private static String canonical(String value) {return ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(value);}
    private static Object value(Map<String,Object> row,String key){return row.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).filter(Objects::nonNull).findFirst().orElse(null);}
    private static String text(Map<String,Object> row,String key){return Objects.toString(value(row,key),null);}
}
