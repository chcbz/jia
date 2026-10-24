package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTypedReviewContextSchemaInitializerTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final Map<String,List<Map<String,Object>>> rows=new HashMap<>();
    ChatTypedReviewContextSchemaInitializerTest() {
        var columns=new ArrayList<Map<String,Object>>();
        ChatTaskDeliveryReviewSchemaInitializer.columns(ChatTypedReviewContextSchemaInitializer.ddl(),8).forEach((name,value)->{
            var bits=value.split("\\|");columns.add(new HashMap<>(Map.of("column_name",name,"column_type",bits[0],"is_nullable",bits[1],"collation_name",bits[2])));
        });rows.put("columns",columns);
        var checks=new ArrayList<Map<String,Object>>();
        // Raw DDL expressions are intentionally kept; the installed server is free to add parentheses/charset prefixes.
        String ddl=ChatTypedReviewContextSchemaInitializer.ddl();
        for(String name:List.of("chk_ctrc_revision","chk_ctrc_json","chk_ctrc_hash","chk_ctrc_time")) {
            String start="CONSTRAINT "+name+" CHECK ";int begin=ddl.indexOf(start)+start.length(),depth=0,end=begin;boolean quoted=false;
            for(;end<ddl.length();end++){char c=ddl.charAt(end);if(c=='\'')quoted=!quoted;if(quoted)continue;if(c=='(')depth++;else if(c==')'&&--depth==0)break;}
            checks.add(new HashMap<>(Map.of("constraint_name",name,"check_clause",ddl.substring(begin,end+1),"enforced","YES")));
        }rows.put("checks",checks);
        var indexes=new ArrayList<Map<String,Object>>();var foreign=new ArrayList<Map<String,Object>>();int position=1;
        for(String column:List.of("tenant_id","owner_jiacn","client_id","request_id","request_revision")) {
            var index=new HashMap<String,Object>();index.put("index_name","PRIMARY");index.put("non_unique",0);index.put("column_name",column);index.put("seq_in_index",position++);index.put("sub_part",null);indexes.add(index);
            foreign.add(new HashMap<>(Map.of("constraint_name","fk_ctrc_request","column_name",column,"referenced_table_schema","fixture", "referenced_table_name","chat_request",
                    "referenced_column_name",column,"update_rule","RESTRICT","delete_rule","RESTRICT","current_schema","fixture")));
        }rows.put("indexes",indexes);rows.put("foreign",foreign);rows.put("table",List.of(Map.of("engine","InnoDB","table_collation","utf8mb4_0900_bin")));
        when(jdbc.queryForList(anyString())).thenAnswer(call->{String sql=call.getArgument(0);
            if(sql.contains("information_schema.columns"))return rows.get("columns");if(sql.contains("information_schema.check_constraints"))return rows.get("checks");
            if(sql.contains("information_schema.statistics"))return rows.get("indexes");if(sql.contains("information_schema.key_column_usage"))return rows.get("foreign");
            if(sql.contains("information_schema.tables"))return rows.get("table");throw new AssertionError("Unexpected SQL");});
    }
    @Test void freshDdlHasCompleteImmutableScopedRequestReferenceAndHashChecks() {
        var ddl=ChatTypedReviewContextSchemaInitializer.ddl();assertEquals(8,ChatTaskDeliveryReviewSchemaInitializer.columns(ddl,8).size());
        assertEquals(4,ChatTaskDeliveryReviewSchemaInitializer.checks(ddl).size());assertTrue(ddl.contains("REFERENCES chat_request"));
        assertFalse(ddl.contains("ON DELETE CASCADE"));assertTrue(ddl.contains("JSON_TYPE"));
        ChatTypedReviewContextSchemaInitializer.validate(jdbc);verify(jdbc,never()).execute(anyString());
    }
    @Test void extraReadIndexDoesNotBlockStartupButExtraUniquenessDoes() {
        var extra = new HashMap<String,Object>();
        extra.put("index_name", "idx_observation"); extra.put("non_unique", 1);
        extra.put("column_name", "request_id"); extra.put("seq_in_index", 1); extra.put("sub_part", null);
        rows.get("indexes").add(extra);
        assertDoesNotThrow(() -> ChatTypedReviewContextSchemaInitializer.validate(jdbc));
        extra.put("non_unique", 0);
        assertThrows(IllegalStateException.class, () -> ChatTypedReviewContextSchemaInitializer.validate(jdbc));
    }
    @Test void unknownColumnAndMissingChecksAreNeverSilentlyAccepted() {
        rows.get("columns").getFirst().put("collation_name","utf8mb4_0900_ai_ci");
        assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
    }
    @Test void weakeningAnEnforcedCheckOrRequestForeignKeyIsRejected() {
        rows.get("checks").getFirst().put("enforced","NO");assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
        rows.get("checks").getFirst().put("enforced","YES");rows.get("foreign").getFirst().put("delete_rule","CASCADE");
        assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
    }
    @Test void prefixIndexOrNontransactionalTableCannotBecomeReady() {
        rows.get("indexes").getFirst().put("sub_part",20);assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
        rows.get("indexes").getFirst().put("sub_part",null);rows.put("table",List.of(Map.of("engine","MyISAM","table_collation","utf8mb4_0900_bin")));
        assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
    }
    @Test void schemaAndProducersAreOffUnlessSameDeliveryReviewGateIsExplicitlyEnabled() {
        for(Class<?> type:List.of(ChatTypedReviewContextSchemaInitializer.class,cn.jia.chat.service.ChatTypedReviewContextStore.class,
                cn.jia.chat.service.ChatTypedReviewContextService.class)) {
            var gate=type.getAnnotation(ConditionalOnProperty.class);assertEquals("chat.delivery-review",gate.prefix());
            assertEquals("true",gate.havingValue());assertFalse(gate.matchIfMissing());
        }
    }
}
