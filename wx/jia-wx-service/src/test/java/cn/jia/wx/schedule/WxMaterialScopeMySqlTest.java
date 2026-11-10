package cn.jia.wx.schedule;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.mat.dao.impl.MatPhraseDaoImpl;
import cn.jia.mat.dao.impl.MatVoteQuestionDaoImpl;
import cn.jia.mat.mapper.MatPhraseMapper;
import cn.jia.mat.mapper.MatVoteQuestionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class WxMaterialScopeMySqlTest extends SkillManagementMySqlFixture {
    JdbcTemplate jdbc; MatVoteQuestionDaoImpl questions; MatPhraseDaoImpl phrases;
    @BeforeEach void setup() {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_wx_material CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        var ds=source("cyf_wx_material");jdbc=new JdbcTemplate(ds);
        for(var table:new String[]{"mat_vote_question","mat_vote_tick","mat_phrase"}) jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("CREATE TABLE mat_vote_question (id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),client_id VARCHAR(64),title VARCHAR(64))");
        jdbc.execute("CREATE TABLE mat_vote_tick (id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),client_id VARCHAR(64),question_id BIGINT,jiacn VARCHAR(64))");
        jdbc.execute("CREATE TABLE mat_phrase (id BIGINT PRIMARY KEY,tenant_id VARCHAR(64),client_id VARCHAR(64),content VARCHAR(64))");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("owned-wx-material",new SpringManagedTransactionFactory(),ds));
        config.addMapper(MatVoteQuestionMapper.class);config.addMapper(MatPhraseMapper.class);
        var session=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        questions=new MatVoteQuestionDaoImpl();phrases=new MatPhraseDaoImpl();
        ReflectionTestUtils.setField(questions,"baseMapper",session.getMapper(MatVoteQuestionMapper.class));
        ReflectionTestUtils.setField(phrases,"baseMapper",session.getMapper(MatPhraseMapper.class));
        var scope=new FixedTenantAdmissionPolicy("tenant-a","client-a");
        ReflectionTestUtils.setField(questions,"processScope",scope);ReflectionTestUtils.setField(phrases,"processScope",scope);
    }
    void content(long id,String tenant,String client) {
        jdbc.update("INSERT INTO mat_vote_question VALUES (?,?,?,'question')",id,tenant,client);
        jdbc.update("INSERT INTO mat_phrase VALUES (?,?,?,'phrase')",id,tenant,client);
    }
    @Test void contentScopePrecedesOrderingAndRandomSelection() {
        content(1,"Tenant-a","client-a");content(2,"tenant-a ","client-a");
        content(3,"tenant-a","Client-a");content(4,"tenant-a","client-a ");
        assertNull(questions.selectScheduledQuestion("client-a","owner"));
        assertNull(phrases.selectScheduledRandom("client-a"));
        content(5,"tenant-a","client-a");
        assertEquals(5L,questions.selectScheduledQuestion("client-a","owner").getId());
        assertEquals(5L,phrases.selectScheduledRandom("client-a").getId());
        assertThrows(IllegalArgumentException.class,()->questions.selectScheduledQuestion("other","owner"));
        assertThrows(IllegalArgumentException.class,()->phrases.selectScheduledRandom("other"));
    }
    @Test void onlyExactScopedAnswerExcludesQuestion() {
        content(1,"tenant-a","client-a");content(2,"tenant-a","client-a");
        String sql="INSERT INTO mat_vote_tick VALUES (?,?,?,?,?)";
        jdbc.update(sql,1,"Tenant-a","client-a",1,"owner");
        jdbc.update(sql,2,"tenant-a","Client-a",1,"owner");
        jdbc.update(sql,3,"tenant-a","client-a",1,"Owner");
        jdbc.update(sql,4,"tenant-a","client-a",1,"owner ");
        assertEquals(1L,questions.selectScheduledQuestion("client-a","owner").getId());
        jdbc.update(sql,5,"tenant-a","client-a",1,"owner");
        assertEquals(2L,questions.selectScheduledQuestion("client-a","owner").getId());
        // A NULL question reference must not poison the anti-join as NOT IN would.
        jdbc.update(sql,6,"tenant-a","client-a",null,"owner");
        assertEquals(2L,questions.selectScheduledQuestion("client-a","owner").getId());
    }
}
