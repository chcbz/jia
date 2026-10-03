package cn.jia.chat.archive.conversation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/** Rejects every non-disposable target before the AC12 fixture opens a connection. */
final class ChatConversationArchiveMySqlTestGuard {
    private static final String PREFIX = "jdbc:mysql://";
    private static final String OWNED_PREFIX = "mmd_ac12main";

    private ChatConversationArchiveMySqlTestGuard() { }

    static Target requireDisposable(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        require("true".equals(environment.get("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")),
                "MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE must be exactly true");
        String url = environment.get("MMD_U1_REFERENCE_MYSQL_URL");
        require(url != null && url.startsWith(PREFIX),
                "MMD_U1_REFERENCE_MYSQL_URL must use jdbc:mysql://");
        URI uri;
        try {
            uri = new URI("mysql://" + url.substring(PREFIX.length()));
        } catch (URISyntaxException failure) {
            throw invalid("MMD_U1_REFERENCE_MYSQL_URL is malformed", failure);
        }
        require(uri.getUserInfo() == null, "credentials are forbidden in the JDBC URL");
        require(uri.getFragment() == null, "JDBC URL fragments are forbidden");
        require("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()),
                "JDBC URL host must be an IP-literal loopback");
        int port = uri.getPort();
        require(port >= 1024 && port <= 65_535 && port != 3306 && port != 33060,
                "JDBC URL requires an explicit non-production port");
        require("/mysql".equals(uri.getPath()) && "/mysql".equals(uri.getRawPath()),
                "JDBC URL must target only the mysql admin catalog");
        String databasePrefix = environment.get("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        require(OWNED_PREFIX.equals(databasePrefix),
                "database prefix must be exactly " + OWNED_PREFIX);
        require(environment.containsKey("MMD_U1_REFERENCE_MYSQL_PASSWORD"),
                "password must be explicitly supplied, including an empty value");
        String user = environment.get("MMD_U1_REFERENCE_MYSQL_USER");
        require(user != null && !user.isBlank(), "MySQL user is required");
        return new Target(url, user, environment.get("MMD_U1_REFERENCE_MYSQL_PASSWORD"),
                databasePrefix);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message, null);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        String prefixed = "Unsafe AC12 MySQL test target: " + message;
        return cause == null ? new IllegalStateException(prefixed)
                : new IllegalStateException(prefixed, cause);
    }

    record Target(String adminUrl, String user, String password, String databasePrefix) { }
}
