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
        var indexes=new ArrayList<Map<String,Object>>();var foreign=new ArrayList<Map<String,Object>>();int position=1;
        for(String column:List.of("tenant_id","owner_jiacn","client_id","request_id","request_revision")) {
            var index=new HashMap<String,Object>();index.put("index_name","PRIMARY");index.put("non_unique",0);index.put("column_name",column);index.put("seq_in_index",position++);index.put("sub_part",null);indexes.add(index);
            foreign.add(new HashMap<>(Map.of("constraint_name","fk_ctrc_request","column_name",column,"referenced_table_schema","fixture", "referenced_table_name","chat_request",
                    "referenced_column_name",column,"update_rule","RESTRICT","delete_rule","RESTRICT","current_schema","fixture")));
        }rows.put("indexes",indexes);rows.put("foreign",foreign);rows.put("table",List.of(Map.of("engine","InnoDB","table_collation","utf8mb4_0900_bin")));
        when(jdbc.queryForList(anyString())).thenAnswer(call->{String sql=call.getArgument(0);
            if(sql.contains("information_schema.columns"))return rows.get("columns");
            if(sql.contains("information_schema.statistics"))return rows.get("indexes");if(sql.contains("information_schema.key_column_usage"))return rows.get("foreign");
            if(sql.contains("information_schema.tables"))return rows.get("table");throw new AssertionError("Unexpected SQL");});
    }
    @Test void freshDdlHasCompleteImmutableScopedRequestReferenceWithoutChecks() {
        var ddl=ChatTypedReviewContextSchemaInitializer.ddl();assertEquals(8,ChatTaskDeliveryReviewSchemaInitializer.columns(ddl,8).size());
        assertFalse(ddl.toLowerCase().contains("check ("));assertTrue(ddl.contains("REFERENCES chat_request"));
        assertFalse(ddl.contains("ON DELETE CASCADE"));
        ChatTypedReviewContextSchemaInitializer.validate(jdbc);verify(jdbc,never()).execute(anyString());
    }
    @Test void columnCollationDriftIsRejected() {
        rows.get("columns").getFirst().put("collation_name","utf8mb4_0900_ai_ci");
        assertThrows(IllegalStateException.class,()->ChatTypedReviewContextSchemaInitializer.validate(jdbc));
    }
    @Test void requestForeignKeyCascadeIsRejected() {
        rows.get("foreign").getFirst().put("delete_rule","CASCADE");
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
