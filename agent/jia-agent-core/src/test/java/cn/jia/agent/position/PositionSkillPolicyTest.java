package cn.jia.agent.position;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.jia.agent.position.PositionSkillState.*;
import static org.junit.jupiter.api.Assertions.*;

class PositionSkillPolicyTest {
    private PositionSkillPolicy policy() {
        var n = new AtomicInteger();
        var p = new PositionSkillPolicy(PositionSkillState.empty(), () -> "id_" + n.incrementAndGet());
        p.defineSkill(new Skill("maintain", SkillType.POSITION, "v1"));
        p.defineSkill(new Skill("read", SkillType.GENERAL, "v2"));
        p.configure(position("editor", 2, "maintain", "read"), 0);
        return p;
    }
    private Position position(String id, int capacity, String... keys) {
        return new Position(id, id, capacity, "archive:public", false, List.of(keys), 0);
    }
    private Installation installation(PositionSkillPolicy p, String key) {
        return p.state().installations().values().stream().filter(i -> i.skillKey().equals(key)).findFirst().orElseThrow();
    }
    @Test void exactlyTwoTypes() { assertEquals(List.of("POSITION", "GENERAL"), Arrays.stream(SkillType.values()).map(Enum::name).toList()); }
    @Test void appointmentSchedulesButNeverInventsInstallationSuccess() {
        var p = policy(); p.appoint("editor", "agent1", 1);
        assertEquals(2, p.state().installations().size());
        assertTrue(p.state().installations().values().stream().allMatch(i -> i.desired() && !i.usable() && i.state() == InstallState.INSTALL_PENDING));
    }
    @Test void generalRetainsLegitimateEntitlementAfterResignation() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1);
        var g = installation(p, "read"); p.receipt(g.id(), g.revision(), true, "a".repeat(64), "runtime1", "host1", null);
        p.resign(a.id());
        assertTrue(installation(p, "read").retained()); assertTrue(installation(p, "read").usable());
        assertFalse(installation(p, "maintain").desired()); assertEquals(InstallState.REMOVE_PENDING, installation(p, "maintain").state());
    }
    @Test void sharedPositionSkillIsRemovedOnlyAfterLastSource() {
        var p = policy(); p.configure(position("reviewer", 1, "maintain"), 0);
        var a = p.appoint("editor", "agent1", 1); var b = p.appoint("reviewer", "agent1", 1);
        assertEquals(2, p.state().installations().size()); p.resign(a.id()); assertTrue(installation(p, "maintain").desired());
        p.resign(b.id()); assertFalse(installation(p, "maintain").desired());
        long revision = installation(p, "maintain").revision(); p.resign(b.id()); assertEquals(revision, installation(p, "maintain").revision());
    }
    @Test void addingDefaultsReconcilesIncumbentsAndRemovingGeneralDoesNotUninstall() {
        var p = policy(); p.configure(position("other", 1), 0); p.appoint("other", "agent1", 1);
        p.configure(position("other", 1, "maintain", "read"), 1); assertEquals(2, p.state().installations().size());
        p.configure(position("other", 1), 2); assertFalse(installation(p, "maintain").desired()); assertTrue(installation(p, "read").desired());
    }
    @Test void removedPositionDefaultKeepsAnotherPositionSource() {
        var p = policy(); p.configure(position("other", 1, "maintain"), 0);
        p.appoint("editor", "agent1", 1); p.appoint("other", "agent1", 1);
        p.configure(position("editor", 2, "read"), 1); assertTrue(installation(p, "maintain").desired());
    }
    @Test void independentlyUninstalledGeneralIsNotReinstalledByUnrelatedConfigOrResignation() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1);
        p.uninstallGeneral(installation(p, "read").id()); p.configure(position("editor", 3, "read", "maintain"), 1);
        p.resign(a.id()); assertFalse(installation(p, "read").desired()); assertFalse(installation(p, "read").retained());
    }
    @Test void capacityIsPerPositionAndDuplicateAppointmentIsIdempotent() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1);
        assertEquals(a, p.appoint("editor", "agent1", 1)); p.appoint("editor", "agent2", 2);
        assertThrows(PositionSkillException.class, () -> p.appoint("editor", "agent3", 3));
        assertThrows(PositionSkillException.class, () -> p.configure(position("editor", 1), 1));
    }
    @Test void oldReceiptCannotReenableRevokedPositionSkill() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1); var i = installation(p, "maintain"); p.resign(a.id());
        assertThrows(PositionSkillException.class, () -> p.receipt(i.id(), i.revision(), true, "a".repeat(64), "runtime1", "host1", null));
        assertFalse(installation(p, "maintain").usable());
    }
    @Test void receiptsAreExactAndIdempotent() {
        var p = policy(); p.appoint("editor", "agent1", 1); var i = installation(p, "read");
        p.receipt(i.id(), i.revision(), true, "a".repeat(64), "runtime1", "host1", null);
        p.receipt(i.id(), i.revision(), true, "a".repeat(64), "runtime1", "host1", null);
        assertTrue(installation(p, "read").usable());
        assertThrows(PositionSkillException.class, () -> p.receipt(i.id(), i.revision(), true, "b".repeat(64), "runtime1", "host1", null));
    }
    @Test void retryAdvancesGenerationAndNeverGrantsUsability() {
        var p = policy(); p.appoint("editor", "agent1", 1); var i = installation(p, "read");
        p.receipt(i.id(), i.revision(), false, "a".repeat(64), "runtime1", "host1", "INSTALL_FAILED");
        p.retry(i.id()); assertEquals(2, installation(p, "read").revision()); assertFalse(installation(p, "read").usable());
    }
    @Test void resignationImmediatelyRevokesBusinessAuthorityEvenIfInstalledFilesRemain() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1);
        p.authorize(a.id(), 1, 1, "agent1", 1, "book", false); p.resign(a.id());
        assertThrows(PositionSkillException.class, () -> p.authorize(a.id(), 1, 1, "agent1", 1, "book", false));
    }
    @Test void businessPermissionIsNotImpliedByInstallationAndRejectsWrongSubjectAndPublish() {
        var p = policy(); var a = p.appoint("editor", "agent1", 1);
        assertThrows(PositionSkillException.class, () -> p.authorize(a.id(), 1, 1, "agent2", 1, "book", false));
        assertThrows(PositionSkillException.class, () -> p.authorize(a.id(), 1, 1, "agent1", 2, "book", false));
        assertThrows(PositionSkillException.class, () -> p.authorize(a.id(), 1, 1, "agent1", 1, "book", true));
        p.configure(position("editor", 2, "read"), 1);
        assertThrows(PositionSkillException.class, () -> p.authorize(a.id(), 1, 1, "agent1", 1, "book", false));
    }
    @Test void definitionsAreImmutableAndStaleConfigurationCannotOverwrite() {
        var p = policy();
        assertThrows(PositionSkillException.class, () -> p.defineSkill(new Skill("read", SkillType.POSITION, "v2")));
        assertThrows(PositionSkillException.class, () -> p.configure(position("editor", 3), 0));
        assertThrows(UnsupportedOperationException.class, () -> p.state().skills().clear());
    }
}
