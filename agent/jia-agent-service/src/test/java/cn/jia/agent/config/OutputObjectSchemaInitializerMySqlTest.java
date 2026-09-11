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

@EnabledIfEnvironmentVariable(named="OD02_MYSQL_URL",matches=".+")
class OutputObjectSchemaInitializerMySqlTest {
    private JdbcTemplate admin,jdbc;private String database;
    @BeforeEach void setup(){String base=env("OD02_MYSQL_URL");admin=new JdbcTemplate(ds(base));database="cyf_od02_schema_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");jdbc=new JdbcTemplate(ds(url(base,database)));}
    @AfterEach void cleanup(){if(admin!=null&&database!=null)admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}
    @Test void freshRepeatAndExtraColumnDriftFailClosed()throws Exception{OutputObjectSchemaInitializer initializer=new OutputObjectSchemaInitializer(jdbc);initializer.afterPropertiesSet();initializer.afterPropertiesSet();assertEquals(8,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'output_%'",Integer.class));jdbc.execute("ALTER TABLE output_scope_quota ADD COLUMN unexpected BIGINT NULL");assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);}
    @Test void partialSchemaFailsWithoutCompleting()throws Exception{jdbc.execute(OutputObjectSchemaInitializer.ddlStatements().getFirst());assertThrows(IllegalStateException.class,()->new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'output_%'",Integer.class));}
    @Test void columnTypeDriftFailsClosed()throws Exception{assertDrift("ALTER TABLE output_scope_quota MODIFY reserved_bytes INT NOT NULL");}
    @Test void columnNullabilityDriftFailsClosed()throws Exception{assertDrift("ALTER TABLE output_object MODIFY error_code VARCHAR(80) COLLATE utf8mb4_0900_bin NOT NULL");}
    @Test void columnDefaultDriftFailsClosed()throws Exception{assertDrift("ALTER TABLE output_scope_quota ALTER COLUMN row_version SET DEFAULT 1");}
    @Test void columnCollationDriftFailsClosed()throws Exception{assertDrift("ALTER TABLE output_object MODIFY actual_mime VARCHAR(100) COLLATE utf8mb4_general_ci NULL");}
    @Test void missingUniqueIndexFailsClosed()throws Exception{assertDrift("ALTER TABLE output_upload_session DROP INDEX uk_output_upload_object");}
    @Test void missingSecondaryIndexFailsClosed()throws Exception{assertDrift("ALTER TABLE output_upload_session DROP INDEX idx_output_upload_run");}
    @Test void missingCheckConstraintFailsClosed()throws Exception{assertDrift("ALTER TABLE output_scope_quota DROP CHECK chk_output_scope_quota_nonnegative");}
    private void assertDrift(String ddl)throws Exception{OutputObjectSchemaInitializer initializer=new OutputObjectSchemaInitializer(jdbc);initializer.afterPropertiesSet();jdbc.execute(ddl);assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);}
    private DriverManagerDataSource ds(String url){DriverManagerDataSource d=new DriverManagerDataSource();d.setDriverClassName("com.mysql.cj.jdbc.Driver");d.setUrl(url);d.setUsername(env("OD02_MYSQL_USER"));d.setPassword(env("OD02_MYSQL_PASSWORD"));return d;}
    private static String url(String base,String db){int q=base.indexOf('?');String h=q<0?base:base.substring(0,q),tail=q<0?"":base.substring(q);int slash=h.indexOf('/',"jdbc:mysql://".length());return (slash<0?h+"/":h.substring(0,slash+1))+db+tail;}
    private static String env(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" missing");return v;}
}
