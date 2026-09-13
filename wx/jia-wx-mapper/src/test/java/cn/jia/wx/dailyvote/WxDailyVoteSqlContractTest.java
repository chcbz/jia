package cn.jia.wx.dailyvote;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WxDailyVoteSqlContractTest {

    @Test
    void receiptMapperLocksBothIdempotencyKeysAndCompletesByOwnedToken() throws Exception {
        String mapper = source("src/main/resources/cn/jia/wx/mapper/WxDailyVoteReceiptMapper.xml");

        assertTrue(mapper.contains("ON DUPLICATE KEY UPDATE id = id"));
        assertTrue(mapper.contains("INSERT INTO wx_daily_vote_message_receipt"));
        assertTrue(mapper.contains("INNER JOIN wx_daily_vote_receipt r ON r.id = m.receipt_id"));
        assertTrue(mapper.contains("id=\"selectMessage\""));
        assertTrue(mapper.contains("id=\"lockById\""));
        assertTrue(mapper.contains("WHERE id = #{id,jdbcType=BIGINT}"));
        assertTrue(mapper.contains("message_key = #{messageKey"));
        assertTrue(mapper.contains("user_key = #{userKey"));
        assertTrue(mapper.contains("question_id = #{questionId"));
        assertTrue(mapper.contains("FOR UPDATE"));
        assertTrue(mapper.contains("processor_token = #{processorToken"));
        assertTrue(mapper.contains("status = 'PROCESSING'"));
        assertTrue(mapper.contains("SET status = 'COMPLETED'"));
    }

    @Test
    void migrationIsAdditiveRepeatableAndPreservesHistoricalTicks() throws Exception {
        String migration = source("src/main/resources/db/wx-daily-vote-opt-01a08daa.sql");

        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS wx_daily_vote_receipt"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS wx_daily_vote_message_receipt"));
        assertTrue(migration.contains("UNIQUE KEY uk_wx_daily_vote_message (appid, message_key)"));
        assertTrue(migration.contains("UNIQUE KEY uk_wx_daily_vote_user_question (appid, user_key, question_id)"));
        assertTrue(migration.contains("UNIQUE KEY uk_wx_daily_vote_message_alias (appid, message_key)"));
        assertTrue(migration.contains("idx_wx_mp_user_appid_open_id (appid, open_id)"));
        assertTrue(migration.contains("idx_mat_vote_tick_jiacn_question (jiacn, question_id)"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("PREPARE wxdv_stmt"));
        assertFalse(migration.toUpperCase().contains("DELETE FROM MAT_VOTE_TICK"));
        assertFalse(migration.toUpperCase().contains("TRUNCATE"));
        assertFalse(migration.toUpperCase().contains("DROP TABLE"));
    }

    @Test
    void identityAndPointQueriesUseIndexableEqualityPlusByteExactGuards() throws Exception {
        String mpUser = source("src/main/resources/cn/jia/wx/mapper/MpUserMapper.xml");
        String userPoint = source("../../user/jia-user-mapper/src/main/resources/cn/jia/user/mapper/InfoMapper.xml");

        assertTrue(mpUser.contains("WHERE appid = #{appid"));
        assertTrue(mpUser.contains("AND open_id = #{openId"));
        assertTrue(mpUser.contains("CAST(open_id AS BINARY)"));
        assertTrue(mpUser.contains("LIMIT 2"));
        assertTrue(userPoint.contains("SET point = COALESCE(point, 0) + #{add"));
        assertTrue(userPoint.contains("CAST(jiacn AS BINARY)"));
        assertFalse(userPoint.contains("SET point = #{point"));
    }

    @Test
    void isolatedMysqlProbeRequiresOwnedNonProductionInstance() throws Exception {
        String probe = source("src/test/scripts/wx-daily-vote-opt-mysql-probe.sh");

        assertTrue(probe.contains("MYSQL_SOCKET=${MYSQL_SOCKET:?"));
        assertTrue(probe.contains("MYSQL_EXPECTED_DATADIR=${MYSQL_EXPECTED_DATADIR:?"));
        assertTrue(probe.contains("refusing standard MySQL port"));
        assertTrue(probe.contains("^wx_daily_vote_probe_"));
        assertFalse(probe.contains("-h 127.0.0.1"));
        assertFalse(probe.contains("DROP DATABASE IF EXISTS cyf"));
    }

    private String source(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
