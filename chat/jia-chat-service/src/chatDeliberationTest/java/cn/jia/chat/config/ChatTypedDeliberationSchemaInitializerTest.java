package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatTypedDeliberationSchemaInitializerTest {
    @Test void defaultOffPerformsNoDatabaseAccess() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        var initializer=new ChatTypedDeliberationSchemaInitializer(jdbc,false,false);
        initializer.initialize();assertFalse(initializer.ready());verifyNoInteractions(jdbc);
    }

    @Test void ddlIsExactlyFourAdditiveTablesWithExplicitForeignKeyActions() {
        List<String> ddl=ChatTypedDeliberationSchemaInitializer.ddl();
        assertEquals(4,ddl.size());
        assertTrue(ddl.get(0).startsWith("CREATE TABLE IF NOT EXISTS chat_typed_outcome "));
        assertTrue(ddl.get(1).startsWith("CREATE TABLE IF NOT EXISTS chat_typed_pending_question "));
        assertTrue(ddl.get(2).startsWith("CREATE TABLE IF NOT EXISTS chat_typed_proposal "));
        assertTrue(ddl.get(3).startsWith("CREATE TABLE IF NOT EXISTS chat_typed_admission "));
        String all=String.join("\n",ddl).toLowerCase();
        assertFalse(all.contains("alter table"));assertFalse(all.contains("drop table"));
        assertEquals(4,count(all,"on update restrict on delete restrict"));
        assertTrue(all.contains("idx_chat_typed_outcome_turn_fk"));
        assertTrue(all.contains("idx_chat_typed_admission_request_fk"));
    }

    @Test void catalogQuoteNormalizationAcceptsOnlyPlainOrOneRawEscapeAndPreservesLiteralData() {
        String plain="kind IN ('ANSWER','CLARIFY')";
        assertEquals(ChatTypedDeliberationSchemaInitializer.canonicalCheck(plain),
                ChatTypedDeliberationSchemaInitializer.canonicalCheck("kind IN (\\'ANSWER\\',\\'CLARIFY\\')"));
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.canonicalCheck(
                "kind IN (\\\\'ANSWER\\\\',\\\\'CLARIFY\\\\')"));
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.canonicalCheck(
                "kind IN ('ANSWER',\\'CLARIFY\\')"));
        assertTrue(ChatTypedDeliberationSchemaInitializer.canonicalCheck(
                "value='literal_utf8mb4\\\\tail'").contains("literal_utf8mb4\\\\tail"));
        assertTrue(ChatTypedDeliberationSchemaInitializer.canonicalCheck(
                "_utf8mb4value='x'").startsWith("_utf8mb4value"));
    }

    @Test void changedOperatorsParenthesesNullClausesAndUnenforcedChecksFailClosed() {
        String exact=ChatTypedDeliberationSchemaInitializer.canonicalCheck("(state='OPEN' AND state_version=0)");
        assertNotEquals(exact,ChatTypedDeliberationSchemaInitializer.canonicalCheck("(state='OPEN' OR state_version=0)"));
        JdbcTemplate empty=mock(JdbcTemplate.class);
        when(empty.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.validateChecks(empty));
        JdbcTemplate nullClause=mock(JdbcTemplate.class);
        when(nullClause.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(
                "constraint_name","chk_chat_typed_outcome_generation","enforced","YES","check_clause",null)));
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.validateChecks(nullClause));
        JdbcTemplate unenforced=mock(JdbcTemplate.class);
        when(unenforced.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(
                "constraint_name","chk_chat_typed_outcome_generation","enforced","NO","check_clause","conversation_generation>=1")));
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.validateChecks(unenforced));
    }

    @Test void indexOrderPrefixExpressionAndUnexpectedIndexesFailClosed() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        List<Map<String,Object>> rows=indexRows();
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(rows);
        ChatTypedDeliberationSchemaInitializer.validateIndexes(jdbc,"chat_typed_outcome");
        rows.get(0).put("SUB_PART",16);
        assertThrows(IllegalStateException.class,
                ()->ChatTypedDeliberationSchemaInitializer.validateIndexes(jdbc,"chat_typed_outcome"));
        rows.get(0).put("SUB_PART",null);rows.get(0).put("COLUMN_NAME","foreign");
        assertThrows(IllegalStateException.class,
                ()->ChatTypedDeliberationSchemaInitializer.validateIndexes(jdbc,"chat_typed_outcome"));
    }

    @Test void missingOrChangedForeignKeyCatalogFailsClosed() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.validateForeignKeys(jdbc));
    }

    private static List<Map<String,Object>> indexRows() {
        Map<String,List<String>> indexes=new LinkedHashMap<>();
        indexes.put("PRIMARY",List.of("outcome_id"));
        indexes.put("idx_chat_typed_outcome_message",List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","assistant_message_id"));
        indexes.put("idx_chat_typed_outcome_turn_fk",List.of("turn_id"));
        indexes.put("uk_chat_typed_outcome_scope_id",List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","outcome_id"));
        indexes.put("uk_chat_typed_outcome_scope_request",List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","request_id","request_revision"));
        indexes.put("uk_chat_typed_outcome_scope_turn",List.of("tenant_id","owner_jiacn","client_id","conversation_id","conversation_generation","turn_id"));
        List<Map<String,Object>> rows=new ArrayList<>();
        indexes.forEach((name,columns)->{for(int i=0;i<columns.size();i++)rows.add(row(
                "INDEX_NAME",name,"NON_UNIQUE",name.startsWith("idx_")?1:0,"SEQ_IN_INDEX",i+1,
                "COLUMN_NAME",columns.get(i),"SUB_PART",null,"EXPRESSION",null));});
        return rows;
    }
    private static Map<String,Object> row(Object... values) {
        Map<String,Object> row=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)row.put((String)values[i],values[i+1]);return row;
    }
    private static int count(String value,String token){int count=0,index=0;while((index=value.indexOf(token,index))>=0){count++;index+=token.length();}return count;}
}
