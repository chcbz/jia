package cn.jia.user.dao.impl;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRelationMapperContractTest {

    @Test
    void relationEnrichmentIsOneBoundedUserIdScopedStatement() throws IOException {
        String xml;
        try (InputStream input = getClass().getResourceAsStream("/cn/jia/user/mapper/InfoMapper.xml")) {
            assertTrue(input != null);
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String statement = xml.substring(xml.indexOf("<select id=\"selectRelationsByUserIds\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectRelationsByUserIds\"")));

        assertEquals(2, occurrences(statement, "UNION ALL"));
        assertEquals(3, occurrences(statement, "<foreach collection=\"userIds\""));
        assertEquals(3, occurrences(statement, "WHERE user_id IN"));
        assertFalse(statement.contains("${"));
        assertFalse(statement.contains("tenant_id = '0'"));
        assertFalse(statement.contains("client_id = '0'"));
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
