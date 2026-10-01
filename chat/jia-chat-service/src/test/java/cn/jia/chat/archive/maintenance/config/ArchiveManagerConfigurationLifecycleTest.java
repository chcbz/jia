package cn.jia.chat.archive.maintenance.config;

import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveManagerGrantRecord;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveManagerConfigurationLifecycleTest {
    private static final ArchiveActorScope ACTOR = new ArchiveActorScope("0", "client-a", "owner-a");
    private static final String COLLECTION = "platform-classics";

    @Test
    void defaultRevisionCreatesExactAuditedAuthorizationWithoutInsertIgnore() {
        ArchiveMaintenanceStore store = storeWithAudit();
        ArchiveMaintenanceProperties properties = properties(1,
                "appoint,source.prepare,job.create,job.manage,draft.write,validate,publish");
        when(store.lockManagerGrants()).thenReturn(List.of());

        initializer(store, properties).reconcileConfiguredManagers();

        var grant = org.mockito.ArgumentCaptor.forClass(ArchiveManagerGrantRecord.class);
        verify(store).insertManagerGrant(grant.capture());
        assertEquals(1, grant.getValue().revision());
        assertEquals("ACTIVE", grant.getValue().state());
        assertEquals("appoint,draft.write,job.create,job.manage,publish,source.prepare,validate",
                grant.getValue().permissions());
        verify(store).commitOperation(eq(ACTOR), startsWith("cfg-manager-"), eq(COLLECTION));
    }

    @Test
    void independentWithdrawPermissionIsAcceptedOnlyWhenExplicitlyConfigured() {
        ArchiveMaintenanceStore store = storeWithAudit();
        when(store.lockManagerGrants()).thenReturn(List.of());
        initializer(store, properties(2, "edition.withdraw")).reconcileConfiguredManagers();
        var grant = org.mockito.ArgumentCaptor.forClass(ArchiveManagerGrantRecord.class);
        verify(store).insertManagerGrant(grant.capture());
        assertEquals("edition.withdraw", grant.getValue().permissions());
        assertEquals("appoint,source.prepare,job.create,job.manage,draft.write,validate,publish",
                new ArchiveMaintenanceProperties.ManagerGrant().getPermissions());
    }

    @Test
    void removalRevokesAtHigherRevisionAndFencesEveryLockedRun() {
        ArchiveMaintenanceStore store = storeWithAudit();
        when(store.lockManagerGrants()).thenReturn(List.of(current(4, "ACTIVE", "appoint,job.manage")));
        when(store.lockRunIdsForManager(ACTOR, COLLECTION)).thenReturn(List.of("run-a", "run-b"));
        when(store.fenceRun("run-a")).thenReturn(1);
        when(store.fenceRun("run-b")).thenReturn(1);
        when(store.revokeManagerGrant(ACTOR, COLLECTION, 4)).thenReturn(1);

        initializer(store, new ArchiveMaintenanceProperties()).reconcileConfiguredManagers();

        var order = inOrder(store);
        order.verify(store).lockManagerGrants();
        order.verify(store).beginOperation(eq(ACTOR), startsWith("cfg-manager-"), eq("DELETE"),
                startsWith("/internal/archive/config/v1/manager-grants/"), anyString(),
                eq("MANAGER_CONFIG"), eq(COLLECTION));
        order.verify(store).lockRunIdsForManager(ACTOR, COLLECTION);
        order.verify(store).fenceRun("run-a");
        order.verify(store).fenceRun("run-b");
        order.verify(store).revokeManagerGrant(ACTOR, COLLECTION, 4);
    }

    @Test
    void staleOrSameRevisionCannotReviveRevokedAuthorization() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        when(store.lockManagerGrants()).thenReturn(List.of(current(2, "REVOKED", "appoint,job.manage")));

        initializer(store, properties(1, "appoint,job.manage")).reconcileConfiguredManagers();
        initializer(store, properties(2, "appoint,job.manage")).reconcileConfiguredManagers();

        verify(store, never()).insertManagerGrant(any());
        verify(store, never()).activateConfiguredManagerGrant(any(), anyLong());
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void permissionChangeRequiresHigherRevisionAndHigherRevisionRegrantsWithFencing() {
        ArchiveMaintenanceStore conflicting = mock(ArchiveMaintenanceStore.class);
        when(conflicting.lockManagerGrants()).thenReturn(List.of(current(2, "ACTIVE", "appoint")));
        assertThrows(IllegalStateException.class, () -> initializer(conflicting,
                properties(2, "appoint,job.manage")).reconcileConfiguredManagers());
        verify(conflicting, never()).activateConfiguredManagerGrant(any(), anyLong());

        ArchiveMaintenanceStore store = storeWithAudit();
        when(store.lockManagerGrants()).thenReturn(List.of(current(2, "REVOKED", "appoint")));
        when(store.lockRunIdsForManager(ACTOR, COLLECTION)).thenReturn(List.of("run-old"));
        when(store.fenceRun("run-old")).thenReturn(1);
        when(store.activateConfiguredManagerGrant(any(), eq(2L))).thenReturn(1);

        initializer(store, properties(3, "appoint,job.manage")).reconcileConfiguredManagers();

        var grant = org.mockito.ArgumentCaptor.forClass(ArchiveManagerGrantRecord.class);
        verify(store).activateConfiguredManagerGrant(grant.capture(), eq(2L));
        assertEquals(3, grant.getValue().revision());
        assertEquals("ACTIVE", grant.getValue().state());
        assertEquals("appoint,job.manage", grant.getValue().permissions());
        verify(store).fenceRun("run-old");
    }

    @Test
    void duplicateIdentityAndNonPositiveRevisionFailBeforeDatabaseMutation() {
        ArchiveMaintenanceProperties duplicate = properties(1, "appoint");
        duplicate.getManagerGrants().add(grant(1, "appoint"));
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        assertThrows(IllegalStateException.class,
                () -> initializer(store, duplicate).reconcileConfiguredManagers());
        ArchiveMaintenanceProperties invalid = properties(0, "appoint");
        assertThrows(IllegalStateException.class,
                () -> initializer(store, invalid).reconcileConfiguredManagers());
        verifyNoInteractions(store);
    }

    private static ArchiveMaintenanceSchemaInitializer initializer(ArchiveMaintenanceStore store,
            ArchiveMaintenanceProperties properties) {
        return new ArchiveMaintenanceSchemaInitializer(mock(JdbcTemplate.class), store, properties);
    }

    private static ArchiveMaintenanceStore storeWithAudit() {
        ArchiveMaintenanceStore store = mock(ArchiveMaintenanceStore.class);
        when(store.beginOperation(any(), anyString(), anyString(), anyString(), anyString(),
                eq("MANAGER_CONFIG"), eq(COLLECTION))).thenAnswer(call ->
                new ArchiveMaintenanceStore.Operation(true, call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(5), call.getArgument(6), "PENDING"));
        return store;
    }

    private static ArchiveMaintenanceProperties properties(long revision, String permissions) {
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setManagerGrants(new java.util.ArrayList<>(List.of(grant(revision, permissions))));
        return properties;
    }

    private static ArchiveMaintenanceProperties.ManagerGrant grant(long revision, String permissions) {
        ArchiveMaintenanceProperties.ManagerGrant grant = new ArchiveMaintenanceProperties.ManagerGrant();
        grant.setClientId("client-a");
        grant.setOwnerJiacn("owner-a");
        grant.setAuthorizationRevision(revision);
        grant.setPermissions(permissions);
        return grant;
    }

    private static ArchiveManagerGrantRecord current(long revision, String state, String permissions) {
        return new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                permissions, revision, state);
    }
}
