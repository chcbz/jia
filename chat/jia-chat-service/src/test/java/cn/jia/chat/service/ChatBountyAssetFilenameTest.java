package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyAssetFilenameTest {
    @Test void historyProjectsStoredFilenameWithoutChangingAssetIdentity() throws Exception {
        checkFilename("读书会.txt", "读书会.txt");
    }

    @Test void missingHistoricalOutputDoesNotInventAFilenameOrDropTheMessage() throws Exception {
        checkFilename(null, "");
    }

    private void checkFilename(String storedName, String expected) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var projector = new ChatBountyAssetProjector(jdbc, mock(ChatDeliberationService.class),
                mock(ChatDeliberationDao.class), mock(ChatConversationDao.class), mock(ChatMessageDao.class),
                mock(PersonalWorkspaceExecutionService.class), mock(ChatConversationEventBroker.class));
        var rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("12");
        when(rs.getString(2)).thenReturn("part-1");
        when(rs.getString(3)).thenReturn("file");
        when(rs.getString(4)).thenReturn("asset-1");
        when(rs.getString(5)).thenReturn("text/plain");
        when(rs.getString(6)).thenReturn("a".repeat(64));
        when(rs.getLong(7)).thenReturn(8L);
        when(rs.getLong(8)).thenReturn(1L);
        when(rs.getString(9)).thenReturn(storedName);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("tenant"), eq("owner"), eq("client"), eq("42")))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertTrue(sql.contains("LEFT JOIN agent_personal_workspace_execution_output o"));
                    assertTrue(sql.contains("BINARY o.execution_id=BINARY a.execution_id"));
                    assertTrue(sql.contains("BINARY o.owner_jiacn=BINARY a.owner_jiacn"));
                    assertTrue(sql.contains("BINARY o.tenant_id=BINARY a.tenant_id"));
                    assertTrue(sql.contains("BINARY o.client_id=BINARY a.client_id"));
                    assertTrue(sql.contains("BINARY o.content_hash=BINARY a.sha256"));
                    RowMapper<?> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });
        var parts = projector.partsFor(new PersonalWorkspaceExecutionService.OwnerScope("tenant", "client", "owner"), "42");
        assertEquals(expected, parts.get("12").getFirst().filename());
        assertEquals("asset-1", parts.get("12").getFirst().assetId());
        assertEquals("1", parts.get("12").getFirst().revision());
    }
}
