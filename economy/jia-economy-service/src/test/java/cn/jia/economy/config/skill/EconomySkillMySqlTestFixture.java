package cn.jia.economy.config.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Externally supplied and explicitly acknowledged isolated MySQL 8.0.21 fixture for W07 only. */
final class EconomySkillMySqlTestFixture implements AutoCloseable {
    static final String URL_ENV = "ECO_V0_W07_MYSQL_URL";
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern SUFFIX = Pattern.compile("[a-z][a-z0-9_]{0,15}");

    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private String databasePrefix;
    private final List<String> databases = new ArrayList<>();

    void start() {
        baseUrl = requiredNonBlank(URL_ENV);
        if (!baseUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(URL_ENV + " must be a jdbc:mysql:// URL");
        }
        username = requiredNonBlank("ECO_V0_W07_MYSQL_USER");
        password = requiredPresent("ECO_V0_W07_MYSQL_PASSWORD");
        if (!"true".equals(requiredNonBlank("ECO_V0_W07_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("ECO_V0_W07_MYSQL_ISOLATED_FIXTURE must equal true");
        }
        databasePrefix = requiredNonBlank("ECO_V0_W07_MYSQL_DATABASE_PREFIX");
        if (!PREFIX.matcher(databasePrefix).matches()) {
            throw new IllegalStateException("ECO_V0_W07_MYSQL_DATABASE_PREFIX is unsafe");
        }
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        String port = System.getenv("ECO_V0_W07_MYSQL_EXPECTED_PORT");
        if (port != null && !port.isBlank()) {
            assertEquals(Integer.valueOf(port), admin.queryForObject("SELECT @@port", Integer.class));
        }
        String datadir = System.getenv("ECO_V0_W07_MYSQL_EXPECTED_DATADIR");
        if (datadir != null && !datadir.isBlank()) {
            String actual = admin.queryForObject("SELECT @@datadir", String.class);
            assertTrue(actual != null && actual.startsWith(datadir), actual);
        }
    }

    Database newDatabase(String suffix) {
        if (admin == null) throw new IllegalStateException("MySQL fixture is not started");
        if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("unsafe MySQL fixture database suffix");
        }
        String database = databasePrefix + "_" + suffix + "_" + Long.toUnsignedString(System.nanoTime(), 36);
        if (database.length() > 64 || !database.startsWith(databasePrefix + "_")) {
            throw new IllegalStateException("unsafe MySQL fixture database name");
        }
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, database));
        return new Database(database, source, new JdbcTemplate(source));
    }

    @Override
    public void close() {
        if (admin == null) return;
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            if (!database.startsWith(databasePrefix + "_")) {
                throw new IllegalStateException("refusing to drop an unowned W07 MySQL database");
            }
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
        databases.clear();
        admin = null;
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String url, String database) {
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return slash < 0 ? prefix + "/" + database + suffix : prefix.substring(0, slash + 1) + database + suffix;
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

    record Database(String name, DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
    }
}
