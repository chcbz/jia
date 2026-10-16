package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.exception.PersonalWorkspaceException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;


/** Keeps short database transactions separate from private filesystem writes. */
@Named
public class PersonalWorkspaceWriteService {
    private final PersonalWorkspaceDao dao;
    private final PersonalWorkspaceOperationClaimTx claimTx;
    @Inject public PersonalWorkspaceWriteService(PersonalWorkspaceDao dao,
            PersonalWorkspaceOperationClaimTx claimTx) {
        this.dao = dao;
        this.claimTx = claimTx;
    }

    /** Leave the failed INSERT transaction before looking up the competing operation. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Claim claim(Scope scope, String type, String key, String requestHash) {
        try {
            return claimTx.claimOnce(scope, type, key, requestHash);
        } catch (DuplicateKeyException raced) {
            var existing = claimTx.findAfterRace(scope, type, key);
            if (existing == null) throw raced;
            return PersonalWorkspaceOperationClaimTx.existingClaim(existing, requestHash);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity completeCreate(Scope scope, PersonalWorkspaceOperationEntity operation,
            PersonalWorkspaceFileEntity file, PersonalWorkspaceVersionEntity version) {
        requireProcessing(scope, operation); dao.insertFile(file); dao.insertVersion(version);
        complete(operation, file.getFileId(), version.getVersion()); return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity completeAppend(Scope scope, PersonalWorkspaceOperationEntity operation,
            String fileId, int expectedPreviousVersion, PersonalWorkspaceVersionEntity version) {
        requireProcessing(scope, operation); PersonalWorkspaceFileEntity file = requireFile(scope, fileId);
        if (!"ACTIVE".equals(file.getState())) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);
        if (file.getLatestVersion() == null || file.getLatestVersion() != expectedPreviousVersion) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.VERSION_CONFLICT);
        }
        dao.insertVersion(version); file.setLatestVersion(version.getVersion())
                .setMetadataRevision(Math.addExact(file.getMetadataRevision(), 1L)); dao.updateFile(file);
        complete(operation, fileId, version.getVersion()); return file;
    }

    /** Runtime outputs are stored before this short transaction; this only makes their metadata visible. */
    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity archiveRuntimeOutput(Scope scope,
            PersonalWorkspaceFileEntity file, PersonalWorkspaceVersionEntity version) {
        if (file == null || version == null || !scope.tenantId().equals(file.getTenantId())
                || !scope.clientId().equals(file.getClientId())
                || !scope.ownerJiacn().equals(file.getOwnerJiacn())
                || !file.getFileId().equals(version.getFileId())
                || !scope.ownerJiacn().equals(version.getOwnerJiacn())
                || version.getVersion() == null || version.getVersion() != 1) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);
        }
        dao.insertFile(file);
        dao.insertVersion(version);
        return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity rename(Scope scope, PersonalWorkspaceOperationEntity operation,
            String fileId, String displayName, String ifMatch) {
        requireProcessing(scope, operation); PersonalWorkspaceFileEntity file = requireFile(scope, fileId); requireMatch(file, ifMatch);
        file.setDisplayName(displayName).setMetadataRevision(file.getMetadataRevision() + 1L); dao.updateFile(file);
        complete(operation, fileId, file.getLatestVersion()); return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity trash(Scope scope, PersonalWorkspaceOperationEntity operation,
            String fileId, long impactRevision, boolean acknowledgeExistingReferences, String ifMatch) {
        requireProcessing(scope, operation); PersonalWorkspaceFileEntity file = requireFile(scope, fileId); requireMatch(file, ifMatch);
        if (impactRevision != file.getMetadataRevision() || !acknowledgeExistingReferences) {
            throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.METADATA_CHANGED);
        }
        // All reference/grant writers serialize on this file row and advance its revision.
        file.setState("TRASHED").setMetadataRevision(file.getMetadataRevision() + 1L); dao.updateFile(file);
        complete(operation, fileId, file.getLatestVersion()); return file;
    }

    @Transactional(rollbackFor = Exception.class)
    public PersonalWorkspaceFileEntity restore(Scope scope, PersonalWorkspaceOperationEntity operation,
            String fileId, String ifMatch) {
        requireProcessing(scope, operation); PersonalWorkspaceFileEntity file = requireFile(scope, fileId); requireMatch(file, ifMatch);
        file.setState("ACTIVE").setMetadataRevision(file.getMetadataRevision() + 1L); dao.updateFile(file);
        complete(operation, fileId, file.getLatestVersion()); return file;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void fail(Scope scope, PersonalWorkspaceOperationEntity operation, String errorCode) {
        PersonalWorkspaceOperationEntity current = dao.findOperation(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), operation.getOperationId());
        if (current != null && "PROCESSING".equals(current.getState())) {
            current.setState("FAILED").setErrorCode(errorCode).setCompletedAt(System.currentTimeMillis()); dao.updateOperation(current);
        }
    }

    private void requireProcessing(Scope scope, PersonalWorkspaceOperationEntity operation) {
        PersonalWorkspaceOperationEntity current = dao.findOperation(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), operation.getOperationId());
        if (current == null) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND);
        if (!"PROCESSING".equals(current.getState())) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.OPERATION_FAILED);
        operation.setId(current.getId());
    }
    private PersonalWorkspaceFileEntity requireFile(Scope scope, String fileId) {
        PersonalWorkspaceFileEntity file=dao.lockFile(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), fileId);
        if(file==null) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.NOT_FOUND); return file;
    }
    private static void requireMatch(PersonalWorkspaceFileEntity file, String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.PRECONDITION_REQUIRED);
        String expected="\""+file.getFileId()+":"+file.getMetadataRevision()+"\"";
        if(!expected.equals(ifMatch)) throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.METADATA_CHANGED);
    }
    private void complete(PersonalWorkspaceOperationEntity operation, String fileId, int version) {
        operation.setState("COMMITTED").setFileId(fileId).setFileVersion(version).setErrorCode(null).setCompletedAt(System.currentTimeMillis()); dao.updateOperation(operation);
    }
    public record Scope(String tenantId, String clientId, String ownerJiacn) { }
    public record Claim(PersonalWorkspaceOperationEntity operation, boolean claimed) { }
}
