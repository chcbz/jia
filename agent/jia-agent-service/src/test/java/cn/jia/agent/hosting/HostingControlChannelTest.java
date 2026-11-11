package cn.jia.agent.hosting;

import cn.jia.agent.config.HostingControlRemoteProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

import java.nio.file.*;
import java.io.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingControlChannelTest {
    @TempDir Path temporary;
    private final AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
    private final ManagedHostingAdapterProperties local = new ManagedHostingAdapterProperties(
            "/private/control.sock", 0L, "0", "fixture-client", "*", 3000, 1000L, null);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private Map<String,Object> request() {
        return new LinkedHashMap<>(Map.of("tenantId", "0", "clientId", "fixture-client", "ownerJiacn", "fixture-owner",
                "canonicalAgentId", "jyt-static", "method", "inventory"));
    }
    private HostingControlChannel channel(String address, int port, String host, SslBundle bundle) {
        var beans = new DefaultListableBeanFactory();
        if (bundle != null) {
            SslBundles registry = mock(SslBundles.class); when(registry.getBundle("control")).thenReturn(bundle);
            beans.registerSingleton("sslBundles", registry);
        }
        var endpoint = new HostingControlRemoteProperties.Endpoint(address, port, "control", "0", "fixture-client", "fixture-owner");
        return new HostingControlChannel(new HostingControlRemoteProperties("local-host", Map.of(host, endpoint)),
                local, runtimes, beans.getBeanProvider(SslBundles.class));
    }
    @Test void trustedQuoteAndCandidateRouteWithoutProjectionQueryOrLocalFallback() throws Exception {
        var control = channel("127.0.0.1", 9443, "remote-host", null); var request = request();
        request.put("quotedInventory", Map.of("hostId", "remote-host"));
        assertEquals("remote-host", control.target(request, null).hostId());
        assertNotNull(control.target(request, "remote-host").remote());
        verifyNoInteractions(runtimes);
        assertThrows(IOException.class, () -> control.target(request, "unknown-host"));
        request.put("ownerJiacn", "intruder"); assertThrows(IOException.class, () -> control.target(request, null));
        verifyNoInteractions(runtimes);
    }
    @Test void registeredSubjectUsesScopedHostEvenWhenStoppedAndMissingHostNeverFallsBack() throws Exception {
        var control = channel("127.0.0.1", 9443, "remote-host", null);
        var runtime = new AgentRuntimeEntity().setRuntimeHostId("remote-host");
        when(runtimes.findInScope("0", "fixture-client", "jyt-static")).thenReturn(runtime);
        assertEquals("remote-host", control.target(request(), null).hostId());
        runtime.setRuntimeHostId(null);
        assertThrows(IOException.class, () -> control.target(request(), null));
        assertFalse(control.availableFor("other-tenant", "fixture-client", "fixture-owner"));
    }
    @Test void onlyUnregisteredManagedSubjectUsesExplicitLocalHostAndNoBundleFailsClosed() throws Exception {
        var control = channel("127.0.0.1", 9443, "remote-host", null);
        assertNull(control.target(request(), null).remote());
        assertThrows(IOException.class, () -> control.exchange(control.target(request(), "remote-host"), "{}\n".getBytes(), 1000));
    }
    private void openssl(String... args) throws Exception {
        var command = new ArrayList<String>(); command.add("openssl"); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(temporary.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(temporary.resolve("openssl.log").toFile()).start();
        try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); } }
    }
    private SslBundle certificates() throws Exception {
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "ca.key", "-out", "ca.crt", "-subj", "/CN=Fixture control CA", "-days", "1");
        for (String name : List.of("server", "client")) {
            openssl("req", "-newkey", "rsa:2048", "-nodes", "-keyout", name+".key", "-out", name+".csr", "-subj", "/CN=fixture-"+name);
            Files.writeString(temporary.resolve(name+".ext"), name.equals("server")
                    ? "subjectAltName=IP:127.0.0.1\nextendedKeyUsage=serverAuth\n" : "extendedKeyUsage=clientAuth\n");
            openssl("x509", "-req", "-in", name+".csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-out", name+".crt", "-days", "1", "-extfile", name+".ext");
        }
        openssl("pkcs12", "-export", "-in", "client.crt", "-inkey", "client.key", "-out", "client.p12", "-passout", "pass:fixture-only");
        KeyStore keys = KeyStore.getInstance("PKCS12"), trust = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(temporary.resolve("client.p12"))) { keys.load(input, "fixture-only".toCharArray()); }
        trust.load(null, null);
        try (var input = Files.newInputStream(temporary.resolve("ca.crt"))) {
            trust.setCertificateEntry("control", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        return SslBundle.of(SslStoreBundle.of(keys, "fixture-only", trust));
    }
    @Test @EnabledIfEnvironmentVariable(named="CYF_GSS_RUNTIME_TEST_SOURCE", matches=".+")
    void actualJavaMutualTlsAdoptsStopsDrainsAndCleansSameRuntimeJournal() throws Exception {
        SslBundle bundle = certificates();
        Path config = temporary.resolve("remote.json");
        Files.writeString(config, JSON.writeValueAsString(Map.of("listenHost", "127.0.0.1", "port", 0,
                "certificatePath", temporary.resolve("server.crt").toString(),
                "privateKeyPath", temporary.resolve("server.key").toString(), "clientCaPath", temporary.resolve("ca.crt").toString())));
        Path source = Path.of(System.getenv("CYF_GSS_RUNTIME_TEST_SOURCE"));
        var builder = new ProcessBuilder(System.getenv("CYF_GSS_NODE_BIN"),
                source.resolve("conf/cyf-agent-runtime-v1/test/hosting-java-bridge-fixture.mjs").toString()).directory(source.toFile())
                .redirectError(temporary.resolve("runtime.log").toFile());
        builder.environment().put("CYF_GSS_STATIC_ADOPTION", "1"); builder.environment().put("CYF_GSS_TLS_CONFIG", config.toString());
        Process child = builder.start();
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            var handshake = new FutureTask<String>(output::readLine); Thread.ofVirtual().start(handshake);
            String line = handshake.get(15, TimeUnit.SECONDS); assertNotNull(line, () -> {
                try { return Files.readString(temporary.resolve("runtime.log")); } catch(IOException e) { return e.toString(); }
            });
            Map<String,Object> endpoint = JSON.readValue(line, new TypeReference<Map<String,Object>>() { });
            var remote = (Map<?,?>)endpoint.get("remoteAddress"); int port = ((Number)remote.get("port")).intValue();
            String host = (String)endpoint.get("hostId"); var subject = (Map<?,?>)endpoint.get("adoptionSubject");
            String agent = (String)subject.get("canonicalAgentId");
            when(runtimes.findInScope("0", "fixture-client", agent)).thenReturn(new AgentRuntimeEntity().setRuntimeHostId(host));
            var credentials = mock(ManagedHostingCredentials.class); when(credentials.available()).thenReturn(true);
            var sessions = mock(AgentRuntimeAuthenticationService.class);
            var rent = new AgentHostingRentProperties(true, "1", "500", "2592000");
            var initial = new ManagedHostingProvisioner.Preparation("0", "fixture-client", "fixture-owner", agent,
                    "tls-transition", "tls-lease", "17", 1791594000000L);
            var existing = new ManagedHostingProvisioner.Candidate((String)subject.get("installationId"),
                    ((String)subject.get("manifestSha256")).substring(7), null, 0, 1, host);
            var control = channel("127.0.0.1", port, host, bundle);
            var adapter = new UnixManagedHostingProvisioner(rent, local, credentials, sessions, control);
            // A wrong SAN cannot establish a relationship, even with the correct client key and CA.
            var wrong = new UnixManagedHostingProvisioner(rent, local, credentials, sessions, channel("localhost", port, host, bundle));
            assertFalse(wrong.adoptExisting(initial, existing));
            var noClientKey = SslBundle.of(SslStoreBundle.of(null, null, bundle.getStores().getTrustStore()));
            var anonymous = new UnixManagedHostingProvisioner(rent, local, credentials, sessions, channel("127.0.0.1", port, host, noClientKey));
            assertFalse(anonymous.adoptExisting(initial, existing));
            assertTrue(adapter.probeCapabilities("0", "fixture-client", "fixture-owner", agent));
            // No default managed route exists on this fixture; remote control still supports the existing static subject.
            assertFalse(adapter.probeCapabilities("0", "fixture-client", "fixture-owner"));
            assertTrue(adapter.adoptExisting(initial, existing)); assertTrue(adapter.adoptExisting(initial, existing));
            assertEquals(existing, adapter.currentOperation(initial).candidate());
            assertTrue(adapter.quiesce(initial, existing, "tls-finish"));
            assertFalse(adapter.flushEvidence(initial, existing, "tls-finish"));
            assertTrue(adapter.flushEvidence(initial, existing, "tls-finish"));
            assertTrue(adapter.cleanup(initial, existing, "tls-finish"));
            assertTrue(adapter.cleanup(initial, existing, "tls-finish"));
            verify(credentials, never()).ensureInstallation(any(), any()); verifyNoInteractions(sessions);
        } finally {
            try { child.getOutputStream().write("stop\n".getBytes()); child.getOutputStream().flush(); } catch(IOException ignored) { }
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(); }
            assertEquals(0, child.exitValue());
        }
    }
}
