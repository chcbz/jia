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
        String pendingPredicate="((state='OPEN' AND state_version=0 AND reply_request_id IS NULL\n"
                +"      AND reply_idempotency_key IS NULL AND reply_body_digest IS NULL)\n"
                +"     OR (state='ANSWERED' AND state_version=1 AND reply_request_id IS NOT NULL\n"
                +"      AND reply_idempotency_key IS NOT NULL\n"
                +"      AND reply_body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$')),";
        assertTrue(ddl.get(1).contains(pendingPredicate));
        assertFalse(ddl.get(1).contains("reply_body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'))),"));
    }

    @Test void parserAcceptsOnlyTheApprovedForeignKeyActionAndFourTableCatalog() {
        List<String> approved=ChatTypedDeliberationSchemaInitializer.ddl();
        String outcome=approved.getFirst();
        assertTrue(ChatTypedDeliberationSchemaInitializer.approvedDdlStatement(outcome,"chat_typed_outcome"));
        assertFalse(ChatTypedDeliberationSchemaInitializer.approvedDdlStatement(
                outcome.replace("ON DELETE RESTRICT","ON DELETE CASCADE"),"chat_typed_outcome"));
        assertFalse(ChatTypedDeliberationSchemaInitializer.approvedDdlStatement(
                outcome+" DELETE FROM chat_typed_outcome","chat_typed_outcome"));
        assertFalse(ChatTypedDeliberationSchemaInitializer.approvedDdlStatement(outcome,"foreign_table"));
        assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.parseDdl(
                String.join(";\n",approved)+";\nCREATE TABLE IF NOT EXISTS foreign_table (id BIGINT)"));
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

    @Test void capturedMysql8021CheckCatalogAcceptsAllRowsAndRejectsEachCorrectedConjunctionWeakened() throws Exception {
        Map<String,Object> fixture=capturedCheckCatalog();
        assertEquals("98af3d88d5a1212e52764e308b2361e99dbffbb7595d1196123465a0481a41a4",fixture.get("sourceSqlSha256"));
        List<Map<String,Object>> actual=capturedCheckRows(fixture);
        assertEquals(18,actual.size());
        JdbcTemplate accepted=mock(JdbcTemplate.class);
        when(accepted.queryForList(anyString(),any(Object[].class))).thenReturn(actual);
        ChatTypedDeliberationSchemaInitializer.validateChecks(accepted);
        for(String corrected:List.of("chk_chat_typed_admission_digest","chk_chat_typed_admission_reply",
                "chk_chat_typed_admission_turns","chk_chat_typed_outcome_json","chk_chat_typed_pending_version",
                "chk_chat_typed_proposal_parent","chk_chat_typed_proposal_sources")) {
            List<Map<String,Object>> weakened=actual.stream().map(LinkedHashMap::new).toList();
            Map<String,Object> row=weakened.stream().filter(value->corrected.equals(value.get("constraint_name"))).findFirst().orElseThrow();
            String clause=(String)row.get("check_clause");String changed=clause.replaceFirst("(?i) and "," or ");
            assertNotEquals(clause,changed,corrected);row.put("check_clause",changed);
            JdbcTemplate rejected=mock(JdbcTemplate.class);
            when(rejected.queryForList(anyString(),any(Object[].class))).thenReturn(weakened);
            assertThrows(IllegalStateException.class,()->ChatTypedDeliberationSchemaInitializer.validateChecks(rejected),corrected);
        }
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


    @SuppressWarnings("unchecked") private static Map<String,Object> capturedCheckCatalog() throws Exception {
        try(var stream=ChatTypedDeliberationSchemaInitializerTest.class.getResourceAsStream(
                "/contracts/typed-check-catalog-mysql8021.json")) {
            assertNotNull(stream);
            return new tools.jackson.databind.ObjectMapper().readValue(stream,Map.class);
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> capturedCheckRows(Map<String,Object> fixture) {
        List<String> rows=(List<String>)fixture.get("capturedRawRows");
        assertEquals(18,rows.size());assertEquals(18,fixture.get("rowCount"));
        return rows.stream().map(value->{String[] fields=value.split("\\t",3);assertEquals(3,fields.length);
            return row("constraint_name",fields[0],"enforced",fields[1],"check_clause",fields[2]);}).toList();
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
