package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveSourceArtifactReconcilerTest {
    private static final Instant NOW=Instant.parse("2026-10-04T05:00:00Z");
    private static ArchiveMaintenanceStore.SourceArtifact artifact(String state,long revision) {
        return new ArchiveMaintenanceStore.SourceArtifact("src-a","0","client-a","owner-a","key-a",
                "cyf-artifact://"+"a".repeat(64)+"/"+"b".repeat(64),"b".repeat(64),7,
                "text/plain",state,revision,NOW.minusSeconds(120),NOW.minusSeconds(120));
    }
    private static ArchiveTransactions transactions() {
        return new ArchiveTransactions(){ @Override public <T> T required(Supplier<T> action){ return action.get(); } };
    }
    private static ArchiveMaintenanceProperties enabled() {
        var properties=new ArchiveMaintenanceProperties(); properties.setSourceCleanupEnabled(true);
        properties.setSourceCleanupStaleMillis(1000); properties.setSourceCleanupBatchSize(10); return properties;
    }

    @Test void durableUnreferencedCandidateIsClaimedDeletedAndFinalized() {
        var store=mock(ArchiveMaintenanceStore.class); var storage=mock(AgentTaskArtifactStorage.class);
        when(store.lockSourceArtifactOperation(any())).thenReturn(new ArchiveMaintenanceStore.Operation(
                false,"POST","/source","a".repeat(64),"SOURCE","src-a","PENDING"));
        var pending=artifact("PENDING",1); var claimed=artifact("DELETE_PENDING",2);
        when(store.listStaleSourceArtifacts(any(),isNull(),eq(10))).thenReturn(List.of(pending));
        when(store.findSourceArtifact("src-a",true)).thenReturn(pending,claimed,claimed);
        when(store.claimSourceArtifactDeletion(eq("src-a"),eq(1L),any())).thenReturn(1);
        when(storage.matches(any(),eq(claimed.storageUri()),eq(claimed.sha256()))).thenReturn(true);
        when(storage.delete(any(),eq(claimed.storageUri()),eq(claimed.sha256()))).thenReturn(true);
        when(store.completeSourceArtifactDeletion("src-a",2)).thenReturn(1);
        new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),
                Clock.fixed(NOW, ZoneOffset.UTC)).reconcile();
        verify(storage).delete(eq(new AgentTaskArtifactStorage.Scope("0","client-a","owner-a","src-a")),
                eq(claimed.storageUri()),eq(claimed.sha256()));
        verify(store).completeSourceArtifactDeletion("src-a",2);
    }

    @Test void deletedTombstoneIsReclaimedAgainWhenALateUploadReappears() {
        var store=mock(ArchiveMaintenanceStore.class); var storage=mock(AgentTaskArtifactStorage.class);
        when(store.lockSourceArtifactOperation(any())).thenReturn(new ArchiveMaintenanceStore.Operation(
                false,"POST","/source","a".repeat(64),"SOURCE","src-a","PENDING"));
        var deleted=artifact("DELETED",3); var claimed=artifact("DELETE_PENDING",4);
        when(store.listStaleSourceArtifacts(any(),isNull(),eq(10))).thenReturn(List.of(deleted));
        when(store.findSourceArtifact("src-a",true)).thenReturn(deleted,claimed,claimed);
        when(store.claimSourceArtifactDeletion(eq("src-a"),eq(3L),any())).thenReturn(1);
        when(storage.matches(any(),eq(claimed.storageUri()),eq(claimed.sha256()))).thenReturn(true);
        when(storage.delete(any(),eq(claimed.storageUri()),eq(claimed.sha256()))).thenReturn(true);
        when(store.completeSourceArtifactDeletion("src-a",4)).thenReturn(1);
        new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),
                Clock.fixed(NOW, ZoneOffset.UTC)).reconcile();
        verify(storage).delete(any(),eq(claimed.storageUri()),eq(claimed.sha256()));
        verify(store).completeSourceArtifactDeletion("src-a",4);
    }

    @Test void lateReferenceOrOperationChangePreventsExternalDelete() {
        var store=mock(ArchiveMaintenanceStore.class); var storage=mock(AgentTaskArtifactStorage.class);
        when(store.lockSourceArtifactOperation(any())).thenReturn(new ArchiveMaintenanceStore.Operation(
                false,"POST","/source","a".repeat(64),"SOURCE","src-a","PENDING"));
        var pending=artifact("PENDING",1);
        when(store.listStaleSourceArtifacts(any(),isNull(),eq(10))).thenReturn(List.of(pending));
        when(store.findSourceArtifact("src-a",true)).thenReturn(pending);
        when(store.claimSourceArtifactDeletion(eq("src-a"),eq(1L),any())).thenReturn(0);
        new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),
                Clock.fixed(NOW, ZoneOffset.UTC)).reconcile();
        verify(storage,never()).delete(any(),anyString(),anyString());
        verify(store,never()).completeSourceArtifactDeletion(anyString(),anyLong());
    }
    @Test void twoInstancesLateDeleteOnlyOldGenerationAndItsLateUploadIsRescanned() throws Exception {
        var store=mock(ArchiveMaintenanceStore.class);var storage=mock(AgentTaskArtifactStorage.class);
        var rows=new java.util.concurrent.ConcurrentHashMap<String,ArchiveMaintenanceStore.SourceArtifact>();
        var pending=artifact("PENDING",1);rows.put("src-a",pending);
        var objects=new java.util.concurrent.ConcurrentHashMap<String,byte[]>();
        objects.put(pending.storageUri(),"payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(store.listStaleSourceArtifacts(any(),nullable(String.class),eq(10))).thenAnswer(call ->
                rows.values().stream().filter(row -> !"REFERENCED".equals(row.state())).toList());
        when(store.lockSourceArtifactOperation(any())).thenAnswer(call -> new ArchiveMaintenanceStore.Operation(
                false,"POST","/source","a".repeat(64),"SOURCE",rows.containsKey("src-b")?"src-b":"src-a",
                rows.containsKey("src-b")?"COMMITTED":"PENDING"));
        when(store.findSourceArtifact(anyString(),eq(true))).thenAnswer(call -> rows.get(call.getArgument(0)));
        when(store.claimSourceArtifactDeletion(eq("src-a"),anyLong(),any())).thenAnswer(call -> {
            var row=rows.get("src-a");long expected=call.getArgument(1);
            if(row.revision()!=expected || !("PENDING".equals(row.state())||"DELETED".equals(row.state()))) return 0;
            rows.put("src-a",artifact("DELETE_PENDING",expected+1));return 1;
        });
        when(store.completeSourceArtifactDeletion(eq("src-a"),anyLong())).thenAnswer(call -> {
            var row=rows.get("src-a");long expected=call.getArgument(1);
            if(row.revision()!=expected || !"DELETE_PENDING".equals(row.state())) return 0;
            rows.put("src-a",artifact("DELETED",expected+1));return 1;
        });
        when(storage.matches(any(),eq(pending.storageUri()),eq(pending.sha256()))).thenReturn(true);
        var aEntered=new java.util.concurrent.CountDownLatch(1);var bEntered=new java.util.concurrent.CountDownLatch(1);
        var allowA=new java.util.concurrent.CountDownLatch(1);var allowB=new java.util.concurrent.CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(storage.delete(any(),eq(pending.storageUri()),eq(pending.sha256()))).thenAnswer(call -> {
            assertEquals(new AgentTaskArtifactStorage.Scope("0","client-a","owner-a","src-a"),call.getArgument(0));
            int number=calls.incrementAndGet();
            if(number==1) { aEntered.countDown();assertTrue(allowA.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
            if(number==2) { bEntered.countDown();assertTrue(allowB.await(5,java.util.concurrent.TimeUnit.SECONDS)); }
            return objects.remove(pending.storageUri())!=null;
        });
        var a=new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),Clock.fixed(NOW,ZoneOffset.UTC));
        var b=new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),Clock.fixed(NOW,ZoneOffset.UTC));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(a::reconcile);assertTrue(aEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var late=pool.submit(b::reconcile);assertTrue(bEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            allowA.countDown();first.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("DELETED",rows.get("src-a").state());
            var next=new ArchiveMaintenanceStore.SourceArtifact("src-b","0","client-a","owner-a","key-a",
                    "cyf-artifact://"+"c".repeat(64)+"/"+pending.sha256(),pending.sha256(),7,"text/plain",
                    "REFERENCED",2,NOW,NOW);
            rows.put("src-b",next);objects.put(next.storageUri(),"payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            allowB.countDown();late.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(objects.containsKey(next.storageUri()));assertEquals("REFERENCED",rows.get("src-b").state());
            objects.put(pending.storageUri(),"payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            new ArchiveSourceArtifactReconciler(store,transactions(),storage,enabled(),Clock.fixed(NOW,ZoneOffset.UTC)).reconcile();
            assertFalse(objects.containsKey(pending.storageUri()));assertTrue(objects.containsKey(next.storageUri()));
            assertEquals("DELETED",rows.get("src-a").state());assertEquals(2,rows.size());
        } finally { allowA.countDown();allowB.countDown();pool.shutdownNow();pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS); }
    }

}
