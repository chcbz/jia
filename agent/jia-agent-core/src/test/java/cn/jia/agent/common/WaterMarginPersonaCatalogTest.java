package cn.jia.agent.common;

import cn.jia.agent.entity.AgentPersonaEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterMarginPersonaCatalogTest {
    @Test
    void exposesImmutablePublicBuiltinMetadataWithoutDatabaseScope() {
        assertEquals(108, WaterMarginPersonaCatalog.definitions().size());
        assertThrows(UnsupportedOperationException.class,
                () -> WaterMarginPersonaCatalog.definitions().clear());

        AgentPersonaEntity first = WaterMarginPersonaCatalog.publicPersona("husanniang");
        AgentPersonaEntity second = WaterMarginPersonaCatalog.publicPersona("husanniang");
        assertNotSame(first, second);
        assertEquals(59, first.getRankNo());
        assertEquals("地慧星", first.getStarName());
        assertEquals("扈三娘", first.getName());
        assertEquals("一丈青", first.getTitle());
        assertEquals("0", first.getTenantId());
        assertNull(first.getClientId());
        assertTrue(first.getActive());
        assertFalse(first.getSystemAgent());
        assertTrue(first.getAbilities().contains("scouting"));
        assertNull(WaterMarginPersonaCatalog.publicPersona("HUSANNIANG"));
        assertNull(WaterMarginPersonaCatalog.publicPersona("private-persona"));
    }

    @Test
    void songjiangRemainsTheOnlySystemBuiltin() {
        long systemCount = WaterMarginPersonaCatalog.publicPersonas().stream()
                .filter(persona -> Boolean.TRUE.equals(persona.getSystemAgent()))
                .count();
        assertEquals(1, systemCount);
        assertTrue(WaterMarginPersonaCatalog.publicPersona("songjiang").getSystemAgent());
    }
}
