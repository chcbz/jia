package cn.jia.chat.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FinalReplyTimingTest {
    @FunctionalInterface interface Action { void run() throws Exception; }

    static List<String> capture(Action action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AgentWebSocketHandler.class);
        Level original = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender); logger.setLevel(Level.INFO);
        try {
            action.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.startsWith("CHAT_FINAL_TIMING ")).toList();
        } finally {
            logger.detachAppender(appender); logger.setLevel(original); appender.stop();
        }
    }

    static long offset(String summary, String key) {
        return Long.parseLong(summary.split(key + "=")[1].split(" ")[0]);
    }

    static void assertCompleted(String summary, String result, boolean sent) {
        assertTrue(summary.contains("turnId=turn-1 "));
        assertTrue(summary.contains("result=" + result + " "));
        assertTrue(summary.contains("sendReturned=" + sent + " "));
        long last = 0;
        for (String key : List.of("decodedUs", "authorizedUs", "persistStartUs", "persistEndUs",
                "hostedStartUs", "hostedEndUs", "sendStartUs", "sendLockUs", "sendEndUs", "totalUs")) {
            long value = offset(summary, key);
            assertTrue(value >= last, key + " must have a monotonic offset");
            last = value;
        }
        assertFalse(summary.contains("token-001"));
        assertFalse(summary.contains("content="));
        assertFalse(summary.contains("snapshot-1"));
    }

    @Test void unreachedStagesAreUnknownAndCorrelationIsBoundedSingleLine() throws Exception {
        String raw = "turn\n\r" + "x".repeat(200);
        var timing = new AgentWebSocketHandler.FinalReplyTiming(System.nanoTime(), 1234, raw);
        List<String> logs = capture(timing::writeSummary);
        assertEquals(1, logs.size());
        String line = logs.getFirst();
        assertFalse(line.contains("\n")); assertFalse(line.contains("\r"));
        assertTrue(line.contains("turnId=turn__" + "x".repeat(122) + " "));
        assertTrue(line.contains("startedAt=1234 result=REJECTED "));
        assertEquals(-1, offset(line, "persistStartUs"));
        assertEquals(-1, offset(line, "sendEndUs"));
        assertTrue(line.contains("sendReturned=false "));
        assertTrue(offset(line, "totalUs") >= 0);
    }
}
