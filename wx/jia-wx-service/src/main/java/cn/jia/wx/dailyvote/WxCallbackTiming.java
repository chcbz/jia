package cn.jia.wx.dailyvote;

import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

public final class WxCallbackTiming {

    private static final long SLOW_MILLIS = 1_000L;

    private final long startedNanos = System.nanoTime();
    private long signatureNanos;
    private long identityNanos;
    private long redisNanos;
    private long replayNanos;
    private long transactionNanos;
    private long replyNanos;
    private String appid = "unknown";
    private String messageType = "unknown";
    private String trace = "none";

    public long startStage() {
        return System.nanoTime();
    }

    public void signature(long stageStart) {
        signatureNanos += elapsed(stageStart);
    }

    public void identity(long stageStart) {
        identityNanos += elapsed(stageStart);
    }

    public void redis(long stageStart) {
        redisNanos += elapsed(stageStart);
    }

    public void replay(long stageStart) {
        replayNanos += elapsed(stageStart);
    }

    public void transaction(long stageStart) {
        transactionNanos += elapsed(stageStart);
    }

    public void reply(long stageStart) {
        replyNanos += elapsed(stageStart);
    }

    public void appid(String appid) {
        if (appid != null && !appid.isBlank()) {
            this.appid = appid;
        }
    }

    public void messageType(String messageType) {
        if (messageType != null && !messageType.isBlank()) {
            this.messageType = messageType;
        }
    }

    public void trace(String trace) {
        if (trace != null && !trace.isBlank()) {
            this.trace = trace;
        }
    }

    public void log(Logger logger) {
        long totalMillis = millis(System.nanoTime() - startedNanos);
        Object[] args = {appid, messageType, trace, millis(signatureNanos), millis(identityNanos),
                millis(redisNanos), millis(replayNanos), millis(transactionNanos),
                millis(replyNanos), totalMillis};
        String template = "Wx callback timing: appid={}, type={}, trace={}, signatureMs={}, identityMs={}, "
                + "redisMs={}, replayMs={}, voteTransactionMs={}, replyMs={}, totalMs={}";
        if (totalMillis >= SLOW_MILLIS) {
            logger.warn(template, args);
        } else {
            logger.debug(template, args);
        }
    }

    private long elapsed(long stageStart) {
        return System.nanoTime() - stageStart;
    }

    private long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
