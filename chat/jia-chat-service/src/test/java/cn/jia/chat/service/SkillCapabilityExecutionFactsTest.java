package cn.jia.chat.service;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.SkillCapabilityLookup.*;
import cn.jia.chat.handler.SkillExecutionSessionRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillCapabilityExecutionFactsTest {
    final SessionFence fence = new SessionFence("installation", "host", "runtime", 1);
    final RuntimeSkill skill = new RuntimeSkill("skill", "1.4.0", "a".repeat(64), null, null,
            Readiness.NOT_EXECUTABLE, null, null, null, "install", "product", "INSTRUCTION", "1", true, 100L);
    View view(String session, SessionFence f, String revision, RuntimeSkill row) {
        return new View(State.AVAILABLE, session, f, new Catalog(revision, List.of(row)));
    }
    RuntimeSkill change(String field, Object value) throws Exception {
        var components = RuntimeSkill.class.getRecordComponents();
        var types = new Class<?>[components.length];
        var values = new Object[components.length];
        for (int i=0; i<components.length; i++) {
            types[i] = components[i].getType();
            values[i] = components[i].getName().equals(field) ? value : components[i].getAccessor().invoke(skill);
        }
        return RuntimeSkill.class.getDeclaredConstructor(types).newInstance(values);
    }
    @Test void newerHeartbeatDoesNotChangeActionsButRawObservationRemainsVisible() throws Exception {
        var before = view("session", fence, "1", skill);
        var after = view("session", fence, "1", change("observedAt", 101L));
        assertNotEquals(before, after); // Original full-record comparison rejected this valid heartbeat.
        assertTrue(before.sameExecutionFacts(after));
        assertTrue(after.sameExecutionFacts(before));
        assertEquals(101L, after.catalog().skills().getFirst().observedAt());
        assertFalse(before.sameExecutionFacts(null));
        assertFalse(before.sameExecutionFacts(View.unavailable(State.DISCONNECTED)));
        assertFalse(before.sameExecutionFacts(view("new-session", fence, "1", skill)));
        assertFalse(before.sameExecutionFacts(view("session", new SessionFence("installation","host","runtime",2), "1", skill)));
        assertFalse(before.sameExecutionFacts(view("session", fence, "2", skill)));
        assertFalse(before.sameExecutionFacts(new View(State.AVAILABLE,"session",fence,new Catalog("1",List.of()))));
    }
    @Test void everyNonTelemetrySkillFieldStillChangesExecutionFacts() throws Exception {
        var mutations = new java.util.LinkedHashMap<String,Object>();
        mutations.put("skillKey","other"); mutations.put("skillVersion","1.5.0"); mutations.put("packageSha256","b".repeat(64));
        mutations.put("manifestSha256","c".repeat(64)); mutations.put("runner","node"); mutations.put("readiness",Readiness.UNKNOWN);
        mutations.put("reasonCode","MISSING"); mutations.put("toolchainDigest","d".repeat(64)); mutations.put("bindingRevision","2");
        mutations.put("skillInstallationId","new-install"); mutations.put("productVersionId","new-product"); mutations.put("kind","EXECUTABLE");
        mutations.put("policyRevision","2"); mutations.put("enabled",false);
        assertEquals(RuntimeSkill.class.getRecordComponents().length-1, mutations.size());
        var before = view("session",fence,"1",skill);
        for (var entry : mutations.entrySet())
            assertFalse(before.sameExecutionFacts(view("session",fence,"1",change(entry.getKey(),entry.getValue()))),entry.getKey());
    }
    @Test void catalogOrderIsNotAuthorityAndRegistryStillRejectsObservationRollback() throws Exception {
        var other = change("skillInstallationId","second-install");
        assertTrue(new Catalog("1",List.of(skill,other)).sameExecutionFacts(new Catalog("1",List.of(other,change("observedAt",101L)))));
        var registry = new SkillExecutionSessionRegistry(); var scope = new Scope("0","client","owner","agent");
        registry.register("session",scope,fence,new Catalog("1",List.of(skill)),()->true);
        registry.register("session",scope,fence,new Catalog("1",List.of(change("observedAt",101L))),()->true);
        assertThrows(IllegalArgumentException.class,()->registry.register("session",scope,fence,new Catalog("1",List.of(skill)),()->true));
        assertThrows(IllegalArgumentException.class,()->registry.register("session",scope,fence,new Catalog("1",List.of(change("enabled",false))),()->true));
        assertEquals(101L,registry.lookup(scope,fence).catalog().skills().getFirst().observedAt());
        registry.suspend("session"); assertEquals(State.DISCONNECTED,registry.lookup(scope,fence).state());
    }
}
