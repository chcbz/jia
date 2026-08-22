package cn.jia.chat.archive.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;

/** Fail-closed parser for the destructive H03 isolated-MySQL test target. */
final class ArchiveReaderDataMySqlTestGuard {
    private static final String JDBC_PREFIX = "jdbc:mysql://";

    private ArchiveReaderDataMySqlTestGuard() {
    }

    static Target requireDisposable(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        require("true".equals(environment.get("CYF_H03_MYSQL_ISOLATED")),
                "CYF_H03_MYSQL_ISOLATED must be exactly true");
        String jdbcUrl = environment.get("CYF_H03_MYSQL_URL");
        require(jdbcUrl != null && jdbcUrl.startsWith(JDBC_PREFIX),
                "CYF_H03_MYSQL_URL must use jdbc:mysql://");

        URI uri;
        try {
            uri = new URI("mysql://" + jdbcUrl.substring(JDBC_PREFIX.length()));
        } catch (URISyntaxException failure) {
            throw invalid("CYF_H03_MYSQL_URL is malformed", failure);
        }
        require(uri.getUserInfo() == null, "credentials are forbidden in CYF_H03_MYSQL_URL");
        require(uri.getQuery() == null && uri.getFragment() == null,
                "query parameters and fragments are forbidden in CYF_H03_MYSQL_URL");
        String host = uri.getHost();
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        require("127.0.0.1".equals(host) || "::1".equals(host),
                "CYF_H03_MYSQL_URL host must be an IP-literal loopback");
        int port = uri.getPort();
        require(port >= 1024 && port <= 65_535 && port != 3306 && port != 33060,
                "CYF_H03_MYSQL_URL requires an explicit non-production port");

        String rawPath = uri.getRawPath();
        String path = uri.getPath();
        require(path != null && path.startsWith("/") && path.indexOf('/', 1) == -1,
                "CYF_H03_MYSQL_URL must contain exactly one database name");
        require(rawPath != null && rawPath.equals(path),
                "encoded database names are forbidden");
        String databaseName = path.substring(1);
        require(databaseName.matches("cyf_h03_[a-z0-9_]{1,56}"),
                "database name must use the disposable cyf_h03_ prefix");
        require(databaseName.equals(environment.get("CYF_H03_MYSQL_DATABASE_CONFIRM")),
                "CYF_H03_MYSQL_DATABASE_CONFIRM must exactly match the URL database name");
        return new Target(jdbcUrl, databaseName);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message, null);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        String prefix = "Unsafe H03 MySQL test target: ";
        return cause == null ? new IllegalStateException(prefix + message)
                : new IllegalStateException(prefix + message, cause);
    }

    record Target(String url, String databaseName) {
    }
}
