package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.agent.service.TaskLinkedMaterialReader;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskLinkedMaterialReaderTest {
    final PersonalWorkspaceTaskLinkDao dao = mock(PersonalWorkspaceTaskLinkDao.class);
    final PersonalWorkspaceStorage storage = mock(PersonalWorkspaceStorage.class);
    final TaskLinkedMaterialReader reader = new TaskLinkedMaterialReaderImpl(dao,storage);
    final PersonalWorkspaceTaskLinkService.Scope scope = new PersonalWorkspaceTaskLinkService.Scope("0","client","owner");
    final TaskLinkedMaterialReader.Selection selection = new TaskLinkedMaterialReader.Selection("file",2,"REFERENCE");
    final TaskLinkedMaterialReader.Metadata metadata = new TaskLinkedMaterialReader.Metadata("image/png","a".repeat(64),3);
    final PersonalWorkspaceTaskLinkDao.MaterialRow row = new PersonalWorkspaceTaskLinkDao.MaterialRow("image/png","a".repeat(64),3L,"private:v2");
    final PersonalWorkspaceStorage.StoredContent content = new PersonalWorkspaceStorage.StoredContent(new byte[]{1,2,3},metadata.sha256(),3,"image/png");

    void linked() { when(dao.findActiveMaterial("0","client","owner","task","file",2,"REFERENCE")).thenReturn(List.of(row)); }
    void readable() { linked(); when(storage.read(any(),eq("private:v2"),eq(metadata.sha256()),eq(3L),eq("image/png"))).thenReturn(content); }
    @Test void fixedSelectionIsReadAndAuthorizationRecheckedAfterIo() {
        readable();
        assertEquals(metadata,reader.metadata(scope,"task",selection));
        assertEquals(content,reader.read(scope,"task",selection,metadata));
        verify(dao,times(3)).findActiveMaterial("0","client","owner","task","file",2,"REFERENCE");
        verify(storage).read(new PersonalWorkspaceStorage.Scope("0","client","owner"),"private:v2",metadata.sha256(),3,"image/png");
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.metadata(new PersonalWorkspaceTaskLinkService.Scope("0","client","other"),"task",selection)).reason());
    }
    @Test void revocationOrChangedSnapshotNeverReturnsMaterial() {
        readable();
        when(dao.findActiveMaterial("0","client","owner","task","file",2,"REFERENCE"))
                .thenReturn(List.of(row),List.of());
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.read(scope,"task",selection,metadata)).reason());
        reset(storage); linked();
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.read(scope,"task",selection,new TaskLinkedMaterialReader.Metadata("image/png","b".repeat(64),3))).reason());
        verifyNoInteractions(storage);
    }
    @Test void ambiguousInvalidUnavailableAndCorruptFactsHaveDistinctFailures() {
        when(dao.findActiveMaterial(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt(),anyString()))
                .thenReturn(List.of(row,row), List.of(new PersonalWorkspaceTaskLinkDao.MaterialRow("image/png",null,3L,"private:v2")))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.NOT_FOUND,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.metadata(scope,"task",selection)).reason());
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.METADATA_INVALID,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.metadata(scope,"task",selection)).reason());
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.UNAVAILABLE,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.metadata(scope,"task",selection)).reason());
        reset(dao); linked();
        when(storage.read(any(),anyString(),anyString(),anyLong(),anyString()))
                .thenThrow(new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_CORRUPT));
        assertEquals(TaskLinkedMaterialReader.Failure.Reason.CONTENT_MISMATCH,assertThrows(TaskLinkedMaterialReader.Failure.class,
                ()->reader.read(scope,"task",selection,metadata)).reason());
    }
}
