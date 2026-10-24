package cn.jia.agent.skill;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Content-addressed local objects. A stored object is not purchase/install authorization.
 * The root is provisioned separately; no startup mkdir, URL fetching or arbitrary path input. */
public final class SkillPackageStore {
    // ByteBuffer.allocate takes an int; this is a representation boundary, not a package quota.
    public static final int MAX_PACKAGE_BYTES = Integer.MAX_VALUE;
    private final Path root;
    public SkillPackageStore(Path root) {
        require(root != null && root.isAbsolute() && root.normalize().equals(root),503,"SKILL_PACKAGE_STORE_INVALID");
        this.root = root;
    }
    public byte[] read(byte[] digest, long size) {
        String hex = objectKey(digest, size);
        try {
            checkRoot();
            Path object = root.resolve(hex + ".zip");
            require(Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS),503,"SKILL_PACKAGE_UNAVAILABLE");
            checkPermissions(object);
            require(!Files.getPosixFilePermissions(object,LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OWNER_WRITE),503,"SKILL_PACKAGE_STORE_INVALID");
            try (FileChannel channel = FileChannel.open(object, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                require(channel.size() == size,503,"SKILL_PACKAGE_UNAVAILABLE");
                ByteBuffer data = ByteBuffer.allocate((int)size);
                while (data.hasRemaining()) require(channel.read(data) >= 0,503,"SKILL_PACKAGE_UNAVAILABLE");
                require(channel.read(ByteBuffer.allocate(1)) == -1,503,"SKILL_PACKAGE_UNAVAILABLE");
                byte[] bytes = data.array();
                require(Arrays.equals(sha256(bytes), digest),503,"SKILL_PACKAGE_UNAVAILABLE");
                return bytes;
            }
        } catch (IOException e) { throw new SkillMarketplaceException(503,"SKILL_PACKAGE_UNAVAILABLE"); }
    }
    /** Trusted maintenance primitive only; no public HTTP endpoint or DB publication implied.
     * Atomic hard-link promotion never replaces an existing hash object, even under concurrency. */
    public String publish(byte[] bytes) {
        require(bytes != null,400,"SKILL_PACKAGE_INVALID");
        byte[] digest = sha256(bytes); String hex = objectKey(digest,bytes.length);
        Path temporary = null;
        try {
            checkRoot();
            temporary = Files.createTempFile(root,".package-",".tmp");
            try (FileChannel channel = FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.hasRemaining()) channel.write(data);
                channel.force(true);
            }
            require(Arrays.equals(sha256(Files.readAllBytes(temporary)),digest),503,"SKILL_PACKAGE_UNAVAILABLE");
            Files.setPosixFilePermissions(temporary,Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ,PosixFilePermission.OTHERS_READ));
            try { Files.createLink(root.resolve(hex+".zip"),temporary); }
            catch (FileAlreadyExistsException alreadyPublished) { /* Verify identical existing bytes below. */ }
            read(digest,bytes.length);
            try (FileChannel directory=FileChannel.open(root,StandardOpenOption.READ)) { directory.force(true); }
            return hex;
        } catch (IOException e) { throw new SkillMarketplaceException(503,"SKILL_PACKAGE_UNAVAILABLE"); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* orphan is never authorized */ } }
    }
    private void checkRoot() throws IOException {
        for(Path p=root;p!=null;p=p.getParent())
            require(!Files.isSymbolicLink(p),503,"SKILL_PACKAGE_STORE_INVALID");
        require(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS),503,"SKILL_PACKAGE_STORE_INVALID");
        checkPermissions(root);
    }
    private static void checkPermissions(Path p) throws IOException {
        var permissions=Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS);
        require(!permissions.contains(PosixFilePermission.GROUP_WRITE)
                && !permissions.contains(PosixFilePermission.OTHERS_WRITE),503,"SKILL_PACKAGE_STORE_INVALID");
    }
    private static String objectKey(byte[] digest,long size) {
        require(digest!=null && digest.length==32 && size>0 && size<=MAX_PACKAGE_BYTES,503,"SKILL_PACKAGE_UNAVAILABLE");
        return HexFormat.of().formatHex(digest);
    }
    static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
