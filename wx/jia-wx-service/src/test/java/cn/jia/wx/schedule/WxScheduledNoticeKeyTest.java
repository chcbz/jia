package cn.jia.wx.schedule;

import cn.jia.wx.schedule.WxScheduledNotice.Key;
import cn.jia.wx.schedule.WxScheduledNotice.Kind;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class WxScheduledNoticeKeyTest {
    @Test void stableRoundTripRetainsClientDayKindAndSubscription() {
        var key = new Key("client", LocalDate.of(2026,11,11), Kind.PHRASE, 7);
        assertEquals("2026-11-11:PHRASE:7", key.businessKey());
        assertEquals(key, Key.parse("client", key.businessKey()));
    }
    @Test void ambiguousOrMalformedKeysCannotSelectAnotherNotice() {
        for (String value : new String[]{"2026-11-11:PHRASE:07", "2026-11-11:PHRASE:0", "2026-11-11:PHRASE:+7",
                "2026-11-11:phrase:7", "2026-02-30:PHRASE:7", "2026-11-11:PHRASE:7:extra"})
            assertThrows(RuntimeException.class, () -> Key.parse("client", value));
        assertThrows(IllegalArgumentException.class, () -> Key.parse("", "2026-11-11:PHRASE:7"));
    }
}
