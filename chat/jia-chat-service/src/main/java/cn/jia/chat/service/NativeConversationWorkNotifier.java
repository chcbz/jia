package cn.jia.chat.service;

import cn.jia.agent.entity.NativeConversationWorkAvailable;
import cn.jia.agent.service.NativeConversationWorkDiscovery;
import cn.jia.chat.handler.AgentWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Coalesced local WS hints. Durable recovery is registered by NativeConversationWorkRecovery.
 * Both paths use current local subscriptions; neither owns the business queue. */
@Slf4j
@Component
public final class NativeConversationWorkNotifier implements SmartLifecycle,AutoCloseable {
    private final AgentWebSocketHandler sockets;
    private final NativeConversationWorkDiscovery discovery;
    private final Set<NativeConversationWorkAvailable> dirty=new HashSet<>();
    private final Supplier<ExecutorService> workers;
    private ExecutorService worker;
    private boolean drainScheduled;
    @Autowired
    public NativeConversationWorkNotifier(AgentWebSocketHandler sockets,NativeConversationWorkDiscovery discovery) {
        this(sockets,discovery,()->Executors.newSingleThreadExecutor(r->{
            var t=new Thread(r,"native-work-notifications");t.setDaemon(true);return t;}));
    }
    NativeConversationWorkNotifier(AgentWebSocketHandler sockets,NativeConversationWorkDiscovery discovery,
            Supplier<ExecutorService> workers) {
        this.sockets=sockets;this.discovery=discovery;this.workers=workers;
    }
    @Override public synchronized void start() {
        if(worker!=null)return;
        worker=workers.get();
    }
    @EventListener public synchronized void available(NativeConversationWorkAvailable target) {
        if(worker==null || !sockets.nativeWorkTargets().contains(target))return;
        dirty.add(target);
        if(drainScheduled)return;
        drainScheduled=true;
        var owner=worker;
        owner.execute(()->drain(owner));
    }
    private void drain(ExecutorService owner) {
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
        synchronized (this) { if (worker == null) return; }
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
