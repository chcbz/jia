package cn.jia.wx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MpUserLastActiveSqlContractTest {

    @Test
    void migrationAndTouchStatementAreAdditiveAndMonotonic() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/wx-mp-user-last-active-v1.sql"));
        String mapper = Files.readString(Path.of("src/main/resources/cn/jia/wx/mapper/MpUserMapper.xml"));

        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("ALTER TABLE wx_mp_user ADD COLUMN last_active_time BIGINT"));
        assertTrue(migration.contains("column_name = 'last_active_time'"));
        assertTrue(mapper.contains("id=\"touchLastActive\""));
        assertTrue(mapper.contains("last_active_time &lt; #{lastActiveTime"));
        assertTrue(mapper.contains("WHERE id = #{id,jdbcType=BIGINT}"));
    }
}
