package cn.jia.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillPackageStoreTest {
    @TempDir Path root;
    private final byte[] bytes="new skill package".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    @Test void publishReadAndIdenticalReplay() throws Exception {
        var store=new SkillPackageStore(root); var hash=SkillPackageStore.sha256(bytes);
        String key=store.publish(bytes); assertEquals(HexFormat.of().formatHex(hash),key);
        assertArrayEquals(bytes,store.read(hash,bytes.length));
        assertEquals(key,store.publish(bytes));
        try(var files=Files.list(root)) { assertEquals(1,files.count()); }
        assertFalse(Files.getPosixFilePermissions(root.resolve(key+".zip")).contains(PosixFilePermission.OWNER_WRITE));
    }
    @Test void concurrentPublicationDoesNotReplaceObject() throws Exception {
        var store=new SkillPackageStore(root);
        try(var workers=Executors.newFixedThreadPool(3)) {
            var jobs=workers.invokeAll(Collections.nCopies(6,()->store.publish(bytes)));
            String key=jobs.getFirst().get(); for(var job:jobs) assertEquals(key,job.get());
            assertArrayEquals(bytes,store.read(SkillPackageStore.sha256(bytes),bytes.length));
        }
        try(var files=Files.list(root)) { assertEquals(1,files.count()); }
    }
    @Test void cannotConstructRelativeOrNoncanonicalRoot() {
        assertThrows(SkillMarketplaceException.class,()->new SkillPackageStore(Path.of("relative")));
        assertThrows(SkillMarketplaceException.class,()->new SkillPackageStore(root.resolve("../escape")));
    }
    @Test void noAutomaticRootCreation() {
        Path absent=root.resolve("not-provisioned");var store=new SkillPackageStore(absent);
        assertThrows(SkillMarketplaceException.class,()->store.publish(bytes));assertFalse(Files.exists(absent));
    }
    @Test void symlinkRootOrAncestorRejected() throws Exception {
        Path real=Files.createDirectory(root.resolve("real"));Path link=root.resolve("link");Files.createSymbolicLink(link,real);
        assertThrows(SkillMarketplaceException.class,()->new SkillPackageStore(link).publish(bytes));
        Path nested=Files.createDirectory(real.resolve("nested"));
        assertThrows(SkillMarketplaceException.class,()->new SkillPackageStore(link.resolve("nested")).publish(bytes));
        assertTrue(Files.exists(nested));
    }
    @Test void symlinkObjectRejectedEvenWhenHashMatches() throws Exception {
        Path outside=Files.createTempFile(root,"outside-",".zip");Files.write(outside,bytes);
        byte[] hash=SkillPackageStore.sha256(bytes);Files.createSymbolicLink(root.resolve(HexFormat.of().formatHex(hash)+".zip"),outside);
        assertThrows(SkillMarketplaceException.class,()->new SkillPackageStore(root).read(hash,bytes.length));
    }
    @Test void writableRootAndWritableObjectRejected() throws Exception {
        var store=new SkillPackageStore(root);String key=store.publish(bytes);Path object=root.resolve(key+".zip");
        Files.setPosixFilePermissions(object,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        assertThrows(SkillMarketplaceException.class,()->store.read(SkillPackageStore.sha256(bytes),bytes.length));
        Files.setPosixFilePermissions(root,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE,PosixFilePermission.GROUP_WRITE));
        assertThrows(SkillMarketplaceException.class,()->store.publish(bytes));
        Files.setPosixFilePermissions(root,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
    }
    @Test void changedBytesAndWrongSizeRejected() throws Exception {
        var store=new SkillPackageStore(root);String key=store.publish(bytes);Path object=root.resolve(key+".zip");
        assertThrows(SkillMarketplaceException.class,()->store.read(SkillPackageStore.sha256(bytes),bytes.length+1));
        Files.setPosixFilePermissions(object,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        byte[] altered=bytes.clone();altered[0]^=1;Files.write(object,altered);Files.setPosixFilePermissions(object,Set.of(PosixFilePermission.OWNER_READ));
        assertThrows(SkillMarketplaceException.class,()->store.read(SkillPackageStore.sha256(bytes),bytes.length));
        assertThrows(SkillMarketplaceException.class,()->store.publish(bytes)); // must not replace corrupt hash object
        assertArrayEquals(altered,Files.readAllBytes(object));
    }
    @Test void digestAndExistingArchiveBoundaryCheckedBeforeRead() {
        var store=new SkillPackageStore(root);
        for(long size:new long[]{0,-1,SkillPackageStore.MAX_PACKAGE_BYTES+1L})
            assertThrows(SkillMarketplaceException.class,()->store.read(new byte[32],size));
        assertThrows(SkillMarketplaceException.class,()->store.read(new byte[31],1));
        assertThrows(SkillMarketplaceException.class,()->store.publish(new byte[0]));
    }
}
