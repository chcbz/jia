package cn.jia.chat.service;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import cn.jia.agent.service.NativeConversationWorkDiscovery;
import cn.jia.chat.handler.AgentWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One recovery scan per WS node, restricted to its current subscriptions. No queue authority. */
@Slf4j
@Component
@ConditionalOnProperty(name="jia.agent.conversation-execution.enabled",havingValue="true")
public final class NativeConversationWorkNotifier implements SmartLifecycle,AutoCloseable {
    private final AgentWebSocketHandler sockets;
    private final NativeConversationWorkDiscovery discovery;
    private final Set<NativeConversationWorkAvailable> dirty=new HashSet<>();
    private final Supplier<ScheduledExecutorService> workers;
    private ScheduledExecutorService worker;
    private boolean drainScheduled;
    @Autowired
    public NativeConversationWorkNotifier(AgentWebSocketHandler sockets,NativeConversationWorkDiscovery discovery) {
        this(sockets,discovery,()->Executors.newSingleThreadScheduledExecutor(r->{
            var t=new Thread(r,"native-work-notifications");t.setDaemon(true);return t;}));
    }
    NativeConversationWorkNotifier(AgentWebSocketHandler sockets,NativeConversationWorkDiscovery discovery,
            Supplier<ScheduledExecutorService> workers) {
        this.sockets=sockets;this.discovery=discovery;this.workers=workers;
    }
    @Override public synchronized void start() {
        if(worker!=null)return;
        var owner=workers.get();
        worker=owner;
        owner.scheduleWithFixedDelay(()->{
            synchronized(this){if(worker!=owner)return;}
            try {recover();} catch(RuntimeException failure){log.warn("Native work notification recovery unavailable",failure);}
        },30,30,TimeUnit.SECONDS);
    }
    @EventListener public synchronized void available(NativeConversationWorkAvailable target) {
        if(worker==null || !sockets.nativeWorkTargets().contains(target))return;
        dirty.add(target);
        if(drainScheduled)return;
        drainScheduled=true;
        var owner=worker;
        owner.execute(()->drain(owner));
    }
    private void drain(ScheduledExecutorService owner) {
        Set<NativeConversationWorkAvailable> targets;
        synchronized(this){
            if(worker!=owner)return;
            targets=Set.copyOf(dirty);dirty.clear();drainScheduled=false;
        }
        for(var target:targets) {
            try {sockets.notifyNativeWork(target);}
            catch(RuntimeException failure){log.warn("Native work hint failed; recovery retained",failure);}
        }
    }
    void recover() {
        var targets=sockets.nativeWorkTargets();
        if(targets.isEmpty())return;
        for(var pending:discovery.pendingFor(targets)) {
            try {sockets.notifyNativeWork(pending);}
            catch(RuntimeException failure){log.warn("Native work recovery hint failed; remaining targets retained",failure);}
        }
    }
    @Override public synchronized void stop(){var old=worker;worker=null;dirty.clear();drainScheduled=false;if(old!=null)old.shutdown();}
    @Override public synchronized boolean isRunning(){return worker!=null;}
    @Override public void close(){stop();}
}
