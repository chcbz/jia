package cn.jia.agent.service;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AgentHostedProfilePublisherTest {
    @TempDir Path temp;

    @Test
    void responseContractHasNoCredentialField() {
        Set<String> fields = Arrays.stream(AgentPersonaBindResultDTO.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).collect(Collectors.toSet());
        assertFalse(fields.contains("apiKey"));
        assertFalse(fields.contains("credential"));
        assertFalse(fields.contains("secret"));
    }

    @Test
    void scopeDigestAndBindingProduceIsolatedProfileKeys() {
        String a = AgentHostedBindingTransaction.scopeDigest(new Scope("owner-a", "client", "owner-a")) + 7L;
        String b = AgentHostedBindingTransaction.scopeDigest(new Scope("owner-b", "client", "owner-b")) + 7L;
        String c = AgentHostedBindingTransaction.scopeDigest(new Scope("owner-a", "client", "owner-a")) + 8L;
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertTrue(a.endsWith("7"));
    }

    @Test
    void disabledStageThenEnableUsesGenerationCasAndIsIdempotent() throws Exception {
        Fixture f = fixture();
        f.publisher.publish(f.hosted, f.persona, 0, 1, false, "dedicated-secret");
        String staged = Files.readString(f.profiles);
        assertTrue(staged.contains("enabled=false"));
        assertTrue(staged.contains("# cyfHostedGeneration=1"));
        assertTrue(staged.contains("apiKey=dedicated-secret"));

        f.publisher.publish(f.hosted, f.persona, 0, 1, false, "dedicated-secret");
        assertEquals(staged, Files.readString(f.profiles));

        f.publisher.publish(f.hosted, f.persona, 1, 2, true, "dedicated-secret");
        String enabled = Files.readString(f.profiles);
        assertTrue(enabled.contains("enabled=true"));
        assertTrue(enabled.contains("# cyfHostedGeneration=2"));
    }

    @Test
    void generationConflictAndLegacyCollisionFailClosedWithoutOverwrite() throws Exception {
        Fixture f = fixture();
        f.publisher.publish(f.hosted, f.persona, 0, 1, false, "dedicated-secret");
        String stable = Files.readString(f.profiles);
        assertThrows(AgentBizException.class,
                () -> f.publisher.publish(f.hosted, f.persona, 0, 2, true, "dedicated-secret"));
        assertEquals(stable, Files.readString(f.profiles));

        Files.writeString(f.profiles, "[agent." + f.hosted.getProfileKey() + "]\nagentId="
                + f.hosted.getCanonicalAgentId() + "\nenabled=true\n");
        String legacy = Files.readString(f.profiles);
        assertThrows(AgentBizException.class,
                () -> f.publisher.publish(f.hosted, f.persona, 0, 1, false, "dedicated-secret"));
        assertEquals(legacy, Files.readString(f.profiles));
    }

    @Test
    void missingAuthoritativeCapabilitiesFailsClosedBeforeFileCreation() throws Exception {
        Path runtime = temp.resolve("runtime-missing");
        Files.createDirectories(runtime);
        Files.writeString(runtime.resolve("agent-client.mjs"), "// no profile security support");
        AgentHostedProfilePublisher publisher = new AgentHostedProfilePublisher(runtime, temp.resolve("clients"));
        FixtureData data = data();
        assertThrows(AgentBizException.class,
                () -> publisher.publish(data.hosted, data.persona, 0, 1, false, "secret"));
        assertFalse(Files.exists(runtime.resolve("codex-profiles.conf")));
    }

    @Test
    void malformedFalseAndRuntimeDriftCapabilitiesFailBeforeAnyPublicationDirectory() throws Exception {
        for (String manifest : List.of(
                "contractVersion=1\nperProfileApiKey=false\ndisabledStage=true\nhotReloadDisconnect=true\nruntimeSha256=" + "0".repeat(64),
                "contractVersion =1",
                "contractVersion=2")) {
            Path runtime = temp.resolve("runtime-contract-" + Math.abs(manifest.hashCode()));
            Path clients = temp.resolve("clients-contract-" + Math.abs(manifest.hashCode()));
            Files.createDirectories(runtime);
            Files.writeString(runtime.resolve("agent-client.mjs"), "runtime");
            Files.writeString(runtime.resolve(AgentHostedProfilePublisher.CAPABILITY_MANIFEST), manifest);
            FixtureData data = data();
            assertThrows(AgentBizException.class, () -> new AgentHostedProfilePublisher(runtime, clients)
                    .publish(data.hosted, data.persona, 0, 1, false, "secret"));
            assertFalse(Files.exists(clients));
            assertFalse(Files.exists(runtime.resolve("codex-profiles.conf")));
        }

        Path runtime = temp.resolve("runtime-drift");
        Path clients = temp.resolve("clients-drift");
        Files.createDirectories(runtime);
        byte[] original = "runtime-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(runtime.resolve("agent-client.mjs"), "runtime-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(runtime.resolve(AgentHostedProfilePublisher.CAPABILITY_MANIFEST), """
                contractVersion=1
                perProfileApiKey=true
                disabledStage=true
                hotReloadDisconnect=true
                runtimeSha256=%s
                """.formatted(AgentHostedProfilePublisher.sha256(original)).strip());
        FixtureData data = data();
        assertThrows(AgentBizException.class, () -> new AgentHostedProfilePublisher(runtime, clients)
                .publish(data.hosted, data.persona, 0, 1, false, "secret"));
        assertFalse(Files.exists(clients));
    }

    private Fixture fixture() throws Exception {
        Path runtime = temp.resolve("runtime");
        Path clients = temp.resolve("clients");
        Files.createDirectories(runtime.resolve(".codex"));
        Files.writeString(runtime.resolve(".codex/config.toml"), "model='test'");
        Files.writeString(runtime.resolve(".codex/auth.json"), "{}");
        byte[] runtimeSource = "authoritative-runtime-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(runtime.resolve("agent-client.mjs"), runtimeSource);
        Files.writeString(runtime.resolve(AgentHostedProfilePublisher.CAPABILITY_MANIFEST), """
                contractVersion=1
                perProfileApiKey=true
                disabledStage=true
                hotReloadDisconnect=true
                runtimeSha256=%s
                """.formatted(AgentHostedProfilePublisher.sha256(runtimeSource)).strip());
        FixtureData data = data();
        return new Fixture(new AgentHostedProfilePublisher(runtime, clients), data.hosted,
                data.persona, runtime.resolve("codex-profiles.conf"));
    }

    private FixtureData data() {
        Scope scope = new Scope("owner-a", "client-a", "owner-a");
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setBindingId(11L); hosted.setTenantId(scope.tenantId()); hosted.setClientId(scope.clientId());
        hosted.setOwnerJiacn(scope.ownerJiacn()); hosted.setCanonicalAgentId("agt_0123456789abcdef0123456789abcdef");
        hosted.setPersonaCode("wuyong"); hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(scope) + 11L);
        hosted.setApiKeyId("key-11"); hosted.setGeneration(0L); hosted.setLifecycleState("PREPARED");
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode("wuyong"); persona.setName("Wu Yong"); persona.setTitle("Strategist");
        return new FixtureData(hosted, persona);
    }

    private record Fixture(AgentHostedProfilePublisher publisher, AgentHostedProfileEntity hosted,
            AgentPersonaEntity persona, Path profiles) {}
    private record FixtureData(AgentHostedProfileEntity hosted, AgentPersonaEntity persona) {}
}
