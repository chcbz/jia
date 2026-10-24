package cn.jia.chat.handler;

import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.service.SkillCapabilityLookup;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Transient live directory only. Registration caller supplies installation-derived scope/fence
 * and a live-binding predicate, never identities or permissions taken from the skill report.
 * The actual authenticated WS registration adapter is a separate integration step. */
@Component
public final class SkillExecutionSessionRegistry implements SkillCapabilityLookup {
    private record Key(String sessionId, String agentId) { }
    private record Entry(Key key, Scope scope, SessionFence fence, Catalog catalog, BooleanSupplier current) { }
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Key,Entry> watermarks=new ConcurrentHashMap<>();

    /** Null catalog records absence/invalidity and replaces any previously valid directory. */
    public synchronized void register(String sessionId, Scope trustedScope, SessionFence trustedFence,
            Catalog catalog, BooleanSupplier currentBinding) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.equals(sessionId.strip())
                || sessionId.codePointCount(0, sessionId.length()) > 128
                || sessionId.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid skill session ID");
        Objects.requireNonNull(trustedScope); SkillCapabilityLookup.validateFence(trustedFence);
        var key = new Key(sessionId, trustedScope.agentId());
        var previous=watermarks.get(key);
        if(previous!=null && previous.fence().equals(trustedFence) && previous.catalog()!=null && catalog!=null) {
            int order=new java.math.BigInteger(catalog.catalogRevision()).compareTo(new java.math.BigInteger(previous.catalog().catalogRevision()));
            if(order<0 || order==0&&!sameMeasuredFacts(previous.catalog(),catalog)) throw new IllegalArgumentException("Skill catalog revision conflict");
        }
        var entry=new Entry(key,trustedScope,trustedFence,catalog,Objects.requireNonNull(currentBinding));
        entries.put(key,entry);if(catalog!=null)watermarks.put(key,entry);
    }

    private static boolean sameMeasuredFacts(Catalog previous,Catalog next) {
        if(previous.skills().size()!=next.skills().size())return false;
        for(var before:previous.skills()) {
            var matches=next.skills().stream().filter(row->Objects.equals(before.skillInstallationId(),row.skillInstallationId())
                    &&before.skillKey().equals(row.skillKey())&&before.skillVersion().equals(row.skillVersion())).toList();
            if(matches.size()!=1)return false;var after=matches.getFirst();
            // Runtime revision fingerprints configuration/readiness, not the time of a
            // repeated measurement. Accept newer observation time only; every exact
            // installation, digest, policy, binding and readiness fact stays fenced.
            if(before.observedAt()!=null&&(after.observedAt()==null||after.observedAt()<before.observedAt()))return false;
            if(!withoutObservation(before).equals(withoutObservation(after)))return false;
        }
        return true;
    }
    private static RuntimeSkill withoutObservation(RuntimeSkill row) {
        return new RuntimeSkill(row.skillKey(),row.skillVersion(),row.packageSha256(),row.manifestSha256(),row.runner(),row.readiness(),row.reasonCode(),
                row.toolchainDigest(),row.bindingRevision(),row.skillInstallationId(),row.productVersionId(),row.kind(),row.policyRevision(),row.enabled(),null);
    }

    public synchronized void suspend(String sessionId) {
        if(sessionId!=null)entries.entrySet().removeIf(e->sessionId.equals(e.getKey().sessionId()));
    }
    public synchronized void remove(String sessionId) {
        suspend(sessionId);
        if(sessionId!=null)watermarks.entrySet().removeIf(e->sessionId.equals(e.getKey().sessionId()));
    }

    @Override public View lookup(Scope scope, SessionFence expectedFence) {
        Objects.requireNonNull(scope); SkillCapabilityLookup.validateFence(expectedFence);
        Entry selected = null;
        for (var entry : entries.values()) {
            if (!entry.scope().equals(scope) || !current(entry)) continue;
            if (selected != null) return View.unavailable(State.AMBIGUOUS);
            selected = entry;
        }
        if (selected == null) return View.unavailable(State.DISCONNECTED);
        if (!selected.fence().equals(expectedFence) || !current(selected) || entries.get(selected.key()) != selected)
            return View.unavailable(State.FENCED);
        if (selected.catalog() == null) return View.unavailable(State.INVALID_DECLARATION);
        return new View(State.AVAILABLE, selected.key().sessionId(), selected.fence(), selected.catalog());
    }

    private static boolean current(Entry entry) {
        try { return entry.current().getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
}
