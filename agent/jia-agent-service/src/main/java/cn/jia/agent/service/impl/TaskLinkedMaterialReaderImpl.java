package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.TaskLinkedMaterialReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Ordinary reads only; inspection/task mutation lock reads retain their existing owners. */
@Service
public class TaskLinkedMaterialReaderImpl implements TaskLinkedMaterialReader {
    private final PersonalWorkspaceTaskLinkDao links;
    private final PersonalWorkspaceStorage storage;

    public TaskLinkedMaterialReaderImpl(PersonalWorkspaceTaskLinkDao links, PersonalWorkspaceStorage storage) {
        this.links = Objects.requireNonNull(links);
        this.storage = Objects.requireNonNull(storage);
    }

    @Override
    public Metadata metadata(PersonalWorkspaceTaskLinkService.Scope scope, String taskId, Selection selection) {
        return metadata(row(scope, taskId, selection));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PersonalWorkspaceStorage.StoredContent read(PersonalWorkspaceTaskLinkService.Scope scope,
            String taskId, Selection selection, Metadata expected) {
        // Suspend any caller snapshot: the post-I/O read must observe committed revocations.
        var row = row(scope, taskId, selection);
        if (!metadata(row).equals(expected)) throw new Failure(Failure.Reason.NOT_FOUND);
        PersonalWorkspaceStorage.StoredContent content;
        try {
            // Existing storage verifies actual bytes/hash; do not hash the same immutable bytes again here.
            content = storage.read(new PersonalWorkspaceStorage.Scope(scope.tenantId(), scope.clientId(), scope.ownerJiacn()),
                    row.storageUri(), expected.sha256(), expected.byteLength(), expected.contentMimeType());
        } catch (PersonalWorkspaceException error) {
            throw new Failure(error.getReason() == PersonalWorkspaceException.Reason.STORAGE_CORRUPT
                    ? Failure.Reason.CONTENT_MISMATCH : Failure.Reason.UNAVAILABLE, error);
        } catch (RuntimeException error) {
            throw new Failure(Failure.Reason.UNAVAILABLE, error);
        }
        if (content == null || !expected.sha256().equals(content.sha256())
                || expected.byteLength() != content.byteLength() || !expected.contentMimeType().equals(content.mimeType()))
            throw new Failure(Failure.Reason.CONTENT_MISMATCH);
        if (!metadata(scope, taskId, selection).equals(expected)) throw new Failure(Failure.Reason.NOT_FOUND);
        return content;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public java.io.InputStream open(PersonalWorkspaceTaskLinkService.Scope scope,
            String taskId,Selection selection,Metadata expected) {
        var row=row(scope,taskId,selection);
        if(!metadata(row).equals(expected))throw new Failure(Failure.Reason.NOT_FOUND);
        java.io.InputStream stream;
        try {
            stream=storage.openStream(new PersonalWorkspaceStorage.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),
                    row.storageUri(),expected.sha256(),expected.byteLength(),expected.contentMimeType());
        } catch(PersonalWorkspaceException error) {
            throw new Failure(error.getReason()==PersonalWorkspaceException.Reason.STORAGE_CORRUPT
                    ?Failure.Reason.CONTENT_MISMATCH:Failure.Reason.UNAVAILABLE,error);
        } catch(RuntimeException error) {throw new Failure(Failure.Reason.UNAVAILABLE,error);}
        try {
            if(!metadata(scope,taskId,selection).equals(expected))throw new Failure(Failure.Reason.NOT_FOUND);
            return stream;
        } catch(RuntimeException | Error rejected) {
            try {stream.close();} catch(java.io.IOException closeFailure) {rejected.addSuppressed(closeFailure);}
            throw rejected;
        }
    }

    private PersonalWorkspaceTaskLinkDao.MaterialRow row(PersonalWorkspaceTaskLinkService.Scope scope,
            String taskId, Selection selection) {
        if (scope == null || selection == null || selection.version() < 1
                || !("INPUT".equals(selection.purpose()) || "REFERENCE".equals(selection.purpose())))
            throw new Failure(Failure.Reason.NOT_FOUND);
        try {
            var rows = links.findActiveMaterial(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), taskId,
                    selection.fileId(), selection.version(), selection.purpose());
            if (rows.size() != 1) throw new Failure(Failure.Reason.NOT_FOUND);
            return rows.getFirst();
        } catch (Failure error) {
            throw error;
        } catch (RuntimeException error) {
            throw new Failure(Failure.Reason.UNAVAILABLE, error);
        }
    }

    private static Metadata metadata(PersonalWorkspaceTaskLinkDao.MaterialRow row) {
        if (row == null || row.contentMimeType() == null || !row.contentMimeType().matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                || row.sha256() == null || !row.sha256().matches("[0-9a-f]{64}")
                || row.byteLength() == null || row.byteLength() < 0 || row.storageUri() == null || row.storageUri().isBlank())
            throw new Failure(Failure.Reason.METADATA_INVALID);
        return new Metadata(row.contentMimeType(), row.sha256(), row.byteLength());
    }
}
