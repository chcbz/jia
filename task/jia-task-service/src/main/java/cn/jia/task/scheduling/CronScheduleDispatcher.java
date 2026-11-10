package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.entity.CronScheduleRow;
import cn.jia.task.job.JobRegistry;
import cn.jia.task.mapper.CronScheduleMapper;
import cn.jia.task.mapper.ScheduledWorkMapper;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** Advance cursor + create one immutable occurrence + arm next wake in the SAME database transaction.
 * No business callback/provider I/O here. Recovery uses the existing task owner/claim machinery, not a second timer.
 */
public final class CronScheduleDispatcher implements CronScheduleService, ScheduledJobHandler {
    public static final String HANDLER = "task.cron-dispatch";
    private final CronScheduleMapper mapper;
    private final ScheduledWorkMapper clock;
    private final ScheduleService schedules;
    private final FixedTenantAdmissionPolicy scope;
    private final JobRegistry registry;
    private final TransactionTemplate transactions;

    public CronScheduleDispatcher(CronScheduleMapper mapper, ScheduledWorkMapper clock, ScheduleService schedules,
            FixedTenantAdmissionPolicy scope, JobRegistry registry, PlatformTransactionManager manager) {
        this.mapper=mapper;this.clock=clock;this.schedules=schedules;this.scope=scope;this.registry=registry;
        this.transactions=new TransactionTemplate(manager);
    }
    @Override public String getName() { return HANDLER; }

    @Override public void register(CronSchedule definition) {
        scope.tenantForClient(definition.clientId());
        if (HANDLER.equals(definition.handler()) || !(registry.getHandler(definition.handler()) instanceof ScheduledJobHandler))
            throw new IllegalArgumentException("Cron target must explicitly support durable scheduled execution");
        Objects.requireNonNull(definition.targetNode(), "Cron node is required; empty means cluster");
        Objects.requireNonNull(definition.misfire(), "Cron misfire policy is required");
        // Parse before entering the transaction; invalid configuration cannot leave partially registered work.
        var expression=CronExpression.parse(definition.expression());var zone=ZoneId.of(definition.zoneId());
        transactions.executeWithoutResult(status -> {
            long now=clock.nowMillis();
            Long first=next(expression,zone,now);
            if(first==null)throw new IllegalArgumentException("Cron expression has no future occurrence");
            mapper.ensure(scope.businessTenantId(),definition,first);
            var row=lock(definition.clientId(),definition.handler(),definition.targetNode());
            Long due=row.getNextFireAt();
            if(!row.isEnabled() || !definition.equals(row.definition())) {
                due=first;save(definition,true,due);
                schedules.reschedule(wake(definition),due);
            } else if(due!=null) {
                // Existing overdue work is retained; restart must not turn it into a new occurrence.
                schedules.signal(wake(definition),due);
            }
        });
    }

    @Override public void cancel(String client,String handler,String node) {
        scope.tenantForClient(client);
        transactions.executeWithoutResult(status -> {
            var row=lock(client,handler,node);
            if(row==null)return;
            save(row.definition(),false,null);
            schedules.cancel(wake(row.definition()));
        });
    }

    @Override public void executeScheduled(ScheduledInvocation invocation) {
        scope.requireAdmitted(invocation.tenantId(),invocation.key().clientId());
        if(!HANDLER.equals(invocation.key().handler()))throw new IllegalArgumentException("Wrong cron dispatch key");
        transactions.executeWithoutResult(status -> {
            var row=lock(invocation.key().clientId(),invocation.key().businessKey(),invocation.key().targetNode());
            if(row==null || !row.isEnabled() || row.getNextFireAt()==null)return;
            var definition=row.definition();long now=clock.nowMillis();long due=row.getNextFireAt();
            if(due>now) {schedules.reschedule(wake(definition),due);return;}
            var expression=CronExpression.parse(definition.expression());var zone=ZoneId.of(definition.zoneId());
            Long following=next(expression,zone,due);
            boolean missed=following!=null && following<=now;
            // No backlog-sized loop. Coalescing emits one stable original occurrence even after a long outage.
            if(!missed || definition.misfire()==CronSchedule.Misfire.COALESCE)
                schedules.signal(definition.occurrence(due),due);
            Long future=next(expression,zone,now);
            save(definition,true,future);
            if(future==null)schedules.cancel(wake(definition));
            else schedules.reschedule(wake(definition),future);
        });
    }
    private CronScheduleRow lock(String client,String handler,String node) {
        return mapper.lock(scope.businessTenantId(),client,handler,node);
    }
    private void save(CronSchedule definition,boolean enabled,Long next) {
        if(mapper.replace(scope.businessTenantId(),definition,enabled,next)!=1)
            throw new IllegalStateException("Cron cursor was not updated");
    }
    private static ScheduleKey wake(CronSchedule definition) {
        return new ScheduleKey(definition.clientId(),HANDLER,definition.handler(),definition.targetNode());
    }
    private static Long next(CronExpression expression,ZoneId zone,long after) {
        var next=expression.next(Instant.ofEpochMilli(after).atZone(zone));
        return next==null?null:next.toInstant().toEpochMilli();
    }
}
