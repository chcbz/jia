package cn.jia.task.job;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JobRegistryTest {
    private JobHandler handler(String name) {
        return new JobHandler() {
            public String getName() { return name; }
            public String execute(JobContext context) { return null; }
        };
    }
    @Test void registeringSameInstanceIsIdempotent() {
        var registry=new JobRegistry();var handler=handler("test");
        registry.register(handler);registry.register(handler);
        assertSame(handler,registry.getHandler("test"));assertEquals(1,registry.getHandlerCount());
    }
    @Test void duplicateDoesNotReplaceExistingHandlerIncludingAlias() {
        var registry=new JobRegistry();var first=handler("test");registry.register(first);
        assertThrows(IllegalStateException.class,()->registry.register(handler("test")));
        assertThrows(IllegalStateException.class,()->registry.register("test",handler("other")));
        assertSame(first,registry.getHandler("test"));
    }
    @Test void concurrentRegistrationHasOnlyOneWinner() throws Exception {
        var registry=new JobRegistry();var successes=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Void> register=()->{start.await();try{registry.register(handler("same"));successes.incrementAndGet();}
                catch(IllegalStateException expected){}return null;};
            var a=pool.submit(register);var b=pool.submit(register);start.countDown();
            a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,successes.get());assertEquals(1,registry.getHandlerCount());
    }
}
