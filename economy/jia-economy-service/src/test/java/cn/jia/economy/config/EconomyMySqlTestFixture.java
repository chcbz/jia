package cn.jia.economy.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Externally supplied, explicitly acknowledged isolated MySQL 8.0.21 fixture. */
public final class EconomyMySqlTestFixture implements AutoCloseable {
    public static final String URL_ENV = "ECO_V0_W02_MYSQL_URL";
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern SUFFIX = Pattern.compile("[a-z][a-z0-9_]{0,15}");

    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private String databasePrefix;
    private final List<String> databases = new ArrayList<>();

    public void start() {
        baseUrl = requiredNonBlank(URL_ENV);
        if (!baseUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(URL_ENV + " must be a jdbc:mysql:// URL");
        }
        username = requiredNonBlank("ECO_V0_W02_MYSQL_USER");
        password = requiredPresent("ECO_V0_W02_MYSQL_PASSWORD");
        if (!"true".equals(requiredNonBlank("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE must equal true");
        }
        databasePrefix = requiredNonBlank("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        if (!PREFIX.matcher(databasePrefix).matches()) {
            throw new IllegalStateException("ECO_V0_W02_MYSQL_DATABASE_PREFIX is unsafe");
        }
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        String port = System.getenv("ECO_V0_W02_MYSQL_EXPECTED_PORT");
        if (port != null && !port.isBlank()) {
            assertEquals(Integer.valueOf(port), admin.queryForObject("SELECT @@port", Integer.class));
        }
        String datadir = System.getenv("ECO_V0_W02_MYSQL_EXPECTED_DATADIR");
        if (datadir != null && !datadir.isBlank()) {
            String actual = admin.queryForObject("SELECT @@datadir", String.class);
            assertTrue(actual != null && actual.startsWith(datadir), actual);
        }
    }

    public Database newDatabase(String suffix) {
        if (admin == null) throw new IllegalStateException("MySQL fixture is not started");
        if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("unsafe MySQL fixture database suffix");
        }
        String database = databasePrefix + "_" + suffix + "_"
                + Long.toUnsignedString(System.nanoTime(), 36);
        if (database.length() > 64 || !database.startsWith(databasePrefix + "_")) {
            throw new IllegalStateException("unsafe MySQL fixture database name");
        }
        // Register ownership before CREATE: a lost/ambiguous CREATE response must still be cleaned up.
        databases.add(database);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, database));
        return new Database(database, source, new JdbcTemplate(source));
    }

    @Override
    public void close() {
        if (admin == null) return;
        while (!databases.isEmpty()) {
            int index = databases.size() - 1;
            String database = databases.get(index);
            if (!database.startsWith(databasePrefix + "_")) {
                throw new IllegalStateException("refusing to drop an unowned MySQL database");
            }
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
            // Remove only after DROP returned successfully. A failed/unknown DROP remains owned for retry.
            databases.remove(index);
        }
        admin = null;
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(boundedJdbcUrl(url));
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    static String boundedJdbcUrl(String url) {
        int query = url.indexOf('?');
        String prefix = query < 0 ? url : url.substring(0, query);
        List<String> options = new ArrayList<>();
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!option.isBlank() && !key.equalsIgnoreCase("connectTimeout")
                        && !key.equalsIgnoreCase("socketTimeout")
                        && !key.equalsIgnoreCase("autoReconnect")
                        && !key.equalsIgnoreCase("autoReconnectForPools")) {
                    options.add(option);
                }
            }
        }
        options.add("connectTimeout=10000");
        options.add("socketTimeout=2400000");
        options.add("autoReconnect=false");
        options.add("autoReconnectForPools=false");
        return prefix + "?" + String.join("&", options);
    }

    private String databaseUrl(String url, String database) {
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return slash < 0
                ? prefix + "/" + database + suffix
                : prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredNonBlank(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String requiredPresent(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " must be explicitly supplied");
        return value;
    }

    public record Database(String name, DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
    }
}
