package cn.jia.wx.dailyvote;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WxDailyVoteSchemaSupportTest {

    @Test
    void recognizesNestedMysqlMissingMessageReceiptTable() {
        SQLException sql = new SQLException(
                "Table 'jia.wx_daily_vote_message_receipt' doesn't exist", "42S02", 1146);
        RuntimeException wrapped = new RuntimeException("mapper failed", sql);

        assertTrue(WxDailyVoteSchemaSupport.isMissingReceiptSchema(wrapped));
    }

    @Test
    void doesNotMaskMissingUnrelatedTable() {
        SQLException sql = new SQLException("Table 'jia.wx_mp_user' doesn't exist", "42S02", 1146);

        assertFalse(WxDailyVoteSchemaSupport.isMissingReceiptSchema(sql));
    }
}
