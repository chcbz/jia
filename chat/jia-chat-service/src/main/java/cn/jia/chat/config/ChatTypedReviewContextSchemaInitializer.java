package cn.jia.chat.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Default-off. Fresh additive install is explicit; an existing incompatible table never gets overwritten. */
@Component
@ConditionalOnProperty(prefix="chat.delivery-review",name="enabled",havingValue="true")
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class ChatTypedReviewContextSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final ChatDeliberationSchemaInitializer deliberation;
    private final boolean allowMigration;
    public ChatTypedReviewContextSchemaInitializer(JdbcTemplate jdbc,ChatDeliberationSchemaInitializer deliberation,
            @Value("${cyf.chat.delivery-review-schema.allow-additive-migration:false}") boolean allowMigration) {
        this.jdbc=jdbc;this.deliberation=deliberation;this.allowMigration=allowMigration;
    }
    @Override public void afterPropertiesSet() throws Exception {
        deliberation.ensureInitialized();
        jdbc.execute((ConnectionCallback<Void>) connection->{
            if(!connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || connection.getMetaData().getDatabaseMajorVersion()<8)throw drift();
            var locked=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            String key="cyf:chat:delivery-review:v1";
            if(!Integer.valueOf(1).equals(locked.queryForObject("SELECT GET_LOCK(?,-1)",Integer.class,key)))throw drift();
            try {
                Long count=locked.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='chat_typed_review_context'",Long.class);
                if(count==null)throw drift();
                if(count==0) {if(!allowMigration)throw new IllegalStateException("Review context additive migration is not authorized");locked.execute(ddl());}
                validate(locked);
            } finally {if(!Integer.valueOf(1).equals(locked.queryForObject("SELECT RELEASE_LOCK(?)",Integer.class,key)))throw drift();}
            return null;
        });
    }
    static String ddl() {
        try {
            String text=new ClassPathResource("db/chat-typed-review-context-v1.sql").getContentAsString(StandardCharsets.UTF_8).lines()
                    .filter(line->!line.stripLeading().startsWith("--")).reduce("",(a,b)->a+b+'\n').strip();
            if(!text.startsWith("CREATE TABLE IF NOT EXISTS chat_typed_review_context (") || !text.endsWith(";")
                    || text.substring(0,text.length()-1).contains(";"))throw drift();
            return text;
        }catch(java.io.IOException failure){throw new IllegalStateException("Review context DDL unavailable",failure);}
    }
    static void validate(JdbcTemplate jdbc) {
        var expected=ChatTaskDeliveryReviewSchemaInitializer.columns(ddl(),8);
        Map<String,String> actual=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("""
            SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns
             WHERE table_schema=DATABASE() AND table_name='chat_typed_review_context' ORDER BY ordinal_position
            """))actual.put(text(row,"column_name"),text(row,"column_type").toLowerCase(Locale.ROOT)+"|"+text(row,"is_nullable")+"|"+text(row,"collation_name"));
        if(!expected.equals(actual))throw drift();
        Map<String,String> checks=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("""
            SELECT t.constraint_name,c.check_clause,t.enforced FROM information_schema.table_constraints t
             JOIN information_schema.check_constraints c ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
             WHERE t.table_schema=DATABASE() AND t.table_name='chat_typed_review_context' AND t.constraint_type='CHECK'
            """)) {
            if(!"YES".equals(text(row,"enforced")))throw drift();
            checks.put(text(row,"constraint_name"),ChatTaskDeliveryReviewSchemaInitializer.metadataExpression(text(row,"check_clause")));
        }
        if(!ChatTaskDeliveryReviewSchemaInitializer.checks(ddl()).equals(checks))throw drift();
        Map<String,String> indexes=new LinkedHashMap<>();
        for(var row:jdbc.queryForList("""
            SELECT index_name,non_unique,column_name,seq_in_index,sub_part FROM information_schema.statistics
             WHERE table_schema=DATABASE() AND table_name='chat_typed_review_context' ORDER BY index_name,seq_in_index
            """)) {
            if(!"null".equals(text(row,"sub_part")))throw drift();
            indexes.merge(text(row,"index_name"),text(row,"non_unique")+":"+text(row,"column_name"),(a,b)->a+","+b.substring(2));
        }
        if(!Map.of("PRIMARY","0:tenant_id,owner_jiacn,client_id,request_id,request_revision").equals(indexes))throw drift();
        var fks=jdbc.queryForList("""
            SELECT k.constraint_name,k.column_name,k.referenced_table_schema,k.referenced_table_name,k.referenced_column_name,
             r.update_rule,r.delete_rule,DATABASE() current_schema FROM information_schema.key_column_usage k
             JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
             WHERE k.table_schema=DATABASE() AND k.table_name='chat_typed_review_context' AND k.referenced_table_name IS NOT NULL
             ORDER BY k.constraint_name,k.ordinal_position
            """);
        var columns=java.util.List.of("tenant_id","owner_jiacn","client_id","request_id","request_revision");
        if(fks.size()!=columns.size())throw drift();
        for(int i=0;i<columns.size();i++) {
            var row=fks.get(i);
            if(!"fk_ctrc_request".equals(text(row,"constraint_name")) || !columns.get(i).equals(text(row,"column_name"))
                    || !columns.get(i).equals(text(row,"referenced_column_name")) || !"chat_request".equals(text(row,"referenced_table_name"))
                    || !text(row,"current_schema").equals(text(row,"referenced_table_schema"))
                    || !java.util.Set.of("RESTRICT","NO ACTION").contains(text(row,"update_rule"))
                    || !java.util.Set.of("RESTRICT","NO ACTION").contains(text(row,"delete_rule")))throw drift();
        }
        var table=jdbc.queryForList("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='chat_typed_review_context'");
        if(table.size()!=1 || !"InnoDB".equals(text(table.getFirst(),"engine")) || !"utf8mb4_0900_bin".equals(text(table.getFirst(),"table_collation")))throw drift();
    }
    private static String text(Map<String,Object> row,String key) {return row.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(key)).map(e->String.valueOf(e.getValue())).findFirst().orElse("null");}
    private static IllegalStateException drift(){return new IllegalStateException("Review context schema differs");}
}
