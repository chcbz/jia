package cn.jia.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NativeConversationWorkNotifierTest {
    final cn.jia.chat.handler.AgentWebSocketHandler sockets=mock(cn.jia.chat.handler.AgentWebSocketHandler.class);
    final cn.jia.agent.service.NativeConversationWorkDiscovery discovery=mock(cn.jia.agent.service.NativeConversationWorkDiscovery.class);
    final ScheduledExecutorService worker=mock(ScheduledExecutorService.class);
    final cn.jia.agent.entity.NativeConversationWorkAvailable local=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client","local");
    final cn.jia.agent.entity.NativeConversationWorkAvailable remote=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client","remote");
    final List<Runnable> drains=new ArrayList<>();
    final cn.jia.task.scheduling.CronScheduleService schedules=mock(cn.jia.task.scheduling.CronScheduleService.class);
    NativeConversationWorkRecovery recovery;
    NativeConversationWorkNotifier notifier;
    @BeforeEach void setupNotifier() {
        when(sockets.nativeWorkTargets()).thenReturn(List.of(local));
        doAnswer(call->{drains.add(call.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
        notifier=new NativeConversationWorkNotifier(sockets,discovery,()->worker);notifier.start();
        recovery=new NativeConversationWorkRecovery(notifier,schedules,
                new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null),"node-a","0/30 * * * * *");
    }
    @Test void burstsCoalesceAndNotificationsDuringSendGetOneTail() {
        notifier.available(remote);
        for(int i=0;i<1000;i++)notifier.available(local);
        assertEquals(1,drains.size());verify(sockets,never()).notifyNativeWork(any());
        doAnswer(call->{for(int i=0;i<1000;i++)notifier.available(local);return null;})
            .doNothing().when(sockets).notifyNativeWork(local);
        drains.get(0).run();assertEquals(2,drains.size());drains.get(1).run();
        verify(sockets,times(2)).notifyNativeWork(local);verifyNoInteractions(discovery);
    }
    @Test void bothLanesUseOneDurableRecoveryAndOneQuery() {
        var file=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client","local","WORKSPACE_FILE");
        when(sockets.nativeWorkTargets()).thenReturn(List.of(local,file));
        when(discovery.pendingFor(List.of(local,file))).thenReturn(List.of(local,file));
        recover();
        verify(discovery).pendingFor(List.of(local,file));
        verify(sockets).notifyNativeWork(local);verify(sockets).notifyNativeWork(file);
        verify(worker,never()).scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any());
        for(int i=0;i<1000;i++){notifier.available(local);notifier.available(file);}
        assertEquals(1,drains.size());drains.get(0).run();
        verify(sockets,times(2)).notifyNativeWork(local);verify(sockets,times(2)).notifyNativeWork(file);
    }
    @Test void missedOrRemoteCommitIsRecoveredByOneLocalSubscriptionQuery() {
        when(discovery.pendingFor(List.of(local))).thenReturn(List.of(local));
        recover();verify(discovery).pendingFor(List.of(local));verify(sockets).notifyNativeWork(local);
        verify(sockets,never()).notifyNativeWork(remote);
    }
    @Test void noSubscribersMeansNoDatabaseWorkAndEmptyQueueMeansNoHint() {
        when(sockets.nativeWorkTargets()).thenReturn(List.of());recover();verifyNoInteractions(discovery);
        when(sockets.nativeWorkTargets()).thenReturn(List.of(local));
        when(discovery.pendingFor(List.of(local))).thenReturn(List.of());recover();
        verify(sockets,never()).notifyNativeWork(any());
    }
    @Test void stoppedCallbacksCannotDiscoverOrSendAndFailedScanCanRecover() {
        when(discovery.pendingFor(List.of(local))).thenThrow(new IllegalStateException("offline")).thenReturn(List.of(local));
        assertThrows(IllegalStateException.class,this::recover);recover();verify(sockets).notifyNativeWork(local);
        notifier.available(local);notifier.stop();drains.get(0).run();recover();
        verify(discovery,times(2)).pendingFor(any());verify(sockets,times(1)).notifyNativeWork(local);
        verify(worker).shutdown();assertFalse(notifier.isRunning());
    }

    void recover() {
        recovery.executeScheduled(new cn.jia.task.scheduling.ScheduledInvocation("0",
                new cn.jia.task.scheduling.ScheduleKey("",NativeConversationWorkRecovery.HANDLER,"cron:30000","node-a"),1));
    }
    @Test void startupRegistersDurableNodeRecoveryWithoutASecondTimerOrBusinessScan() {
        recovery.onApplicationEvent(null);
        verify(schedules).register(new cn.jia.task.scheduling.CronSchedule("", NativeConversationWorkRecovery.HANDLER,
                "node-a", "0/30 * * * * *", "UTC", cn.jia.task.scheduling.CronSchedule.Misfire.COALESCE));
        verifyNoInteractions(discovery);
        verify(worker,never()).scheduleWithFixedDelay(any(Runnable.class),anyLong(),anyLong(),any());
    }
    @Test void clientScopedNodeCannotRegisterTenantWideOrClusterSubscriptionRecovery() {
        var scoped=new NativeConversationWorkRecovery(notifier,schedules,
                new cn.jia.core.security.FixedTenantAdmissionPolicy("0","client"),"node-b","0/30 * * * * *");
        scoped.onApplicationEvent(null);
        verify(schedules).register(new cn.jia.task.scheduling.CronSchedule("client",NativeConversationWorkRecovery.HANDLER,
                "node-b","0/30 * * * * *","UTC",cn.jia.task.scheduling.CronSchedule.Misfire.COALESCE));
        assertThrows(IllegalArgumentException.class,()->new NativeConversationWorkRecovery(notifier,schedules,
                new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null),"","0/30 * * * * *"));
    }
    @Test void recoveryBeanIsOptInWithoutStartingAnotherLocalTimer() {
        for (boolean enabled : List.of(false,true)) {
            try (var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "test", java.util.Map.of("cyf.task.scheduling.enabled",String.valueOf(enabled),
                                "cyf.task.scheduling.node-id","node-a")));
                context.registerBean(NativeConversationWorkNotifier.class,()->mock(NativeConversationWorkNotifier.class));
                context.registerBean(cn.jia.task.scheduling.CronScheduleService.class,()->schedules);
                context.registerBean(cn.jia.core.security.FixedTenantAdmissionPolicy.class,
                        ()->new cn.jia.core.security.FixedTenantAdmissionPolicy("0",null));
                context.register(NativeConversationWorkRecovery.class);
                context.refresh();
                assertEquals(enabled?1:0,context.getBeansOfType(NativeConversationWorkRecovery.class).size());
                verifyNoInteractions(schedules); // Startup event, not bean construction, owns registration.
            }
        }
    }

}
