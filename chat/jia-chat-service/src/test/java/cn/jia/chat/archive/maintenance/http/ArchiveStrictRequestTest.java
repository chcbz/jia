package cn.jia.chat.archive.maintenance.http;

import cn.jia.chat.archive.maintenance.dto.ArchiveAppointmentCreateRequest;
import cn.jia.chat.archive.maintenance.dto.ArchiveDraftUpdateRequest;
import cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveStrictRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsForgedIdentityAtEveryDepthAndDuplicateKeys() {
        assertRejected("{\"agentId\":\"a\",\"ownerJiacn\":\"b\"}", ArchiveAppointmentCreateRequest.class);
        assertRejected("{\"requiredSkill\":{\"key\":\"archive\",\"permissions\":\"publish\"}}", ArchiveAppointmentCreateRequest.class);
        assertRejected("{\"blocks\":[{\"blockKey\":\"a\",\"paragraphs\":[{\"text\":\"hello\",\"tenantId\":\"1\"}]}]}", ArchiveDraftUpdateRequest.class);
        assertRejected("{\"agentId\":\"a\",\"agentId\":\"b\"}", ArchiveAppointmentCreateRequest.class);
        assertRejected("{\"blocks\":[{\"titleSourceRanges\":[{\"startByte\":0,\"ownerJiacn\":\"x\"}]}]}", ArchiveDraftUpdateRequest.class);
        assertRejected("{\"blocks\":[],\"excludedSourceRanges\":[{\"startByte\":0,\"permissions\":\"publish\"}]}", ArchiveDraftUpdateRequest.class);
    }

    @Test
    void permitsOnlyDeclaredNestedFields() {
        var parsed = ArchiveStrictRequest.read(mapper, "{\"blocks\":[{\"blockType\":\"CHAPTER\",\"blockKey\":\"one\",\"ordinal\":1,\"title\":\"One\",\"paragraphs\":[{\"ordinal\":1,\"text\":\"正文\"}]}]}".getBytes(StandardCharsets.UTF_8), ArchiveDraftUpdateRequest.class);
        assertEquals("正文", parsed.blocks().getFirst().paragraphs().getFirst().text());
    }

    private <T> void assertRejected(String json, Class<T> type) {
        ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                () -> ArchiveStrictRequest.read(mapper, json.getBytes(StandardCharsets.UTF_8), type));
        assertEquals(400, failure.status());
    }
}
