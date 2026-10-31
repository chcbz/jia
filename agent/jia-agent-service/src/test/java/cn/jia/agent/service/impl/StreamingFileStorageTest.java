package cn.jia.agent.service.impl;

import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StreamingFileStorageTest {
    @TempDir Path temporary;
    private static final long LENGTH = 20L * 1024 * 1024 + 7;
    private record Stored(String uri, String hash, long length) { }
    private interface Store {
        Stored upload(InputStream input, long length);
        void download(Stored stored, OutputStream output);
        void wrongOwner(Stored stored, OutputStream output);
    }
    private List<Store> stores() {
        var workspace = new FileSystemPersonalWorkspaceStorage(temporary.resolve("workspace"), 16);
        var artifacts = new FileSystemAgentTaskArtifactStorage(temporary.resolve("artifacts"), 16);
        var owner = new PersonalWorkspaceStorage.Scope("0", "client", "owner");
        var task = new AgentTaskArtifactStorage.Scope("0", "client", "owner", "task");
        return List.of(new Store() {
            public Stored upload(InputStream input, long length) {
                var s = workspace.storeStream(owner, input, length, "application/octet-stream");
                return new Stored(s.storageUri(), s.sha256(), s.byteLength());
            }
            public void download(Stored s, OutputStream output) {
                workspace.copyTo(owner, s.uri(), s.hash(), s.length(), "application/octet-stream", output);
            }
            public void wrongOwner(Stored s, OutputStream output) {
                workspace.copyTo(new PersonalWorkspaceStorage.Scope("0", "client", "other"),
                        s.uri(), s.hash(), s.length(), "application/octet-stream", output);
            }
        }, new Store() {
            public Stored upload(InputStream input, long length) {
                var s = artifacts.storeStream(task, input, length, "application/octet-stream");
                return new Stored(s.storageUri(), s.sha256(), s.byteLength());
            }
            public void download(Stored s, OutputStream output) {
                artifacts.copyTo(task, s.uri(), s.hash(), s.length(), "application/octet-stream", output);
            }
            public void wrongOwner(Stored s, OutputStream output) {
                artifacts.copyTo(new AgentTaskArtifactStorage.Scope("0", "client", "other", "task"),
                        s.uri(), s.hash(), s.length(), "application/octet-stream", output);
            }
        });
    }

    @Test void largeFilesUseStreamsAndDeduplicateBeyondTheOldByteArrayCeiling() throws Exception {
        for (Store store : stores()) {
            var saved = store.upload(generated(LENGTH), LENGTH);
            assertEquals(LENGTH, saved.length());
            assertEquals(saved, store.upload(generated(LENGTH), LENGTH));
            var digest = MessageDigest.getInstance("SHA-256");
            var count = new java.util.concurrent.atomic.AtomicLong();
            OutputStream sink = new OutputStream() {
                public void write(int value) { count.incrementAndGet(); }
                public void write(byte[] bytes, int offset, int length) { count.addAndGet(length); }
            };
            store.download(saved, new DigestOutputStream(sink, digest));
            assertEquals(LENGTH, count.get());
            assertEquals(saved.hash(), HexFormat.of().formatHex(digest.digest()));
        }
        assertNoTemporaryFiles();
    }

    @Test void invalidLengthInterruptedUploadAndWrongScopeNeverPublishOrLeakContent() throws Exception {
        for (Store store : stores()) {
            assertThrows(RuntimeException.class, () -> store.upload(generated(3), 4));
            assertThrows(RuntimeException.class, () -> store.upload(generated(4), 3));
            assertThrows(RuntimeException.class, () -> store.upload(new InputStream() {
                public int read() throws IOException { throw new IOException("interrupted upload"); }
            }, 4));
            var saved = store.upload(generated(4), 4);
            OutputStream forbidden = new OutputStream() {
                public void write(int value) { fail("Unverified bytes reached the response"); }
            };
            assertThrows(RuntimeException.class, () -> store.wrongOwner(saved, forbidden));
            assertThrows(RuntimeException.class, () -> store.download(
                    new Stored(saved.uri(), saved.hash(), saved.length() + 1), forbidden));
            assertThrows(RuntimeException.class, () -> store.download(
                    new Stored(saved.uri(), "0".repeat(64), saved.length()), forbidden));
        }
        assertNoTemporaryFiles();
    }

    @Test void corruptedStoredBytesAreRejectedBeforeTheFirstResponseByte() throws Exception {
        for (Store store : stores()) {
            var saved = store.upload(generated(4), 4);
            try (var paths = Files.walk(temporary)) {
                Path object = paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().contains(saved.hash()))
                        .filter(path -> {
                            try { return Files.size(path) == 4; } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                        }).findFirst().orElseThrow();
                Files.setPosixFilePermissions(object, java.util.Set.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                Files.write(object, new byte[]{9, 9, 9, 9});
                assertThrows(RuntimeException.class, () -> store.download(saved, new OutputStream() {
                    public void write(int b) { fail("Corrupt bytes reached the response"); }
                }));
                // Restore the object so the next store's same digest is not selected here.
                Files.delete(object);
            }
        }
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var files = Files.walk(temporary)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".upload-")));
        }
    }
    private static InputStream generated(long length) {
        return new InputStream() {
            long position;
            @Override public int read() { return position == length ? -1 : (int) (position++ % 251); }
            @Override public int read(byte[] bytes, int offset, int count) {
                if (position == length) return -1;
                int n = (int) Math.min(count, length - position);
                for (int i = 0; i < n; i++) bytes[offset + i] = (byte) (position++ % 251);
                return n;
            }
            @Override public byte[] readAllBytes() { throw new AssertionError("Whole-file buffering forbidden"); }
        };
    }
}
