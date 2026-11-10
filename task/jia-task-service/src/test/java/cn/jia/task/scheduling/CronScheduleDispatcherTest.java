package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.entity.CronScheduleRow;
import cn.jia.task.job.JobRegistry;
import cn.jia.task.mapper.CronScheduleMapper;
import cn.jia.task.mapper.ScheduledWorkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CronScheduleDispatcherTest {
    final CronScheduleMapper mapper=mock(CronScheduleMapper.class);
    final ScheduledWorkMapper clock=mock(ScheduledWorkMapper.class);
    final ScheduleService schedules=mock(ScheduleService.class);
    final JobRegistry registry=new JobRegistry();
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final CronSchedule spec=new CronSchedule("client","hourly","","0 0 * * * *","UTC",CronSchedule.Misfire.COALESCE);
    final long hour=Instant.parse("2026-11-10T10:00:00Z").toEpochMilli();
    final ScheduleKey wake=new ScheduleKey("client",CronScheduleDispatcher.HANDLER,"hourly","");
    CronScheduleRow row;
    CronScheduleDispatcher dispatcher;
    @BeforeEach void setup() {
        var target=mock(ScheduledJobHandler.class);when(target.getName()).thenReturn("hourly");registry.register(target);clearInvocations(target);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        row=new CronScheduleRow();row.setTenantId("tenant");row.setClientId("client");row.setHandler("hourly");row.setTargetNode("");
        row.setExpression(spec.expression());row.setZoneId("UTC");row.setMisfire("COALESCE");row.setEnabled(true);row.setNextFireAt(hour);
        when(mapper.lock("tenant","client","hourly","")).thenReturn(row);
        when(mapper.replace(anyString(),any(),anyBoolean(),nullable(Long.class))).thenReturn(1);
        when(clock.nowMillis()).thenReturn(hour+1000);
        dispatcher=new CronScheduleDispatcher(mapper,clock,schedules,new FixedTenantAdmissionPolicy("tenant","client"),registry,manager);
    }
    void fire() {dispatcher.executeScheduled(new ScheduledInvocation("tenant",wake,1));}
    @Test void tenantMaintenanceUsesExplicitEmptyClientWithoutWeakeningExternalAdmission() {
        var scope = new FixedTenantAdmissionPolicy("tenant", null);
        dispatcher = new CronScheduleDispatcher(mapper,clock,schedules,scope,registry,manager);
        row.setClientId("");
        when(mapper.lock("tenant","","hourly","")).thenReturn(row);
        var maintenance = new CronSchedule("","hourly","",spec.expression(),"UTC",spec.misfire());
        dispatcher.register(maintenance);
        var maintenanceWake = new ScheduleKey("",CronScheduleDispatcher.HANDLER,"hourly","");
        dispatcher.executeScheduled(new ScheduledInvocation("tenant",maintenanceWake,1));
        verify(schedules).signal(maintenance.occurrence(hour),hour);
        assertThrows(IllegalArgumentException.class, () -> scope.tenantForClient(""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.executeScheduled(
                new ScheduledInvocation("foreign",maintenanceWake,1)));
    }
    @Test void restrictedProcessRejectsMaintenanceAndNullClientBeforeSql() {
        var maintenance = new CronSchedule("","hourly","",spec.expression(),"UTC",spec.misfire());
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(maintenance));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.cancel("","hourly",""));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.executeScheduled(
                new ScheduledInvocation("tenant",new ScheduleKey("",CronScheduleDispatcher.HANDLER,"hourly",""),1)));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(
                new CronSchedule(null,"hourly","",spec.expression(),"UTC",spec.misfire())));
        verifyNoInteractions(mapper,schedules);
    }
    @Test void restartPreservesOriginalOverdueCursor() {
        dispatcher.register(spec);verify(schedules).signal(wake,hour);verify(mapper,never()).replace(any(),any(),anyBoolean(),any());
    }
    @Test void dueOccurrenceUsesScheduledTimeNotRevisionOrDispatchTime() {
        fire();verify(schedules).signal(spec.occurrence(hour),hour);
        verify(mapper).replace("tenant",spec,true,hour+3600000);verify(schedules).reschedule(wake,hour+3600000);
        verifyNoInteractions(registry.getHandler("hourly"));
    }
    @Test void longDowntimeCoalescesOnceWithoutEnumeratingMissedSlots() {
        when(clock.nowMillis()).thenReturn(hour+10*3600000+1000);
        fire();verify(schedules).signal(spec.occurrence(hour),hour);verify(schedules).reschedule(wake,hour+11*3600000);
    }
    @Test void skipDropsOnlyAnOccurrenceWhoseSuccessorIsAlsoDue() {
        row.setMisfire("SKIP");when(clock.nowMillis()).thenReturn(hour+3600000);
        fire();verify(schedules,never()).signal(any(),anyLong());verify(schedules).reschedule(wake,hour+2*3600000);
    }
    @Test void skipDoesNotTreatNormalQueueLatencyAsMissed() {
        row.setMisfire("SKIP");fire();verify(schedules).signal(spec.occurrence(hour),hour);
    }
    @Test void earlyCallbackOnlyArmsExistingFutureDeadline() {
        when(clock.nowMillis()).thenReturn(hour-1);fire();verify(schedules).reschedule(wake,hour);
        verify(schedules,never()).signal(any(),anyLong());verify(mapper,never()).replace(any(),any(),anyBoolean(),any());
    }
    @Test void canceledCursorCannotRecreateAnOccurrence() {
        row.setEnabled(false);fire();verifyNoInteractions(schedules);
        dispatcher.register(spec);verify(mapper).replace("tenant",spec,true,hour+3600000);
    }
    @Test void invalidDefinitionOrLegacyMetadataCannotActivateCron() {
        assertThrows(RuntimeException.class,()->dispatcher.register(new CronSchedule("client","hourly","","invalid","UTC",spec.misfire())));
        assertThrows(RuntimeException.class,()->dispatcher.register(new CronSchedule("client","hourly","",spec.expression(),"not/a-zone",spec.misfire())));
        assertThrows(IllegalArgumentException.class,()->dispatcher.register(new CronSchedule("other","hourly","",spec.expression(),"UTC",spec.misfire())));
        registry.unregister("hourly");
        var legacy=mock(cn.jia.task.job.JobHandler.class);when(legacy.getName()).thenReturn("hourly");when(legacy.getCron()).thenReturn(spec.expression());registry.register(legacy);
        assertThrows(IllegalArgumentException.class,()->dispatcher.register(spec));
        verifyNoInteractions(mapper,schedules);
    }
    @Test void explicitZoneDeterminesFirstOccurrenceAndConfigChangeReplacesFutureCursor() {
        var changed=new CronSchedule("client","hourly","","0 30 7 * * *","Asia/Shanghai",spec.misfire());
        dispatcher.register(changed);
        long next=Instant.parse("2026-11-10T23:30:00Z").toEpochMilli();
        verify(mapper).replace("tenant",changed,true,next);verify(schedules).reschedule(wake,next);
    }
    @Test void daylightSavingRepeatedLocalHourKeepsDistinctInstantOccurrences() {
        row.setExpression("0 30 1 * * *");row.setZoneId("America/Los_Angeles");
        long first=Instant.parse("2026-11-01T08:30:00Z").toEpochMilli();
        row.setNextFireAt(first);when(clock.nowMillis()).thenReturn(first+1000);
        fire();verify(schedules).signal(row.definition().occurrence(first),first);
        verify(schedules).reschedule(wake,Instant.parse("2026-11-01T09:30:00Z").toEpochMilli());
    }
    @Test void failedQueueWritePropagatesThroughOriginalTransaction() {
        doThrow(new IllegalStateException("queue unavailable")).when(schedules).signal(any(),anyLong());
        assertThrows(IllegalStateException.class,this::fire);verify(manager).rollback(any());
        verify(mapper,never()).replace(any(),any(),anyBoolean(),any());
    }
    @Test void cancelDoesNotCancelAlreadyAcceptedBusinessOccurrences() {
        dispatcher.cancel("client","hourly","");verify(mapper).replace("tenant",spec,false,null);
        verify(schedules).cancel(wake);verifyNoMoreInteractions(schedules);
    }
}
