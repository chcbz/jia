package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentRuntimeV2EffectMapper;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.agent.skill.SkillInstallResultService;
import cn.jia.agent.security.AgentRuntimeV2ExecutionAuthority;
import cn.jia.agent.security.AgentRuntimeV2RecoveryAuthority;
import cn.jia.agent.security.AgentRuntimeV2RegistrationKeyRing;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import static cn.jia.agent.dao.AgentRuntimeV2EffectStore.*;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.Catalog;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.ColumnDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.IndexDefinition;
import cn.jia.agent.config.AgentRuntimeV2RegistrationSchemaGate.JdbcCatalog;

/** Explicitly dormant R4 assembly. Catalog verification is NOT isolation,
 * complete durable graph validation, or an executable readiness capability.
 * No migration/DDL or readiness provider is installed here.
 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="agent.runtime-v2-effects",name="enabled",havingValue="true")
public class AgentRuntimeV2EffectConfiguration {
    @Bean CatalogGate agentRuntimeV2EffectCatalogGate(JdbcTemplate jdbc,AgentRuntimeV2ExecutionSchemaGate baseline){return new CatalogGate(jdbc,new JdbcCatalog(jdbc),baseline::afterPropertiesSet);}
    @Bean AgentRuntimeV2RecoveryAuthority agentRuntimeV2RecoveryAuthority(AgentRuntimeV2RegistrationStore store,AgentRuntimeV2RegistrationKeyRing keys){return new AgentRuntimeV2RecoveryAuthority(store,keys,Clock.systemUTC());}
    @Bean AgentRuntimeV2EffectStore agentRuntimeV2EffectStore(JdbcTemplate jdbc,CatalogGate catalog){require(catalog.catalogVerified());return new AgentRuntimeV2EffectMapper(jdbc);}
    @Bean AgentRuntimeV2HistoricalSettlementEvidenceValidator agentRuntimeV2HistoricalSettlementEvidenceValidator(AgentRuntimeV2EffectStore store){return new AgentRuntimeV2HistoricalSettlementEvidenceValidator(store);}
    @Bean GraphGate agentRuntimeV2EffectGraphGate(AgentRuntimeV2ExecutionStore execution,AgentRuntimeV2ExecutionEvidenceValidator validator,AgentRuntimeV2HistoricalSettlementEvidenceValidator effects,CatalogGate catalog){return new GraphGate(execution,validator,effects,catalog);}
    @Bean AgentRuntimeV2EffectService agentRuntimeV2EffectService(AgentRuntimeV2ExecutionStore execution,AgentRuntimeV2EffectStore effects,AgentRuntimeV2ExecutionAuthority authority,AgentRuntimeV2RecoveryAuthority recovery,AgentRuntimeV2ExecutionEvidenceValidator validator,AgentRuntimeV2HistoricalSettlementEvidenceValidator historical,PersonalWorkspaceExecutionService workspace,SkillInstallResultService skills,PlatformTransactionManager manager,GraphGate graphs){
        require(graphs.graphVerified());return new AgentRuntimeV2EffectServiceImpl(execution,effects,authority,recovery,validator,historical,workspace,skills,manager,Clock.systemUTC());
    }

    /** Exact read-only metadata seam, distinct from the durable graph gate.
     * Neither gate supplies executable readiness or isolation authority.
     */
    public static final class CatalogGate implements InitializingBean {
        private final JdbcTemplate jdbc;
        private final Catalog catalog;
        private final Runnable baseline;
        private volatile boolean catalogVerified;
        CatalogGate(JdbcTemplate jdbc,Catalog catalog,Runnable baseline){this.jdbc=Objects.requireNonNull(jdbc);this.catalog=Objects.requireNonNull(catalog);this.baseline=Objects.requireNonNull(baseline);}
        public boolean catalogVerified(){return catalogVerified;}
        @Override public void afterPropertiesSet(){
            catalogVerified=false;
            baseline.run();
            String product=catalog.databaseProduct();require(product!=null&&product.toLowerCase(Locale.ROOT).contains("mysql"));
            for(var entry:expected().entrySet()){
                String table=entry.getKey();Definition contract=entry.getValue();
                var actual=catalog.table(table);
                require(actual!=null&&"InnoDB".equalsIgnoreCase(actual.engine())&&"utf8mb4_bin".equals(actual.collation()));
                require(catalog.participatingForeignKeyCount(table)==0);
                require(contract.columns().equals(catalog.columns(table)));
                require(contract.indexes().equals(catalog.indexes(table)));
                var checks=catalog.checks(table);require(checks.keySet().equals(contract.checks().keySet()));
                contract.checks().forEach((name,clause)->{
                    var check=checks.get(name);require(check!=null&&check.enforced()&&AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(clause).equals(AgentRuntimeV2PersistenceSchemaGate.canonicalCheck(check.clause())));
                });
                Long triggers=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?",Long.class,table);
                require(triggers!=null&&triggers==0L);
            }
            catalogVerified=true;
        }
    }
    /** Startup validates every actual original execution and associated R4
     * graph. Runtime admission still acquires its own current ACL/graph locks;
     * this is not a cached permission or a replacement for beforeCommit.
     */
    public static final class GraphGate implements InitializingBean {
        private final AgentRuntimeV2ExecutionStore execution;
        private final AgentRuntimeV2ExecutionEvidenceValidator validator;
        private final AgentRuntimeV2HistoricalSettlementEvidenceValidator effects;
        private final CatalogGate catalog;
        private volatile boolean graphVerified;
        GraphGate(AgentRuntimeV2ExecutionStore execution,AgentRuntimeV2ExecutionEvidenceValidator validator,AgentRuntimeV2HistoricalSettlementEvidenceValidator effects,CatalogGate catalog){this.execution=Objects.requireNonNull(execution);this.validator=Objects.requireNonNull(validator);this.effects=Objects.requireNonNull(effects);this.catalog=Objects.requireNonNull(catalog);}
        public boolean graphVerified(){return graphVerified;}
        @Override public void afterPropertiesSet(){
            graphVerified=false;require(catalog.catalogVerified());
            // Iterating only known originals would silently omit orphan R4
            // rows. Every persisted row must belong to a real original graph;
            // BLOB inherits its dispatch only through the exact effect root.
            for(String table:List.of(RUN,EFFECT,REQUEST,EVENT)){
                Long missing=execution.jdbc().queryForObject("SELECT COUNT(*) FROM "+table+" r LEFT JOIN agent_runtime_v2_internal_outbox i ON i.runtime_dispatch_id=r.runtime_dispatch_id WHERE i.runtime_dispatch_id IS NULL",Long.class);
                require(missing!=null&&missing==0L);
            }
            Long missingBlob=execution.jdbc().queryForObject("SELECT COUNT(*) FROM "+BLOB+" b LEFT JOIN "+EFFECT+" e ON e.effect_id=b.effect_id LEFT JOIN agent_runtime_v2_internal_outbox i ON i.runtime_dispatch_id=e.runtime_dispatch_id WHERE e.effect_id IS NULL OR i.runtime_dispatch_id IS NULL",Long.class);
            require(missingBlob!=null&&missingBlob==0L);
            for(var id:execution.jdbc().queryForList("SELECT runtime_dispatch_id FROM agent_runtime_v2_internal_outbox ORDER BY runtime_dispatch_id",String.class)){
                var origin=new AgentRuntimeV2InternalOutboxRow(required(execution.hint(AgentRuntimeV2ExecutionStore.INTERNAL,cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("runtime_dispatch_id",id))));
                var rows=new AgentRuntimeV2DispatchSource.Locked(required(execution.hint(AgentRuntimeV2ExecutionStore.DELIVERY,cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",origin.get("delivery_id")))),required(execution.hint(AgentRuntimeV2ExecutionStore.OUTBOX,cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",origin.get("source_outbox_id")))),required(execution.hint(AgentRuntimeV2ExecutionStore.INBOX,cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("id",origin.get("inbox_id")))));
                effects.inspect(origin,rows,validator.inspect(rows,origin,false),false);
            }
            graphVerified=true;
        }
        private static <T>T required(T value){if(value==null)throw new IllegalStateException("R4 original graph row absent");return value;}
    }
    record Definition(List<ColumnDefinition> columns,Map<String,IndexDefinition> indexes,Map<String,String> checks){
        Definition{columns=List.copyOf(columns);indexes=Map.copyOf(indexes);checks=Map.copyOf(checks);}
    }
    private static void require(boolean value){if(!value)throw new IllegalStateException("exact R4 MySQL catalog required");}
    static String manualSql(){
        try(var input=AgentRuntimeV2EffectConfiguration.class.getResourceAsStream("/db/agent-runtime-v2-effects-manual-20261003.sql")){
            if(input==null)throw new IllegalStateException("R4 reviewed manual schema absent");
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException failure){throw new IllegalStateException("R4 manual schema unreadable",failure);}
    }
    /** Parse only the bundled reviewed fixed five-table schema, never message
     * data or a user-selected file. Reject unrecognized DDL instead of guessing
     * defaults. This function describes metadata and never executes the SQL.
     */
    static Map<String,Definition> expected(){
        var result=new LinkedHashMap<String,Definition>();
        var tablePattern=Pattern.compile("CREATE TABLE (agent_runtime_v2_effect(?:_run|_request|_event|_blob)?) \\(\\n(.*?)\\n\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;",Pattern.DOTALL);
        var columnPattern=Pattern.compile("([a-z_][a-z0-9_]*) (CHAR\\([0-9]+\\)|VARCHAR\\([0-9]+\\)|VARBINARY\\([0-9]+\\)|BINARY\\([0-9]+\\)|MEDIUMBLOB|BIGINT|TINYINT) (NOT NULL|NULL)");
        var indexPattern=Pattern.compile("(UNIQUE KEY|KEY) ([a-z0-9_]+) \\(([a-z_][a-z0-9_]*(?:,[a-z_][a-z0-9_]*)*)\\)");
        var checkPattern=Pattern.compile("CONSTRAINT ([a-z0-9_]+) CHECK \\((.*)\\)");
        String sql=manualSql().replaceAll("(?m)^--[^\\r\\n]*(?:\\r?\\n|$)","");
        var tables=tablePattern.matcher(sql);int end=0;
        while(tables.find()){
            require(sql.substring(end,tables.start()).isBlank());end=tables.end();
            var columns=new ArrayList<ColumnDefinition>();var indexes=new LinkedHashMap<String,IndexDefinition>();var checks=new LinkedHashMap<String,String>();
            for(String raw:tables.group(2).split("\\n")){
                String line=raw.strip();if(line.endsWith(","))line=line.substring(0,line.length()-1);
                var column=columnPattern.matcher(line);var index=indexPattern.matcher(line);var check=checkPattern.matcher(line);
                if(column.matches()){
                    String type=column.group(2).toLowerCase(Locale.ROOT),base=type.replaceAll("\\(.*","");
                    columns.add(new ColumnDefinition(column.group(1),base,type,"NULL".equals(column.group(3)),null,Set.of("char","varchar").contains(base)?"utf8mb4_bin":null,"",""));
                }else if(line.startsWith("PRIMARY KEY (")){
                    require(line.matches("PRIMARY KEY \\([a-z_][a-z0-9_]*(?:,[a-z_][a-z0-9_]*)*\\)"));require(indexes.put("PRIMARY",new IndexDefinition(true,List.of(line.substring(13,line.length()-1).split(","))))==null);
                }else if(index.matches())require(indexes.put(index.group(2),new IndexDefinition("UNIQUE KEY".equals(index.group(1)),List.of(index.group(3).split(","))))==null);
                else if(check.matches())require(checks.put(check.group(1),check.group(2))==null);
                else throw new IllegalStateException("unrecognized reviewed R4 schema declaration");
            }
            require(!columns.isEmpty()&&indexes.containsKey("PRIMARY")&&!checks.isEmpty());
            require(result.put(tables.group(1),new Definition(columns,indexes,checks))==null);
        }
        require(sql.substring(end).isBlank());require(result.keySet().equals(Set.of(RUN,EFFECT,REQUEST,EVENT,BLOB)));
        return Collections.unmodifiableMap(result);
    }
}
