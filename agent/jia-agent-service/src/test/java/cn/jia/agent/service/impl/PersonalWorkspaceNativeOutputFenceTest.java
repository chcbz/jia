package cn.jia.agent.service.impl;

import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.ArgumentCaptor;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real execution service and persistent auth transaction proxy; storage/DAOs are collaborators,
 * not claimed as real filesystem or MySQL concurrency evidence. Formal tests belong to Flow. */
class PersonalWorkspaceNativeOutputFenceTest {
    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void generationRotatedDuringStoreCannotInsertOutputOrCommitStaging() throws Exception {
        var f=fixture();byte[] bytes=png();String hash=hash(bytes);
        when(f.storage.store(any(),any(byte[].class),eq("image/png"))).thenAnswer(call->{
            assertFalse(f.transactionActive.get());
            verify(f.executions,never()).lockByTaskRun(anyString(),anyString(),anyString(),anyString(),anyString());
            verify(f.executions,never()).lockOutput(anyString(),anyString(),anyString(),anyString(),anyString());
            f.row.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement");
            return new PersonalWorkspaceStorage.StoredObject("private-output",hash,bytes.length,"image/png");
        });
        assertThrows(RuntimeException.class,()->stage(f,bytes));
        verify(f.executions,never()).insertOutput(any());
        verify(f.manager).rollback(f.status);verify(f.manager,never()).commit(any());
    }

    @Test void preparedBytesCommitOnlyUnderInstallationRuntimeAndOriginalOutputLocks() throws Exception {
        var f=fixture();byte[] bytes=png();String hash=hash(bytes);
        when(f.storage.store(any(),any(byte[].class),eq("image/png"))).thenAnswer(call->{
            assertFalse(f.transactionActive.get());
            return new PersonalWorkspaceStorage.StoredObject("private-output",hash,bytes.length,"image/png");
        });
        var staged=stage(f,bytes);
        assertEquals(hash,staged.sha256());assertEquals("STAGED",staged.state());
        var order=inOrder(f.storage,f.manager,f.installations,f.rows,f.executions);
        order.verify(f.storage).store(any(),any(byte[].class),eq("image/png"));
        order.verify(f.manager).getTransaction(any());
        order.verify(f.installations).lock(PersonalWorkspaceControlledImageV3StartTest.StartFixture.ID);
        order.verify(f.rows).lockInScope("0","client",PersonalWorkspaceControlledImageV3StartTest.StartFixture.AGENT);
        order.verify(f.executions).lockByTaskRun("0","client","owner","task","run");
        order.verify(f.executions).lockOutput("0","client","owner","execution","output_1");
        var captured=ArgumentCaptor.forClass(PersonalWorkspaceExecutionOutputEntity.class);
        order.verify(f.executions).insertOutput(captured.capture());order.verify(f.manager).commit(f.status);
        assertEquals("execution",captured.getValue().getExecutionId());assertEquals("output_1",captured.getValue().getOutputId());
        assertEquals("FILE",captured.getValue().getOutputPurpose());assertEquals("owner",captured.getValue().getOwnerJiacn());
    }

    @Test void originalStagedReceiptReconcilesWithoutStoringAgainAndStillRechecksFence() throws Exception {
        var f=fixture();byte[] bytes=png();var original=output(bytes,"private-original");
        when(f.executions.findOutput("0","client","owner","execution","output_1")).thenReturn(original);
        when(f.executions.lockOutput("0","client","owner","execution","output_1")).thenReturn(original);
        var receipt=stage(f,bytes);
        assertEquals(original.getContentHash(),receipt.sha256());assertEquals("STAGED",receipt.state());
        verify(f.storage,never()).store(any(),any(),any());verify(f.executions,never()).insertOutput(any());
        verify(f.manager).commit(f.status);
    }

    @Test void duplicateOutputWithDifferentPayloadIsPermanentConflictNotRestorage() throws Exception {
        var f=fixture();byte[] bytes=png();var original=output(bytes,"private-original").setContentHash("0".repeat(64));
        when(f.executions.findOutput("0","client","owner","execution","output_1")).thenReturn(original);
        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->stage(f,bytes));
        assertEquals(PersonalWorkspaceExecutionService.Reason.OUTPUT_CONFLICT,failure.getReason());
        verify(f.storage,never()).store(any(),any(),any());verify(f.executions,never()).insertOutput(any());
    }

    @Test void changingOriginalStorageTupleBetweenPreparationAndCommitRejects() throws Exception {
        var f=fixture();byte[] bytes=png();var original=output(bytes,"private-original");
        when(f.executions.findOutput("0","client","owner","execution","output_1")).thenReturn(original);
        when(f.executions.lockOutput("0","client","owner","execution","output_1"))
                .thenReturn(output(bytes,"different-storage-tuple"));
        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->stage(f,bytes));
        assertEquals(PersonalWorkspaceExecutionService.Reason.OUTPUT_CONFLICT,failure.getReason());
        verify(f.storage,never()).store(any(),any(),any());verify(f.executions,never()).insertOutput(any());
        verify(f.manager).rollback(f.status);
    }

    @Test void privateArchiveCannotBypassPersistentFenceAndContainsNoStorageIo() throws Exception {
        var f=fixture();byte[] bytes=png();var original=output(bytes,"private-original");
        original.setOriginalFilename("output.png");
        when(f.executions.lockOutputs("0","client","owner","execution")).thenReturn(java.util.List.of(original));
        String manifest="pwe_m_"+hash(("task\nrun\noutput_1\n"+hash(bytes)+"\n"+bytes.length+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        f.row.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement");
        assertThrows(RuntimeException.class,()->f.service.commitOutputs(f.scope,"task","run",manifest,
                java.util.List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash(bytes),bytes.length))));
        verify(f.executions,never()).lockOutputs(anyString(),anyString(),anyString(),anyString());
        verify(f.executions,never()).updateOutput(any());verify(f.storage,never()).store(any(),any(),any());
        verify(f.storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(f.manager).rollback(f.status);
    }

    private static PersonalWorkspaceControlledImageV3StartTest.StartFixture fixture() throws Exception {
        var f=new PersonalWorkspaceControlledImageV3StartTest.StartFixture();
        f.execution.setExecutionMode("PRIVATE").setOutputContentMimeType("image/png");
        when(f.executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(f.execution);
        return f;
    }
    private static PersonalWorkspaceExecutionService.StagedOutput stage(
            PersonalWorkspaceControlledImageV3StartTest.StartFixture f,byte[] bytes) {
        return f.service.stageOutput(f.scope,"task","run","output_1","output.png","image/png",bytes);
    }
    private static PersonalWorkspaceExecutionOutputEntity output(byte[] bytes,String uri) throws Exception {
        var row=new PersonalWorkspaceExecutionOutputEntity().setExecutionId("execution").setOutputId("output_1")
                .setOwnerJiacn("owner").setContentHash(hash(bytes)).setByteLength((long)bytes.length)
                .setContentMimeType("image/png").setStorageUri(uri).setOutputPurpose("FILE").setOutputState("STAGED");
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static String hash(byte[] bytes) throws Exception {
        return PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(bytes);
    }
    private static byte[] png() throws Exception {
        var bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",bytes));
        return bytes.toByteArray();
    }
}
