package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "OD02_MYSQL_URL", matches = ".+")
class AgentTaskArtifactOutputSchemaMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String baseUrl;
    private String username;
    private String password;

    @BeforeEach
    void setUp() {
        baseUrl = required("OD02_MYSQL_URL");
        username = required("OD02_MYSQL_USER");
        password = required("OD02_MYSQL_PASSWORD");
        admin = new JdbcTemplate(dataSource(baseUrl));
        database = "cyf_od03_artifact_schema_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        jdbc = new JdbcTemplate(dataSource(url(baseUrl, database)));
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && database != null) {
            assertTrue(database.matches("cyf_od03_artifact_schema_[a-z0-9]+"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void freshAndRepeatedM002InitializationAreStable() {
        AgentSchemaInitializer initializer = new AgentSchemaInitializer(jdbc);

        initializer.ensureTaskArtifactOutputColumns();
        initializer.ensureTaskArtifactOutputColumns();

        assertEquals(7, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_task_artifact'
                  AND column_name IN ('object_id','run_id','file_name','content_byte_length',
                                      'mime_type','owner_shared_at','retain_until')
                """, Integer.class));
    }

    @Test
    void existingWrongM002TypeFailsClosedWithoutMutation() {
        jdbc.execute("ALTER TABLE agent_task_artifact ADD object_id VARCHAR(100) NULL");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(jdbc).ensureTaskArtifactOutputColumns());

        assertTrue(error.getMessage().contains("agent_task_artifact.object_id"), error.getMessage());
        assertEquals("varchar(100)", jdbc.queryForObject("""
                SELECT column_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_task_artifact'
                  AND column_name='object_id'
                """, String.class));
    }

    private DriverManagerDataSource dataSource(String jdbcUrl) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private String url(String base, String db) {
        int query = base.indexOf('?');
        String head = query < 0 ? base : base.substring(0, query);
        String tail = query < 0 ? "" : base.substring(query);
        int slash = head.indexOf('/', "jdbc:mysql://".length());
        return (slash < 0 ? head + "/" : head.substring(0, slash + 1)) + db + tail;
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " missing");
        return value;
    }
}
