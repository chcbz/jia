package cn.jia.agent.hosting;

import cn.jia.agent.config.HostingControlRemoteProperties;
import cn.jia.agent.config.ManagedHostingAdapterProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Trusted host selection and remote transport only; no lifecycle or charging decisions. */
@Component
public final class HostingControlChannel {
    private final HostingControlRemoteProperties routes;
    private final ManagedHostingAdapterProperties local;
    private final AgentRuntimeDao runtimes;
    private final ObjectProvider<SslBundles> bundles;
    private record Context(SslBundle bundle, SSLContext context) { }
    private final Map<String, Context> contexts = new ConcurrentHashMap<>();
    record Target(String hostId, HostingControlRemoteProperties.Endpoint remote) { }
    public HostingControlChannel(HostingControlRemoteProperties routes, ManagedHostingAdapterProperties local,
            AgentRuntimeDao runtimes, ObjectProvider<SslBundles> bundles) {
        this.routes = routes; this.local = local; this.runtimes = runtimes; this.bundles = bundles;
    }
    public boolean available() {
        return local.configured() && routes.localHostId() != null
                || routes.remotes().values().stream().anyMatch(HostingControlRemoteProperties.Endpoint::configured);
    }
    public boolean availableFor(String tenant, String client, String owner) {
        return routes.localHostId() != null && local.allowsScope(tenant, client, owner)
                || routes.remotes().values().stream().anyMatch(endpoint -> endpoint.allows(tenant, client, owner));
    }
    Target target(Map<String, Object> request, String preferredHost) throws IOException {
        String tenant = (String)request.get("tenantId"), client = (String)request.get("clientId"), owner = (String)request.get("ownerJiacn");
        String host = preferredHost;
        if (host == null && request.get("quotedInventory") instanceof Map<?, ?> quoted) host = (String)quoted.get("hostId");
        // A persisted quote or installation candidate needs no projection lookup. Inventory/current
        // and free reprovision use the registered subject; no scan or in-memory owner filtering.
        if (host == null && request.get("canonicalAgentId") instanceof String agent) {
            var runtime = runtimes.findInScope(tenant, client, agent);
            if (runtime != null) {
                host = runtime.getRuntimeHostId();
                if (host == null || host.isBlank()) throw new IOException("HOSTING_HOST_UNRESOLVED");
            }
        }
        if (host == null) host = routes.localHostId(); // Only a new, not-yet-registered managed subject.
        if (host == null || host.isBlank()) throw new IOException("HOSTING_HOST_UNRESOLVED");
        var endpoint = routes.remotes().get(host);
        if (endpoint != null) {
            if (host.equals(routes.localHostId()) || !endpoint.allows(tenant, client, owner))
                throw new IOException("HOSTING_HOST_SCOPE_REJECTED");
            return new Target(host, endpoint);
        }
        if (!host.equals(routes.localHostId()) || !local.allowsScope(tenant, client, owner))
            throw new IOException("HOSTING_HOST_UNCONFIGURED");
        return new Target(host, null);
    }
    byte[] exchange(Target target, byte[] frame, int timeoutMs) throws IOException {
        var endpoint = target.remote();
        if (endpoint == null) throw new IllegalArgumentException("Remote endpoint required");
        SSLContext context;
        try {
            var registry = bundles.getIfAvailable();
            if (registry == null) throw new IOException("HOSTING_TLS_UNCONFIGURED");
            var bundle = registry.getBundle(endpoint.sslBundle());
            context = contexts.compute(endpoint.sslBundle(), (name, cached) -> cached != null && cached.bundle() == bundle
                    ? cached : new Context(bundle, bundle.createSslContext())).context();
        } catch (RuntimeException invalid) { throw new IOException("HOSTING_TLS_UNCONFIGURED", invalid); }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try (var socket = (SSLSocket)context.getSocketFactory().createSocket()) {
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS"); // Verify DNS/IP SAN, never trust hostId as a certificate name.
            parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            socket.setSSLParameters(parameters);
            socket.connect(new InetSocketAddress(endpoint.address(), endpoint.port()), remaining(deadline));
            socket.setSoTimeout(remaining(deadline)); socket.startHandshake();
            socket.setSoTimeout(remaining(deadline));
            socket.getOutputStream().write(frame); socket.getOutputStream().flush();
            var response = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
            while (true) {
                socket.setSoTimeout(remaining(deadline));
                int count = socket.getInputStream().read(buffer);
                if (count < 0) throw new IOException("Incomplete hosting response");
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == '\n') {
                        if (i != count - 1) throw new IOException("Multiple hosting frames");
                        return response.toByteArray();
                    }
                    if (response.size() >= 16384) throw new IOException("Hosting response too large");
                    response.write(buffer[i]);
                }
            }
        }
    }
    private static int remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new java.net.SocketTimeoutException("Hosting transport timeout");
        return (int)Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }
}
