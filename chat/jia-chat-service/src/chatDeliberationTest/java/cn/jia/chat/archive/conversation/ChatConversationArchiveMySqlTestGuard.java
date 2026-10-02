package cn.jia.chat.archive.conversation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/** Rejects every non-disposable target before the AC12 MySQL fixture opens a connection. */
final class ChatConversationArchiveMySqlTestGuard {
    private static final String PREFIX = "jdbc:mysql://";

    private ChatConversationArchiveMySqlTestGuard() { }

    static Target requireDisposable(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        require("true".equals(environment.get("CYF_AC12_MYSQL_ISOLATED")),
                "CYF_AC12_MYSQL_ISOLATED must be exactly true");
        String url = environment.get("CYF_AC12_MYSQL_URL");
        require(url != null && url.startsWith(PREFIX), "CYF_AC12_MYSQL_URL must use jdbc:mysql://");
        URI uri;
        try {
            uri = new URI("mysql://" + url.substring(PREFIX.length()));
        } catch (URISyntaxException failure) {
            throw invalid("CYF_AC12_MYSQL_URL is malformed", failure);
        }
        require(uri.getUserInfo() == null, "credentials are forbidden in CYF_AC12_MYSQL_URL");
        require(uri.getQuery() == null && uri.getFragment() == null,
                "query parameters and fragments are forbidden in CYF_AC12_MYSQL_URL");
        String host = uri.getHost();
        require("127.0.0.1".equals(host) || "::1".equals(host),
                "CYF_AC12_MYSQL_URL host must be an IP-literal loopback");
        int port = uri.getPort();
        require(port >= 1024 && port <= 65_535 && port != 3306 && port != 33060,
                "CYF_AC12_MYSQL_URL requires an explicit non-production port");
        String path = uri.getPath();
        require(path != null && path.startsWith("/") && path.indexOf('/', 1) == -1
                        && Objects.equals(path, uri.getRawPath()),
                "CYF_AC12_MYSQL_URL must contain one unencoded database name");
        String database = path.substring(1);
        require(database.matches("cyf_ac12_[a-z0-9_]{1,55}"),
                "database name must use the disposable cyf_ac12_ prefix");
        require(database.equals(environment.get("CYF_AC12_MYSQL_DATABASE_CONFIRM")),
                "CYF_AC12_MYSQL_DATABASE_CONFIRM must exactly match the URL database name");
        return new Target(url, database);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message, null);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        String prefixed = "Unsafe AC12 MySQL test target: " + message;
        return cause == null ? new IllegalStateException(prefixed)
                : new IllegalStateException(prefixed, cause);
    }

    record Target(String url, String database) { }
}
