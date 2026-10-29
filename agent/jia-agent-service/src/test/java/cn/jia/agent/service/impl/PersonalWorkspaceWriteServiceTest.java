package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonalWorkspaceWriteServiceTest {
    @Test
    void runtimeOutputRejectsForeignVersionMetadataBeforeAnyWrite() {
        var dao = org.mockito.Mockito.mock(cn.jia.agent.dao.PersonalWorkspaceDao.class);
        var writes = new PersonalWorkspaceWriteService(dao,
                org.mockito.Mockito.mock(PersonalWorkspaceOperationClaimTx.class));
        var scope = new PersonalWorkspaceWriteService.Scope("1", "browser", "owner");
        var file = new PersonalWorkspaceFileEntity();
        file.setTenantId("1"); file.setClientId("browser");
        file.setFileId("file-1"); file.setOwnerJiacn("owner");
        var version = new PersonalWorkspaceVersionEntity();
        version.setTenantId("0"); version.setClientId("browser");
        version.setFileId("file-1"); version.setOwnerJiacn("owner"); version.setVersion(1);
        org.junit.jupiter.api.Assertions.assertThrows(cn.jia.agent.exception.PersonalWorkspaceException.class,
                () -> writes.archiveRuntimeOutput(scope, file, version));
        version.setTenantId("1"); version.setClientId("foreign");
        org.junit.jupiter.api.Assertions.assertThrows(cn.jia.agent.exception.PersonalWorkspaceException.class,
                () -> writes.archiveRuntimeOutput(scope, file, version));
        org.mockito.Mockito.verifyNoInteractions(dao);
        version.setClientId("browser");
        assertEquals(file, writes.archiveRuntimeOutput(scope, file, version));
        org.mockito.Mockito.verify(dao).insertFile(file);
        org.mockito.Mockito.verify(dao).insertVersion(version);
    }

    @Test
    void storageIoStaysOutsideTransactionsWhileClaimAndFailureReceiptsUseRequiresNew() throws Exception {
        assertNull(PersonalWorkspaceServiceImpl.class.getAnnotation(Transactional.class));
        Method create = PersonalWorkspaceServiceImpl.class.getMethod("create",
                PersonalWorkspaceService.Scope.class, PersonalWorkspaceService.UploadCommand.class);
        Method append = PersonalWorkspaceServiceImpl.class.getMethod("appendVersion",
                PersonalWorkspaceService.Scope.class, String.class,
                PersonalWorkspaceService.UploadCommand.class, int.class);
        assertNull(create.getAnnotation(Transactional.class));
        assertNull(append.getAnnotation(Transactional.class));

        var claim = PersonalWorkspaceWriteService.class.getMethod("claim",
                PersonalWorkspaceWriteService.Scope.class, String.class, String.class, String.class);
        assertEquals(Propagation.NOT_SUPPORTED, claim.getAnnotation(Transactional.class).propagation());
        var claimOnce = PersonalWorkspaceOperationClaimTx.class.getMethod("claimOnce",
                PersonalWorkspaceWriteService.Scope.class, String.class, String.class, String.class);
        assertEquals(Propagation.REQUIRES_NEW, claimOnce.getAnnotation(Transactional.class).propagation());
        assertArrayEquals(new Class<?>[] {Exception.class}, claimOnce.getAnnotation(Transactional.class).rollbackFor());
        var afterRace = PersonalWorkspaceOperationClaimTx.class.getMethod("findAfterRace",
                PersonalWorkspaceWriteService.Scope.class, String.class, String.class);
        assertEquals(Propagation.REQUIRES_NEW, afterRace.getAnnotation(Transactional.class).propagation());
        assertTransaction("fail", Propagation.REQUIRES_NEW,
                PersonalWorkspaceWriteService.Scope.class,
                PersonalWorkspaceOperationEntity.class, String.class);

        for (Method method : List.of(
                PersonalWorkspaceWriteService.class.getMethod("completeCreate",
                        PersonalWorkspaceWriteService.Scope.class,
                        PersonalWorkspaceOperationEntity.class,
                        PersonalWorkspaceFileEntity.class,
                        PersonalWorkspaceVersionEntity.class),
                PersonalWorkspaceWriteService.class.getMethod("completeAppend",
                        PersonalWorkspaceWriteService.Scope.class,
                        PersonalWorkspaceOperationEntity.class, String.class, int.class,
                        PersonalWorkspaceVersionEntity.class),
                PersonalWorkspaceWriteService.class.getMethod("rename",
                        PersonalWorkspaceWriteService.Scope.class,
                        PersonalWorkspaceOperationEntity.class, String.class,
                        String.class, String.class),
                PersonalWorkspaceWriteService.class.getMethod("trash",
                        PersonalWorkspaceWriteService.Scope.class,
                        PersonalWorkspaceOperationEntity.class, String.class,
                        long.class, boolean.class, String.class),
                PersonalWorkspaceWriteService.class.getMethod("restore",
                        PersonalWorkspaceWriteService.Scope.class,
                        PersonalWorkspaceOperationEntity.class, String.class, String.class))) {
            Transactional transaction = method.getAnnotation(Transactional.class);
            assertNotNull(transaction, method.getName());
            assertEquals(Propagation.REQUIRED, transaction.propagation(), method.getName());
            assertArrayEquals(new Class<?>[] {Exception.class}, transaction.rollbackFor(),
                    method.getName());
        }
    }

    private static void assertTransaction(String methodName, Propagation propagation,
            Class<?>... parameterTypes) throws Exception {
        Transactional transaction = PersonalWorkspaceWriteService.class
                .getMethod(methodName, parameterTypes).getAnnotation(Transactional.class);
        assertNotNull(transaction, methodName);
        assertEquals(propagation, transaction.propagation(), methodName);
        assertArrayEquals(new Class<?>[] {Exception.class}, transaction.rollbackFor(), methodName);
    }
}
