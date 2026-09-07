package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveBlockRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcArchiveContentStoreTest {
    @Test
    void blockMapperReadsNullableChapterNumberBeforeAnyLaterColumn() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        AtomicReference<String> lastColumn = new AtomicReference<>();
        Map<String, String> strings = Map.of(
                "edition_id", "edition-1",
                "block_id", "preface",
                "block_type", "PREFACE",
                "title", "Preface",
                "block_content_sha256", "a".repeat(64));
        when(resultSet.getString(anyString())).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            lastColumn.set(column);
            return strings.get(column);
        });
        when(resultSet.getInt(anyString())).thenAnswer(invocation -> {
            String column = invocation.getArgument(0);
            lastColumn.set(column);
            return "paragraph_count".equals(column) ? 11 : 0;
        });
        when(resultSet.getLong(anyString())).thenAnswer(invocation -> {
            lastColumn.set(invocation.getArgument(0));
            return 123L;
        });
        when(resultSet.wasNull()).thenAnswer(
                ignored -> "chapter_number".equals(lastColumn.get()));

        ArchiveBlockRecord block = blockMapper().mapRow(resultSet, 0);

        assertNull(block.chapterNumber());
    }

    @SuppressWarnings("unchecked")
    private RowMapper<ArchiveBlockRecord> blockMapper() throws Exception {
        Field field = JdbcArchiveContentStore.class.getDeclaredField("BLOCK_MAPPER");
        field.setAccessible(true);
        return (RowMapper<ArchiveBlockRecord>) field.get(null);
    }
}
