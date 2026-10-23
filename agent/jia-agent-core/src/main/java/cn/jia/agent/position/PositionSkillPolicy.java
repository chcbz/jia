package cn.jia.agent.position;

import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.position.PositionSkillState.*;
import static cn.jia.agent.position.PositionSkillException.require;

/** Pure lifecycle rules. Caller serializes persistence and authorization; no transport success is inferred. */
public final class PositionSkillPolicy {
    private final Map<String, Skill> skills;
    private final Map<String, Position> positions;
    private final Map<String, Appointment> appointments;
    private final Map<String, Installation> installations;
    private final Supplier<String> ids;
    public PositionSkillPolicy(PositionSkillState state, Supplier<String> ids) {
        this.skills = new TreeMap<>(state.skills()); this.positions = new TreeMap<>(state.positions());
        this.appointments = new TreeMap<>(state.appointments()); this.installations = new TreeMap<>(state.installations());
        this.ids = Objects.requireNonNull(ids);
    }
    public PositionSkillState state() { return new PositionSkillState(1, skills, positions, appointments, installations); }
    public void defineSkill(Skill skill) {
        require(skill != null && skill.type() != null, 400, "POSITION_SKILL_INVALID");
        key(skill.key()); key(skill.productVersionId());
        require(skill.key().matches("[a-z0-9][a-z0-9-]{0,63}"),400,"POSITION_SKILL_INVALID");
        var old = skills.get(skill.key());
        require(old == null || old.equals(skill), 409, "POSITION_SKILL_IMMUTABLE");
        skills.put(skill.key(), skill);
    }
    public void configure(Position definition, long expectedRevision) {
        require(definition != null, 400, "POSITION_INVALID"); key(definition.id());
        require(definition.title() != null && !definition.title().isBlank() && definition.title().length() <= 200
                && definition.capacity() > 0 && definition.resource() != null
                && definition.resource().matches("archive:(public|work:[A-Za-z0-9][A-Za-z0-9._:-]{0,99})"), 400, "POSITION_INVALID");
        require(new HashSet<>(definition.skillKeys()).size() == definition.skillKeys().size()
                && definition.skillKeys().stream().allMatch(skills::containsKey), 400, "POSITION_SKILL_UNKNOWN");
        var old = positions.get(definition.id());
        require((old == null ? 0 : old.revision()) == expectedRevision, 409, "POSITION_REVISION_CONFLICT");
        var incumbents = appointments.values().stream().filter(a -> a.active() && a.positionId().equals(definition.id())).toList();
        require(incumbents.size() <= definition.capacity(), 409, "POSITION_CAPACITY_CONFLICT");
        positions.put(definition.id(), new Position(definition.id(), definition.title(), definition.capacity(),
                definition.resource(), definition.allowPublish(), definition.skillKeys(), expectedRevision + 1));
        // Only newly added GENERAL defaults grant retention. An unrelated config change must not undo explicit uninstall.
        Set<String> previous = old == null ? Set.of() : Set.copyOf(old.skillKeys());
        for (var a : incumbents) for (var skill : definition.skillKeys()) {
            if (!previous.contains(skill)) ensure(a, skills.get(skill));
        }
        reconcile();
    }
    public Appointment appoint(String positionId, String agentId, long bindingId) {
        key(agentId); require(bindingId > 0, 400, "POSITION_BINDING_INVALID");
        var p = positions.get(positionId); require(p != null, 404, "POSITION_NOT_FOUND");
        var existing = appointments.values().stream().filter(a -> a.active() && a.positionId().equals(positionId)
                && a.agentId().equals(agentId)).findFirst();
        if (existing.isPresent()) {
            require(existing.get().bindingId() == bindingId, 409, "POSITION_BINDING_CHANGED");
            return existing.get();
        }
        require(appointments.values().stream().filter(a -> a.active() && a.positionId().equals(positionId)).count() < p.capacity(),
                409, "POSITION_CAPACITY_REACHED");
        var a = new Appointment(ids.get(), positionId, agentId, bindingId, true, 1);
        appointments.put(a.id(), a);
        for (var key : p.skillKeys()) ensure(a, skills.get(key));
        reconcile(); return a;
    }
    public void resign(String appointmentId) {
        var a = appointments.get(appointmentId); require(a != null, 404, "APPOINTMENT_NOT_FOUND");
        if (!a.active()) return;
        appointments.put(a.id(), new Appointment(a.id(), a.positionId(), a.agentId(), a.bindingId(), false, a.revision() + 1));
        reconcile();
    }
    public void uninstallGeneral(String installationId) {
        var i = installation(installationId);
        require(skills.get(i.skillKey()).type() == SkillType.GENERAL, 409, "POSITION_SKILL_REQUIRES_RESIGNATION");
        installations.put(i.id(), change(i, false, false));
    }
    public void retry(String installationId) {
        var i = installation(installationId);
        require(i.state() == InstallState.FAILED, 409, "POSITION_INSTALLATION_NOT_FAILED");
        installations.put(i.id(), new Installation(i.id(), i.agentId(), i.bindingId(), i.skillKey(), i.retained(), i.desired(),
                i.revision() + 1, i.desired() ? InstallState.INSTALL_PENDING : InstallState.REMOVE_PENDING, null, null, null, null));
    }
    public void receipt(String installationId, long revision, boolean installed, String digest, String runtimeId,
            String hostId, String errorCode) {
        var i = installation(installationId);
        require(revision == i.revision(), 409, "POSITION_INSTALLATION_FENCED");
        require(digest != null && digest.matches("[0-9a-f]{64}"), 400, "POSITION_RECEIPT_INVALID");
        key(runtimeId); key(hostId);
        if (i.receiptDigest() != null) {
            require(digest.equals(i.receiptDigest()) && runtimeId.equals(i.runtimeInstallationId()) && hostId.equals(i.runtimeHostId()),
                    409, "POSITION_RECEIPT_CONFLICT"); return;
        }
        require(i.state() == InstallState.INSTALL_PENDING || i.state() == InstallState.REMOVE_PENDING,
                409, "POSITION_INSTALLATION_FENCED");
        require(errorCode == null || errorCode.matches("[A-Z][A-Z0-9_]{0,63}"), 400, "POSITION_RECEIPT_INVALID");
        require(errorCode != null || installed == i.desired(), 409, "POSITION_RECEIPT_CONFLICT");
        installations.put(i.id(), new Installation(i.id(), i.agentId(), i.bindingId(), i.skillKey(), i.retained(), i.desired(), i.revision(),
                errorCode != null ? InstallState.FAILED : installed ? InstallState.INSTALLED : InstallState.REMOVED,
                digest, runtimeId, hostId, errorCode));
    }
    public void authorize(String appointmentId, long appointmentRevision, long positionRevision,
            String agentId, long bindingId, String workId, boolean publish) {
        var a = appointments.get(appointmentId);
        require(a != null && a.active() && a.revision() == appointmentRevision && a.agentId().equals(agentId)
                && a.bindingId() == bindingId, 403, "POSITION_AUTHORITY_REVOKED");
        var p = positions.get(a.positionId());
        require(p != null && p.revision() == positionRevision
                && (p.resource().equals("archive:public") || p.resource().equals("archive:work:" + workId))
                && (!publish || p.allowPublish()), 403, "POSITION_RESOURCE_FORBIDDEN");
    }
    private void ensure(Appointment a, Skill s) {
        var old = installations.values().stream().filter(i -> i.agentId().equals(a.agentId()) && i.bindingId() == a.bindingId()
                && i.skillKey().equals(s.key())).findFirst().orElse(null);
        boolean retained = s.type() == SkillType.GENERAL;
        if (old == null) {
            String id = ids.get();
            installations.put(id, new Installation(id, a.agentId(), a.bindingId(), s.key(), retained, true, 1,
                    InstallState.INSTALL_PENDING, null, null, null, null));
        } else installations.put(old.id(), change(old, retained || old.retained(), true));
    }
    private void reconcile() {
        for (var i : List.copyOf(installations.values())) {
            boolean desired = skills.get(i.skillKey()).type() == SkillType.GENERAL ? i.retained()
                    : appointments.values().stream().anyMatch(a -> a.active() && a.agentId().equals(i.agentId())
                    && a.bindingId() == i.bindingId() && positions.get(a.positionId()).skillKeys().contains(i.skillKey()));
            installations.put(i.id(), change(i, i.retained(), desired));
        }
    }
    private Installation change(Installation i, boolean retained, boolean desired) {
        boolean transition = desired != i.desired();
        return new Installation(i.id(), i.agentId(), i.bindingId(), i.skillKey(), retained, desired,
                transition ? i.revision() + 1 : i.revision(),
                transition ? desired ? InstallState.INSTALL_PENDING : InstallState.REMOVE_PENDING : i.state(),
                transition ? null : i.receiptDigest(), transition ? null : i.runtimeInstallationId(),
                transition ? null : i.runtimeHostId(), transition ? null : i.errorCode());
    }
    private Installation installation(String id) {
        var i = installations.get(id); require(i != null, 404, "POSITION_INSTALLATION_NOT_FOUND"); return i;
    }
    public static void key(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"), 400, "POSITION_KEY_INVALID");
    }
}
