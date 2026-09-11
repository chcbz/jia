package cn.jia.chat.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "OD02_MYSQL_URL", matches = ".+")
class ChatOutputSchemaInitializerMySqlTest {
    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private final List<String> databases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        baseUrl = required("OD02_MYSQL_URL");
        username = required("OD02_MYSQL_USER");
        password = required("OD02_MYSQL_PASSWORD");
        admin = new JdbcTemplate(dataSource(baseUrl));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8.0."));
    }

    @AfterEach
    void tearDown() {
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            assertTrue(database.matches("cyf_od03_chat_schema_[a-z0-9_]+"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void freshAndRepeatedInitializationKeepExactCatalog() {
        JdbcTemplate jdbc = newDatabase("fresh");
        ChatOutputSchemaInitializer initializer = new ChatOutputSchemaInitializer(jdbc);

        initializer.run(null);
        List<String> first = catalog(jdbc);
        initializer.run(null);

        assertEquals(first, catalog(jdbc));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chat_output", Integer.class));
    }

    @Test
    void wrongColumnTypeFailsClosedWithoutRepair() {
        JdbcTemplate jdbc = initializedDatabase("column_drift");
        jdbc.execute("ALTER TABLE chat_output MODIFY mime_type VARCHAR(101) "
                + "COLLATE utf8mb4_0900_bin NOT NULL");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ChatOutputSchemaInitializer(jdbc).run(null));

        assertTrue(error.getMessage().contains("column mime_type"), error.getMessage());
    }

    @Test
    void wrongIndexUniquenessFailsClosedWithoutRepair() {
        JdbcTemplate jdbc = initializedDatabase("index_drift");
        jdbc.execute("ALTER TABLE chat_output DROP INDEX idx_chat_output_source, "
                + "ADD UNIQUE INDEX idx_chat_output_source "
                + "(tenant_id,client_id,conversation_id,created_at,output_id,output_version)");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ChatOutputSchemaInitializer(jdbc).run(null));

        assertTrue(error.getMessage().contains("index idx_chat_output_source"), error.getMessage());
    }

    @Test
    void missingCheckConstraintFailsClosedWithoutRecreatingIt() {
        JdbcTemplate jdbc = initializedDatabase("check_drift");
        jdbc.execute("ALTER TABLE chat_output DROP CHECK chk_chat_output_length");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ChatOutputSchemaInitializer(jdbc).run(null));

        assertTrue(error.getMessage().contains("checks"), error.getMessage());
        assertEquals(2, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE() AND table_name='chat_output'
                  AND constraint_type='CHECK'
                """, Integer.class));
    }

    private JdbcTemplate initializedDatabase(String suffix) {
        JdbcTemplate jdbc = newDatabase(suffix);
        new ChatOutputSchemaInitializer(jdbc).run(null);
        return jdbc;
    }

    private JdbcTemplate newDatabase(String suffix) {
        String database = "cyf_od03_chat_schema_" + suffix + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        assertTrue(database.matches("[a-z0-9_]+"));
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        return new JdbcTemplate(dataSource(url(baseUrl, database)));
    }

    private List<String> catalog(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT CONCAT(column_name,':',column_type,':',is_nullable,':',
                              COALESCE(column_default,'<null>'),':',
                              COALESCE(collation_name,'<null>'))
                FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='chat_output'
                ORDER BY ordinal_position
                """, String.class);
    }

    private DriverManagerDataSource dataSource(String jdbcUrl) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private String url(String base, String database) {
        int query = base.indexOf('?');
        String head = query < 0 ? base : base.substring(0, query);
        String tail = query < 0 ? "" : base.substring(query);
        int slash = head.indexOf('/', "jdbc:mysql://".length());
        return (slash < 0 ? head + "/" : head.substring(0, slash + 1)) + database + tail;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " missing");
        return value;
    }
}
