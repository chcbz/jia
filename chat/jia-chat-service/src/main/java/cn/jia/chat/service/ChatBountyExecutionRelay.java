package cn.jia.chat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in durable consumer; a crash after execution insert resumes the same intent ID. */
@Slf4j
@Component
@ConditionalOnProperty(prefix="chat.bounty-execution",name="enabled",havingValue="true")
public class ChatBountyExecutionRelay implements SmartLifecycle, AutoCloseable {
    private final ChatBountyExecutionCoordinator coordinator;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile String previousFailure;
    private volatile int failureCount;

    public ChatBountyExecutionRelay(ChatBountyExecutionCoordinator coordinator) {
        this.coordinator=Objects.requireNonNull(coordinator);
    }
    @Override public synchronized void start() {
        if (!running.compareAndSet(false,true)) return;
        scheduler=Executors.newSingleThreadScheduledExecutor(r->{
            Thread thread=new Thread(r,"chat-bounty-execution"); thread.setDaemon(true); return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safePoll,0,5,TimeUnit.SECONDS);
    }
    void pollOnce() {
        for (var candidate:coordinator.pending(16)) coordinator.coordinate(candidate);
    }
    private void safePoll() {
        if (!running.get()) return;
        try { pollOnce(); previousFailure=null; failureCount=0; }
        catch (RuntimeException failure) {
            String signature=failure.getClass().getName()+":"+failure.getMessage();
            failureCount=Objects.equals(signature,previousFailure)?failureCount+1:1;
            previousFailure=signature;
            log.warn("Bounty execution coordination unavailable (no paid execution retried)",failure);
            if (failureCount>=2) { log.error("Bounty execution relay halted on unchanged failure"); stop(); }
        }
    }
    @Override public synchronized void stop() {
        running.set(false);
        var active=scheduler; scheduler=null;
        if (active!=null) active.shutdown();
    }
    @Override public boolean isRunning(){return running.get();}
    @Override public void close(){stop();}
}
