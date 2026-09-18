package cn.jia.agent.service.impl;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable owner-scoped private file storage. The URI contains only a derived scope digest. */
public final class FileSystemPersonalWorkspaceStorage implements PersonalWorkspaceStorage {
    private static final String SCHEME = "cyf-personal-workspace";
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(PosixFilePermission.OWNER_READ);
    private final Path root; private final Object rootKey; private final long maximum; private final Set<String> allowed;

    public FileSystemPersonalWorkspaceStorage(Path configuredRoot, long maximum, Set<String> allowedMimeTypes) {
        if (configuredRoot == null || !configuredRoot.isAbsolute() || configuredRoot.normalize().getParent() == null || maximum < 1) invalid();
        if (allowedMimeTypes == null || allowedMimeTypes.isEmpty()) invalid();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String mime : allowedMimeTypes) if (!validMime(mime) || !normalized.add(mime)) invalid();
        this.root = createRoot(configuredRoot.normalize()); this.rootKey = attributes(root).fileKey();
        this.maximum = maximum; this.allowed = Set.copyOf(normalized);
    }

    @Override public StoredObject store(Scope scope, byte[] content, String mimeType) {
        validateScope(scope); validateMime(mimeType); if (content == null || content.length > maximum) invalid();
        verifyRoot(); String hash = sha(content); String key = scopeKey(scope); Path directory = directory(key, hash); ensureDirectory(directory);
        Path target = objectPath(directory, hash);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verifyExisting(target, hash, content.length);
        else {
            Path temp = null;
            try {
                temp = Files.createTempFile(directory, ".upload-", ".tmp"); verifyRegular(temp); write(temp, content); permissions(temp, FILE_PERMS);
                try { Files.createLink(target, temp); } catch (FileAlreadyExistsException raced) { verifyExisting(target, hash, content.length); }
            } catch (IOException failure) { unavailable(failure); }
            finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { } }
            verifyExisting(target, hash, content.length);
        }
        verifyRoot(); return new StoredObject(uri(key, hash), hash, content.length, mimeType);
    }

    @Override public StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType) {
        validateScope(scope); validateMime(expectedMimeType);
        if (!SHA.matcher(Objects.requireNonNullElse(expectedSha256, "")).matches() || expectedByteLength < 0 || expectedByteLength > maximum) invalid();
        verifyRoot(); UriParts parts = parse(storageUri);
        if (!constant(scopeKey(scope), parts.scopeKey()) || !constant(expectedSha256, parts.hash())) corrupt();
        byte[] bytes = read(objectPath(requireDirectory(parts.scopeKey(), parts.hash()), parts.hash()));
        if (bytes.length != expectedByteLength || !constant(sha(bytes), expectedSha256)) corrupt();
        verifyRoot(); return new StoredContent(bytes, expectedSha256, bytes.length, expectedMimeType);
    }

    private Path directory(String scopeKey, String hash) { return root.resolve("v1").resolve(scopeKey.substring(0,2)).resolve(scopeKey).resolve(hash.substring(0,2)).normalize(); }
    private Path objectPath(Path directory, String hash) { Path target=directory.resolve(hash).normalize(); if (!target.startsWith(root) || !Objects.equals(target.getParent(), directory)) corrupt(); return target; }
    private void ensureDirectory(Path target) { verifyRoot(); Path current=root; for (Path part:root.relativize(target)) { current=current.resolve(part); if (Files.exists(current,LinkOption.NOFOLLOW_LINKS)) { BasicFileAttributes a=attributes(current); if (a.isSymbolicLink() || !a.isDirectory()) corrupt(); } else try { Files.createDirectory(current); } catch (FileAlreadyExistsException raced) { BasicFileAttributes a=attributes(current); if(a.isSymbolicLink()||!a.isDirectory()) corrupt(); } catch(IOException e){unavailable(e);} permissions(current,DIR_PERMS); } }
    private Path requireDirectory(String key, String hash) { Path d=directory(key,hash); verifyRoot(); Path current=root; for(Path part:root.relativize(d)){current=current.resolve(part); BasicFileAttributes a=attributes(current);if(a.isSymbolicLink()||!a.isDirectory())corrupt();} return d; }
    private static Path createRoot(Path target) { Path current=target.getRoot(); if(current==null) invalid(); for(Path part:target){current=current.resolve(part);if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)){BasicFileAttributes a=attributes(current);if(a.isSymbolicLink()||!a.isDirectory())invalid();}else try{Files.createDirectory(current);}catch(FileAlreadyExistsException raced){BasicFileAttributes a=attributes(current);if(a.isSymbolicLink()||!a.isDirectory())invalid();}catch(IOException e){unavailable(e);} permissions(current,DIR_PERMS);}return target; }
    private void verifyRoot(){BasicFileAttributes a=attributes(root);if(a.isSymbolicLink()||!a.isDirectory()||(rootKey!=null&&!Objects.equals(rootKey,a.fileKey())))corrupt();}
    private void verifyExisting(Path target,String hash,long length){byte[] bytes=read(target);if(bytes.length!=length||!constant(sha(bytes),hash))corrupt();permissions(target,FILE_PERMS);}
    private byte[] read(Path target){BasicFileAttributes a=attributes(target);if(a.isSymbolicLink()||!a.isRegularFile()||a.size()>maximum)corrupt();try(FileChannel c=FileChannel.open(target,Set.<OpenOption>of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(a.size(),65536))){ByteBuffer b=ByteBuffer.allocate(8192);long count=0;while(c.read(b)!=-1){b.flip();count+=b.remaining();if(count>maximum)corrupt();out.write(b.array(),b.position(),b.remaining());b.clear();}return out.toByteArray();}catch(IOException e){unavailable(e);return null;}}
    private static void write(Path target,byte[] bytes)throws IOException{try(FileChannel c=FileChannel.open(target,Set.<OpenOption>of(StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS))){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())c.write(b);c.force(true);}}
    private static void verifyRegular(Path p){BasicFileAttributes a=attributes(p);if(a.isSymbolicLink()||!a.isRegularFile())corrupt();}
    private static BasicFileAttributes attributes(Path p){try{return Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}catch(IOException e){unavailable(e);return null;}}
    private static void permissions(Path p,Set<PosixFilePermission> permissions){try{Files.setPosixFilePermissions(p,permissions);}catch(IOException|UnsupportedOperationException e){unavailable(e);}}
    private void validateMime(String mime){if(!validMime(mime)||!allowed.contains(mime))invalid();}
    private static boolean validMime(String mime){return mime!=null&&MIME.matcher(mime).matches();}
    private static void validateScope(Scope s){if(s==null||!"0".equals(s.tenantId())||!identifier(s.clientId(),50)||!identifier(s.ownerJiacn(),50)||"0".equals(s.ownerJiacn()))invalid();}
    private static boolean identifier(String s,int max){return s!=null&&!s.isBlank()&&s.equals(s.strip())&&s.codePointCount(0,s.length())<=max&&!s.chars().anyMatch(Character::isISOControl);}
    private static String scopeKey(Scope scope){MessageDigest d=digest();component(d,scope.tenantId());component(d,scope.clientId());component(d,scope.ownerJiacn());return HexFormat.of().formatHex(d.digest());}
    private static void component(MessageDigest d,String value){byte[] b=value.getBytes(StandardCharsets.UTF_8);d.update(ByteBuffer.allocate(Integer.BYTES).putInt(b.length).array());d.update(b);}
    private static String sha(byte[] b){return HexFormat.of().formatHex(digest().digest(b));}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String uri(String key,String hash){return URI.create(SCHEME+"://"+key+"/"+hash).toASCIIString();}
    private static UriParts parse(String value){try{URI u=URI.create(value);String p=u.getRawPath();if(!SCHEME.equals(u.getScheme())||u.getUserInfo()!=null||u.getPort()!=-1||u.getQuery()!=null||u.getFragment()!=null||!SHA.matcher(Objects.requireNonNullElse(u.getHost(),"")).matches()||p==null||p.length()!=65||p.charAt(0)!='/'||!SHA.matcher(p.substring(1)).matches())invalid();return new UriParts(u.getHost(),p.substring(1));}catch(IllegalArgumentException e){invalid();return null;}}
    private static boolean constant(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    private static void invalid(){throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.BAD_REQUEST);}
    private static void corrupt(){throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_CORRUPT);}
    private static void unavailable(Throwable... ignored){throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_UNAVAILABLE);}
    private record UriParts(String scopeKey,String hash){}
}
