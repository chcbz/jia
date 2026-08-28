package cn.jia.agent.service;

import cn.jia.agent.entity.AgentHostedProfileEntity;
import cn.jia.agent.entity.AgentPersonaBindResultDTO;
import cn.jia.agent.entity.AgentPersonaEntity;
import cn.jia.agent.service.AgentHostedBindingTransaction.Scope;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.util.JsonUtil;
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
    void responseContractHasNoCredentialFieldAndLargeIdentifiersSerializeAsStrings() throws Exception {
        Set<String> fields = Arrays.stream(AgentPersonaBindResultDTO.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(Field::getName).collect(Collectors.toSet());
        assertFalse(fields.contains("apiKey"));
        assertFalse(fields.contains("credential"));
        assertFalse(fields.contains("secret"));

        AgentPersonaBindResultDTO result = new AgentPersonaBindResultDTO();
        result.setBindingId("9223372036854775806");
        result.setGeneration("9223372036854775805");
        String json = JsonUtil.getMapper().writeValueAsString(result);
        assertTrue(json.contains("\"bindingId\":\"9223372036854775806\""));
        assertTrue(json.contains("\"generation\":\"9223372036854775805\""));
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
    void disabledStageThenEnableUsesGenerationCasAndReportsConfiguredPathsTruthfully() throws Exception {
        Fixture fixture = fixture("configured");
        AgentHostedProfilePublisher.PublishedPaths first = fixture.publisher.publish(
                fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret");
        String staged = Files.readString(fixture.profiles);
        assertTrue(staged.contains("enabled=false"));
        assertTrue(staged.contains("# cyfHostedGeneration=1"));
        assertTrue(staged.contains("apiKey=dedicated-secret"));
        assertEquals(fixture.clients.resolve(fixture.hosted.getCanonicalAgentId()).toString(), first.workdir());
        assertEquals(fixture.runtime.resolve(".codex-hosted-" + fixture.hosted.getProfileKey()).toString(),
                first.codexHome());
        assertEquals(fixture.profiles.toString(), first.profilesFile());
        assertTrue(first.created());
        assertFalse(first.alreadyExists());

        AgentHostedProfilePublisher.PublishedPaths retry = fixture.publisher.publish(
                fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret");
        assertEquals(staged, Files.readString(fixture.profiles));
        assertFalse(retry.created());
        assertTrue(retry.alreadyExists());

        AgentHostedProfilePublisher.PublishedPaths enabledPaths = fixture.publisher.publish(
                fixture.hosted, fixture.persona, 1, 2, true, "dedicated-secret");
        String enabled = Files.readString(fixture.profiles);
        assertTrue(enabled.contains("enabled=true"));
        assertTrue(enabled.contains("# cyfHostedGeneration=2"));
        assertFalse(enabledPaths.created());
        assertTrue(enabledPaths.alreadyExists());

        AgentHostedProfilePublisher.PublishedPaths inspected =
                fixture.publisher.inspectExisting(fixture.hosted, 2L);
        assertEquals(enabledPaths.workdir(), inspected.workdir());
        assertFalse(inspected.created());
        assertTrue(inspected.alreadyExists());
    }

    @Test
    void runtimeCompatibleProfileAndWhitespaceAgentIdCollisionsFailClosed() throws Exception {
        Fixture fixture = fixture("collision");
        for (String colliding : List.of(
                """
                [profile.shadow]
                agentId = %s
                """.formatted(fixture.hosted.getCanonicalAgentId()),
                "   [agent.shadow]   \n   agentId    =    %s   \n"
                        .formatted(fixture.hosted.getCanonicalAgentId()),
                "[profile.nbsp]\nagentId\u00a0=\u00a0%s\n"
                        .formatted(fixture.hosted.getCanonicalAgentId()))) {
            Files.writeString(fixture.profiles, colliding);
            String stable = Files.readString(fixture.profiles);
            assertThrows(AgentBizException.class, () -> fixture.publisher.publish(
                    fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret"));
            assertEquals(stable, Files.readString(fixture.profiles));
        }
    }

    @Test
    void quotedUnknownSectionAndBomCanonicalCollisionsMatchRuntimeLexing() throws Exception {
        Fixture fixture = fixture("runtime-lexing");
        for (String colliding : List.of(
                "[profile.quoted]\nagentId=\"" + fixture.hosted.getCanonicalAgentId() + "\"\n",
                "[agent.shadow]\n[unknown.section]\nagentId='"
                        + fixture.hosted.getCanonicalAgentId() + "'\n",
                "\uFEFF[profile.bom]\uFEFF\n\uFEFFagentId\uFEFF=\uFEFF\""
                        + fixture.hosted.getCanonicalAgentId() + "\"\uFEFF\n")) {
            Files.writeString(fixture.profiles, colliding);
            String stable = Files.readString(fixture.profiles);
            assertThrows(AgentBizException.class, () -> fixture.publisher.publish(
                    fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret"));
            assertEquals(stable, Files.readString(fixture.profiles));
        }
    }

    @Test
    void recognizedDefaultAliasesBoundTargetReplacementLikeRuntimeSections() throws Exception {
        int index = 0;
        for (String defaultHeader : List.of("[default]", "[agent.default]", "[profile.default]")) {
            Fixture fixture = fixture("default-boundary-" + index);
            fixture.publisher.publish(fixture.hosted, fixture.persona,
                    0, 1, false, "dedicated-secret");
            String retained = "# retained-default-boundary-" + index;
            Files.writeString(fixture.profiles, Files.readString(fixture.profiles)
                    + "\n" + defaultHeader + "\ncodexApproval=never\n" + retained + "\n");

            fixture.publisher.publish(fixture.hosted, fixture.persona,
                    1, 2, true, "dedicated-secret");

            String published = Files.readString(fixture.profiles);
            assertTrue(published.contains("# cyfHostedGeneration=2"));
            assertTrue(published.contains(defaultHeader + "\ncodexApproval=never\n" + retained));
            index++;
        }
    }

    @Test
    void duplicateAgentIdAndAmbiguousProfileTargetFailClosed() throws Exception {
        Fixture fixture = fixture("ambiguous");
        Files.writeString(fixture.profiles, """
                [agent.shadow]
                agentId=agt_11111111111111111111111111111111
                agentId=agt_22222222222222222222222222222222
                """);
        assertThrows(AgentBizException.class, () -> fixture.publisher.publish(
                fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret"));

        Files.writeString(fixture.profiles, "[profile." + fixture.hosted.getProfileKey() + "]\nenabled=true\n");
        assertThrows(AgentBizException.class, () -> fixture.publisher.publish(
                fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret"));
    }

    @Test
    void generationConflictLegacyCollisionAndMissingActiveSectionFailClosedWithoutOverwrite() throws Exception {
        Fixture fixture = fixture("generation");
        fixture.publisher.publish(fixture.hosted, fixture.persona, 0, 1, false, "dedicated-secret");
        String stable = Files.readString(fixture.profiles);
        assertThrows(AgentBizException.class,
                () -> fixture.publisher.publish(fixture.hosted, fixture.persona,
                        0, 2, true, "dedicated-secret"));
        assertEquals(stable, Files.readString(fixture.profiles));

        Files.writeString(fixture.profiles, "[agent." + fixture.hosted.getProfileKey() + "]\nagentId="
                + fixture.hosted.getCanonicalAgentId() + "\nenabled=true\n");
        String legacy = Files.readString(fixture.profiles);
        assertThrows(AgentBizException.class,
                () -> fixture.publisher.publish(fixture.hosted, fixture.persona,
                        0, 1, false, "dedicated-secret"));
        assertEquals(legacy, Files.readString(fixture.profiles));

        Files.delete(fixture.profiles);
        assertThrows(AgentBizException.class,
                () -> fixture.publisher.inspectExisting(fixture.hosted, 2L));
    }

    @Test
    void exhaustedGenerationFailsBeforeProfileBootstrapLockOrDirectoryMutation() throws Exception {
        Fixture fixture = fixture("generation-exhausted");
        String stable = """
                [agent.%s]
                # cyfHostedBindingId=%d
                # cyfHostedCanonicalAgentId=%s
                # cyfHostedGeneration=%d
                agentId=%s
                codexWorkdir=/stable/workdir
                agentName=Wu Yong
                personaName=Strategist
                codexHome=/stable/home
                enabled=false
                apiKey=dedicated-secret
                """.formatted(fixture.hosted.getProfileKey(), fixture.hosted.getBindingId(),
                fixture.hosted.getCanonicalAgentId(), Long.MAX_VALUE,
                fixture.hosted.getCanonicalAgentId());
        Files.writeString(fixture.profiles, stable);
        Path codexHome = fixture.runtime.resolve(".codex-hosted-" + fixture.hosted.getProfileKey());
        Path lock = fixture.profiles.resolveSibling(fixture.profiles.getFileName() + ".lock");

        assertThrows(AgentBizException.class, () -> fixture.publisher.publish(
                fixture.hosted, fixture.persona, Long.MAX_VALUE, Long.MIN_VALUE,
                false, "dedicated-secret"));

        assertEquals(stable, Files.readString(fixture.profiles));
        assertFalse(Files.exists(fixture.clients));
        assertFalse(Files.exists(codexHome));
        assertFalse(Files.exists(lock));
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
        Files.write(runtime.resolve("agent-client.mjs"),
                "runtime-v2".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private Fixture fixture(String name) throws Exception {
        Path runtime = temp.resolve("runtime-" + name);
        Path clients = temp.resolve("clients-" + name);
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
                data.persona, runtime, clients, runtime.resolve("codex-profiles.conf"));
    }

    private FixtureData data() {
        Scope scope = new Scope("owner-a", "client-a", "owner-a");
        AgentHostedProfileEntity hosted = new AgentHostedProfileEntity();
        hosted.setBindingId(11L);
        hosted.setTenantId(scope.tenantId());
        hosted.setClientId(scope.clientId());
        hosted.setOwnerJiacn(scope.ownerJiacn());
        hosted.setCanonicalAgentId("agt_0123456789abcdef0123456789abcdef");
        hosted.setPersonaCode("wuyong");
        hosted.setProfileKey(AgentHostedBindingTransaction.scopeDigest(scope) + 11L);
        hosted.setApiKeyId("key-11");
        hosted.setGeneration(0L);
        hosted.setLifecycleState("PREPARED");
        AgentPersonaEntity persona = new AgentPersonaEntity();
        persona.setPersonaCode("wuyong");
        persona.setName("Wu Yong");
        persona.setTitle("Strategist");
        return new FixtureData(hosted, persona);
    }

    private record Fixture(AgentHostedProfilePublisher publisher, AgentHostedProfileEntity hosted,
            AgentPersonaEntity persona, Path runtime, Path clients, Path profiles) {
    }

    private record FixtureData(AgentHostedProfileEntity hosted, AgentPersonaEntity persona) {
    }
}
