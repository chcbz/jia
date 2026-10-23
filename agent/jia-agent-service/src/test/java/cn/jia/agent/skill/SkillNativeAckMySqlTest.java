package cn.jia.agent.skill;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;

/** Same native ACK/download/result transactions against exact unadapted MySQL schemas.
 * Only an explicitly isolated loopback fixture is used, never production SQL. */
@EnabledIfEnvironmentVariable(named="CYF_SKILL_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/.*")
public class SkillNativeAckMySqlTest extends SkillMarketplaceRealTransactionTest {
    private JdbcTemplate admin;
    private String database;
    @Override protected DriverManagerDataSource fixtureDataSource() {
        if(!"true".equals(System.getenv("CYF_SKILL_MYSQL_ISOLATED"))) throw new IllegalStateException("Exclusive fixture required");
        String url=System.getenv("CYF_SKILL_MYSQL_URL");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,"root",""));
        database="skill_ack_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        return new DriverManagerDataSource(url.replace("/?","/"+database+"?"),"root","");
    }
    @Override protected boolean mysqlFixture() { return true; }
    @Override protected void cleanupFixture() { if(admin!=null && database!=null) admin.execute("DROP DATABASE "+database); }
}
