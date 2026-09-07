package cn.jia.agent.service;

import cn.jia.agent.common.AgentErrorConstants;
import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentHostedProfilePublisher {
    private static final String DEFAULT_SECTION = """
            [default]
            codexBin=/usr/local/bin/codex
            codexWorkdir=/home/isp
            codexSandbox=workspace-write
            codexApproval=never
            codexSessionMode=resume
            codexTimeoutMs=900000
            """.stripTrailing();

    private final Path runtimeDir;
    private final Path profilesFile;
    private final Path clientsDir;
    static final String CAPABILITY_MANIFEST = "hosted-runtime-capabilities.properties";
    private static final String CAPABILITY_VERSION = "1";
    private static final Pattern DEFAULT_HEADER = Pattern.compile(
            "\\[(?:default|profile\\.default|agent\\.default)]");
    private static final Pattern PROFILE_HEADER = Pattern.compile(
            "\\[(agent|profile)\\.([^]]+)]");
    private static final List<String> CAPABILITY_KEYS = List.of(
            "contractVersion", "perProfileApiKey", "disabledStage",
            "hotReloadDisconnect", "runtimeSha256");

    private final Path runtimeSource;
    private final Path capabilityManifest;

    @Autowired
    public AgentHostedProfilePublisher(
            @Value("${jia.agent.hosted.runtime-dir:/home/isp/apps/codex-ws-agent}") String runtimeDir,
            @Value("${jia.agent.hosted.clients-dir:/home/isp/hosts/cyf/agent-clients}") String clientsDir) {
        this(Path.of(runtimeDir), Path.of(clientsDir));
    }

    AgentHostedProfilePublisher(Path runtimeDir, Path clientsDir) {
        this.runtimeDir = runtimeDir;
        this.profilesFile = runtimeDir.resolve("codex-profiles.conf");
        this.clientsDir = clientsDir;
        this.runtimeSource = runtimeDir.resolve("agent-client.mjs");
        this.capabilityManifest = runtimeDir.resolve(CAPABILITY_MANIFEST);
    }

    public PublishedPaths publish(AgentHostedProfileEntity hosted, AgentPersonaEntity persona,
            long expectedGeneration, long nextGeneration, boolean enabled, String apiKey) {
        requireExactHosted(hosted);
        AgentHostedGeneration.requireSuccessor(expectedGeneration, nextGeneration);
        if (!safeConfigValue(apiKey)) fail("Dedicated hosted API key is unavailable");
        if (persona == null || !safeConfigValue(persona.getName())
                || (persona.getTitle() != null && !safeConfigValue(persona.getTitle()))) {
            fail("Hosted persona configuration is invalid");
        }
        verifyRuntimeCapabilities();
        Path workdir = workdir(hosted);
        Path codexHome = codexHome(hosted);
        try {
            Files.createDirectories(workdir);
            Files.createDirectories(codexHome);
            copyBootstrap("config.toml", codexHome);
            copyBootstrap("auth.json", codexHome);
            Files.createDirectories(profilesFile.getParent());
            Path lockPath = lockPath();
            boolean existed;
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                String current = Files.exists(profilesFile)
                        ? Files.readString(profilesFile, StandardCharsets.UTF_8) : DEFAULT_SECTION;
                String desired = section(hosted, persona, workdir, codexHome, enabled, apiKey, nextGeneration);
                Replacement replacement = replaceCas(
                        current, hosted, expectedGeneration, nextGeneration, desired);
                if (!replacement.content().equals(current)) atomicPublish(replacement.content());
                existed = replacement.existed();
            }
            return paths(hosted, !existed, existed);
        } catch (IOException e) {
            fail("Hosted profile publication failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    public PublishedPaths inspectExisting(AgentHostedProfileEntity hosted, long expectedGeneration) {
        requireExactHosted(hosted);
        AgentHostedGeneration.requireNonNegative(expectedGeneration);
        verifyRuntimeCapabilities();
        try {
            if (!Files.exists(profilesFile)) {
                fail("Hosted profile durable state has no published section");
            }
            try (FileChannel lockChannel = FileChannel.open(lockPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                if (!Files.exists(profilesFile)) {
                    fail("Hosted profile durable state has no published section");
                }
                String current = Files.readString(profilesFile, StandardCharsets.UTF_8);
                ProfileIndex index = profileIndex(current, hosted);
                if (index.target() == null) {
                    fail("Hosted profile durable state has no published section");
                }
                validateTarget(current, index.target(), hosted);
                long actualGeneration = markerGeneration(index.target().body(current));
                if (actualGeneration != expectedGeneration) {
                    fail("Hosted profile durable generation does not match published section");
                }
            }
            return paths(hosted, false, true);
        } catch (IOException e) {
            fail("Hosted profile inspection failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private void verifyRuntimeCapabilities() {
        try {
            byte[] runtimeBytes = Files.readAllBytes(runtimeSource);
            List<String> lines = Files.readAllLines(capabilityManifest, StandardCharsets.UTF_8);
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int separator = line.indexOf('=');
                if (separator <= 0 || separator != line.lastIndexOf('=')) {
                    fail("Hosted runtime capability contract is malformed");
                }
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (!CAPABILITY_KEYS.contains(key) || value.isEmpty()
                        || !key.equals(key.strip()) || !value.equals(value.strip())
                        || values.putIfAbsent(key, value) != null) {
                    fail("Hosted runtime capability contract is malformed");
                }
            }
            if (!values.keySet().equals(new java.util.LinkedHashSet<>(CAPABILITY_KEYS))
                    || !CAPABILITY_VERSION.equals(values.get("contractVersion"))
                    || !"true".equals(values.get("perProfileApiKey"))
                    || !"true".equals(values.get("disabledStage"))
                    || !"true".equals(values.get("hotReloadDisconnect"))) {
                fail("Hosted runtime capability contract is unsupported");
            }
            String expectedDigest = values.get("runtimeSha256");
            String actualDigest = sha256(runtimeBytes);
            if (!expectedDigest.matches("[0-9a-f]{64}") || !expectedDigest.equals(actualDigest)) {
                fail("Hosted runtime capability contract does not match authoritative runtime");
            }
        } catch (IOException e) {
            fail("Authoritative hosted runtime capability contract is unavailable");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Replacement replaceCas(String profiles, AgentHostedProfileEntity hosted,
            long expectedGeneration, long nextGeneration, String desired) {
        ProfileIndex index = profileIndex(profiles, hosted);
        Section target = index.target();
        if (target == null) {
            if (expectedGeneration != 0) fail("Hosted profile generation conflict: section missing");
            return new Replacement(profiles.stripTrailing() + "\n\n" + desired.strip() + "\n", false);
        }
        validateTarget(profiles, target, hosted);
        String existing = target.body(profiles);
        long actual = markerGeneration(existing);
        if (actual == nextGeneration && normalize(existing).equals(normalize(desired))) {
            return new Replacement(profiles, true);
        }
        if (actual != expectedGeneration) fail("Hosted profile generation conflict");
        return new Replacement(profiles.substring(0, target.start()) + desired.strip() + "\n"
                + profiles.substring(target.end()), true);
    }

    private ProfileIndex profileIndex(String profiles, AgentHostedProfileEntity hosted) {
        List<Header> headers = headers(profiles);
        Section target = null;
        for (int index = 0; index < headers.size(); index++) {
            Header header = headers.get(index);
            int end = index + 1 < headers.size() ? headers.get(index + 1).start() : profiles.length();
            Section section = new Section(header.start(), end, header.family(), header.key(),
                    assignments(profiles.substring(header.start(), end)));
            long agentIdCount = section.assignments().stream()
                    .filter(assignment -> "agentId".equals(assignment.key())).count();
            if (agentIdCount > 1) fail("Hosted profile agentId is duplicated or ambiguous");
            boolean intendedTarget = "agent".equals(section.family())
                    && hosted.getProfileKey().equals(section.key());
            if (hosted.getProfileKey().equals(section.key()) && !intendedTarget) {
                fail("Hosted profile target section is ambiguous");
            }
            String body = section.body(profiles);
            boolean sameBinding = hasExactLine(body,
                    "# cyfHostedBindingId=" + hosted.getBindingId());
            boolean sameCanonicalMarker = hasExactLine(body,
                    "# cyfHostedCanonicalAgentId=" + hosted.getCanonicalAgentId());
            boolean sameAgent = section.assignments().stream().anyMatch(assignment ->
                    "agentId".equals(assignment.key())
                            && hosted.getCanonicalAgentId().equals(assignment.value()));
            if ((sameBinding || sameCanonicalMarker || sameAgent) && !intendedTarget) {
                fail("Hosted profile identity collision");
            }
            if (intendedTarget) {
                if (target != null) fail("Duplicate hosted profile key");
                target = section;
            }
        }
        return new ProfileIndex(target);
    }

    private List<Header> headers(String text) {
        List<Header> result = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline;
            String line = trimRuntimeWhitespace(text.substring(lineStart, lineEnd));
            if (DEFAULT_HEADER.matcher(line).matches()) {
                result.add(new Header(lineStart, "default", null));
            } else {
                Matcher profile = PROFILE_HEADER.matcher(line);
                if (profile.matches()) {
                    result.add(new Header(lineStart, profile.group(1), profile.group(2)));
                }
            }
            if (newline < 0) break;
            lineStart = newline + 1;
        }
        return result;
    }

    private List<Assignment> assignments(String section) {
        List<Assignment> result = new ArrayList<>();
        int lineStart = section.indexOf('\n') + 1;
        while (lineStart > 0 && lineStart <= section.length()) {
            int newline = section.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? section.length() : newline;
            String trimmed = trimRuntimeWhitespace(section.substring(lineStart, lineEnd));
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                int separator = trimmed.indexOf('=');
                if (separator >= 0) {
                    result.add(new Assignment(
                            trimRuntimeWhitespace(trimmed.substring(0, separator)),
                            parseRuntimeScalar(trimmed.substring(separator + 1))));
                }
            }
            if (newline < 0) break;
            lineStart = newline + 1;
        }
        return result;
    }

    private void validateTarget(String profiles, Section target, AgentHostedProfileEntity hosted) {
        String existing = target.body(profiles);
        if (countExactLine(existing, "# cyfHostedBindingId=" + hosted.getBindingId()) != 1
                || countExactLine(existing,
                    "# cyfHostedCanonicalAgentId=" + hosted.getCanonicalAgentId()) != 1) {
            fail("Legacy or colliding hosted profile is not adoptable");
        }
        List<String> agentIds = target.assignments().stream()
                .filter(assignment -> "agentId".equals(assignment.key()))
                .map(Assignment::value).toList();
        if (agentIds.size() != 1 || !hosted.getCanonicalAgentId().equals(agentIds.getFirst())) {
            fail("Hosted profile target identity is invalid");
        }
    }

    private long markerGeneration(String section) {
        String prefix = "# cyfHostedGeneration=";
        List<String> values = section.lines().filter(line -> line.startsWith(prefix)).toList();
        if (values.size() != 1) fail("Hosted profile generation marker is missing or duplicated");
        try {
            return Long.parseLong(values.getFirst().substring(prefix.length()));
        } catch (NumberFormatException e) {
            fail("Hosted profile generation marker is invalid");
            return -1;
        }
    }

    private String section(AgentHostedProfileEntity hosted, AgentPersonaEntity persona,
            Path workdir, Path codexHome, boolean enabled, String apiKey, long generation) {
        return """
                [agent.%s]
                # cyfHostedBindingId=%d
                # cyfHostedCanonicalAgentId=%s
                # cyfHostedGeneration=%d
                agentId=%s
                codexWorkdir=%s
                agentName=%s
                personaName=%s
                codexHome=%s
                enabled=%s
                apiKey=%s
                """.formatted(hosted.getProfileKey(), hosted.getBindingId(), hosted.getCanonicalAgentId(),
                generation, hosted.getCanonicalAgentId(), workdir, persona.getName(),
                persona.getTitle() == null || persona.getTitle().isBlank() ? persona.getName() : persona.getTitle(),
                codexHome, enabled, apiKey).stripTrailing();
    }

    private void atomicPublish(String content) throws IOException {
        Path directory = profilesFile.getParent();
        Path temp = Files.createTempFile(directory, profilesFile.getFileName() + ".", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temp, profilesFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic move is unsupported", e);
            }
            try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
                directoryChannel.force(true);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void copyBootstrap(String name, Path codexHome) throws IOException {
        Path source = runtimeDir.resolve(".codex").resolve(name);
        Path target = codexHome.resolve(name);
        if (Files.exists(source) && !Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private PublishedPaths paths(AgentHostedProfileEntity hosted, boolean created, boolean alreadyExists) {
        return new PublishedPaths(workdir(hosted).toString(), codexHome(hosted).toString(),
                profilesFile.toString(), created, alreadyExists);
    }

    private Path workdir(AgentHostedProfileEntity hosted) {
        return clientsDir.resolve(hosted.getCanonicalAgentId());
    }

    private Path codexHome(AgentHostedProfileEntity hosted) {
        return runtimeDir.resolve(".codex-hosted-" + hosted.getProfileKey());
    }

    private Path lockPath() {
        return profilesFile.resolveSibling(profilesFile.getFileName() + ".lock");
    }

    private boolean hasExactLine(String body, String expected) {
        return countExactLine(body, expected) > 0;
    }

    private long countExactLine(String body, String expected) {
        return body.lines().filter(expected::equals).count();
    }

    private void requireExactHosted(AgentHostedProfileEntity h) {
        if (h == null || h.getBindingId() == null || h.getBindingId() <= 0
                || blank(h.getTenantId()) || blank(h.getClientId()) || blank(h.getOwnerJiacn())
                || blank(h.getCanonicalAgentId()) || blank(h.getProfileKey()) || blank(h.getApiKeyId())
                || !h.getCanonicalAgentId().matches("agt_[0-9a-f]{32}")) {
            fail("Hosted profile durable identity is incomplete");
        }
        AgentHostedBindingTransaction.Scope scope;
        try {
            scope = new AgentHostedBindingTransaction.Scope(
                    h.getTenantId(), h.getClientId(), h.getOwnerJiacn());
        } catch (IllegalArgumentException invalidScope) {
            fail("Hosted profile durable scope is invalid");
            return;
        }
        if (!h.getProfileKey().equals(AgentHostedBindingTransaction.scopeDigest(scope) + h.getBindingId())) {
            fail("Hosted profile durable key is invalid");
        }
    }

    private String trimRuntimeWhitespace(String value) {
        int start = 0;
        while (start < value.length()) {
            int codePoint = value.codePointAt(start);
            if (!isEcmaScriptWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        int end = value.length();
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isEcmaScriptWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private String parseRuntimeScalar(String rawValue) {
        String value = trimRuntimeWhitespace(rawValue);
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private boolean isEcmaScriptWhitespace(int codePoint) {
        return codePoint == 0x0009 || codePoint == 0x000B || codePoint == 0x000C
                || codePoint == 0x0020 || codePoint == 0x00A0 || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x202F || codePoint == 0x205F || codePoint == 0x3000
                || codePoint == 0xFEFF || codePoint == 0x000A || codePoint == 0x000D
                || codePoint == 0x2028 || codePoint == 0x2029;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank() || !value.equals(value.strip());
    }

    private boolean safeConfigValue(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private String normalize(String value) {
        return value.strip().replace("\r\n", "\n");
    }

    private void fail(String message) {
        throw new AgentBizException(AgentErrorConstants.AGENT_ERROR, message);
    }

    private record Header(int start, String family, String key) {
    }

    private record Assignment(String key, String value) {
    }

    private record Section(int start, int end, String family, String key,
            List<Assignment> assignments) {
        private String body(String profiles) {
            return profiles.substring(start, end);
        }
    }

    private record ProfileIndex(Section target) {
    }

    private record Replacement(String content, boolean existed) {
    }

    public record PublishedPaths(String workdir, String codexHome, String profilesFile,
            boolean created, boolean alreadyExists) {
    }
}
