package cn.jia.chat.deliberation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatMessageOutcomeStoreTest {
    @Test void emptyWindowDoesNotReadAnything() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        assertTrue(new ChatMessageOutcomeStore(jdbc).read(scope(), List.of(), List.of()).outcomes().isEmpty());
        verifyNoInteractions(jdbc);
    }
    @Test void queryCountIsIndependentOfPageSizeAndNeitherQueryLocksOrReadsFullSourceCatalogs() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var sql = new ArrayList<String>();
        when(jdbc.queryForList(anyString(), anyMap())).thenAnswer(call -> {
            String query = call.getArgument(0); Map<String, Object> params = call.getArgument(1);
            sql.add(query); assertEquals(20, ((List<?>) params.get("requests")).size());
            assertEquals("owner", params.get("owner")); assertEquals(1L, params.get("generation"));
            assertFalse(query.toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
            assertFalse(query.contains("source_catalog_json")); assertFalse(query.contains("binding_json"));
            assertFalse(query.contains("o.text"));
            if (sql.size() == 1) assertTrue(query.contains("JSON_REMOVE(o.outcome_json"));
            assertFalse(query.contains("SELECT *")); assertFalse(query.contains("payload_json"));
            assertTrue(query.contains("t.request_id IN (:requests)"));
            assertTrue(query.contains("t.final_message_id IS NULL OR t.final_message_id IN (:messages)"));
            assertTrue(query.contains("CAST(t.owner_jiacn AS BINARY)=CAST(:owner AS BINARY)"));
            return sql.size() == 1 ? List.of(Map.of("turn_id", "turn")) : List.of();
        });
        var requests = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "request-" + i).toList();
        new ChatMessageOutcomeStore(jdbc).read(scope(), requests, List.of(9L));
        assertEquals(2, sql.size());
        verify(jdbc, times(2)).queryForList(anyString(), anyMap()); verifyNoMoreInteractions(jdbc);
    }
    private static ChatBountyRequestIndexStore.Scope scope() {
        return new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "42", 1);
    }
}
