package cn.jia.chat.memory;

/** Business outcome journal, not another task queue. STARTED without a terminal outcome is
 * unresolved: the same cron occurrence must not blindly repeat provider calls after a crash. */
public interface MemoryJobRunStore {
    enum Outcome { STARTED, SUCCEEDED, FAILED, SKIPPED }
    record Run(long scheduledAt, String startToken, Outcome outcome, long startedAt, Long finishedAt) { }
    boolean start(long scheduledAt, String startToken, long startedAt);
    Run find(long scheduledAt);
    void finish(long scheduledAt, String startToken, Outcome outcome, long finishedAt);
}
