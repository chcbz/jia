package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatTypedDeliberationVersionProbeTest {
    @Test
    void disabledDoesNotTouchDatabase() {
        var jdbc = mock(JdbcTemplate.class);
        var probe = new ChatTypedDeliberationVersionProbe(jdbc, false);
        probe.run(null);
        assertEquals(DISABLED, probe.readiness());
        assertFalse(probe.ready());
        verifyNoInteractions(jdbc);
    }

    @Test
    void appliedVersionRequiresOnlyOneBoundedReadAndNoMaintenance() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("APPLIED"));
        var probe = new ChatTypedDeliberationVersionProbe(jdbc, true);
        assertEquals(INITIALIZING, probe.readiness());
        probe.run(null);
        assertTrue(probe.ready());
        verify(jdbc).queryForList(argThat(sql -> sql.strip().equals("""
                SELECT stage FROM chat_deliberation_schema_version
                WHERE version=3 LIMIT 2""")), eq(String.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void missingVersionIsUnavailableNotReady() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        var probe = new ChatTypedDeliberationVersionProbe(jdbc, true);
        probe.run(null);
        assertEquals(UNAVAILABLE, probe.readiness());
        assertFalse(probe.ready());
    }

    @Test
    void partialOrDuplicateVersionFailsClosed() {
        for (List<String> stages : List.of(List.of("PREFLIGHT"), List.of("APPLIED", "APPLIED"))) {
            var jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(stages);
            var probe = new ChatTypedDeliberationVersionProbe(jdbc, true);
            assertThrows(IllegalStateException.class, () -> probe.run(null));
            assertEquals(FAILED, probe.readiness());
            assertFalse(probe.ready());
        }
    }

    @Test
    void databaseFailureDoesNotReportReadyOrAttemptRepair() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        var probe = new ChatTypedDeliberationVersionProbe(jdbc, true);
        assertThrows(DataAccessResourceFailureException.class, () -> probe.run(null));
        assertEquals(FAILED, probe.readiness());
        verify(jdbc).queryForList(anyString(), eq(String.class));
        verifyNoMoreInteractions(jdbc);
    }
}
