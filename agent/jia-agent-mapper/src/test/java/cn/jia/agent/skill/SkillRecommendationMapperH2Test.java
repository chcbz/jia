package cn.jia.agent.skill;

import cn.jia.agent.mapper.skill.SkillManagementMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class SkillRecommendationMapperH2Test {
    @Test void activeNativeAndPurchasedAuthorizationsAreScopedWithoutMarketplaceInstallationDependency() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:recommend_"+java.util.UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE economy_skill_managed_state(tenant_id VARCHAR(50),client_id VARCHAR(50),agent_id VARCHAR(100),active_installation_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE economy_skill_installation_authorization(tenant_id VARCHAR(50),client_id VARCHAR(50),installation_id VARCHAR(100),package_json VARCHAR(1000))");
        // Deliberately no marketplace installation table: native position/adopted skills are valid.
        jdbc.execute("INSERT INTO economy_skill_managed_state VALUES('t','c','a','native'),('t','c','a','market'),('other','c','a','native'),('t','other','a','native'),('t','c','b','other-agent')");
        jdbc.execute("INSERT INTO economy_skill_installation_authorization VALUES('t','c','native','native-package'),('t','c','market','market-package'),('t','c','inactive','old'),('other','c','native','private'),('t','other','native','private'),('t','c','other-agent','private')");
        var bean=new SqlSessionFactoryBean();bean.setDataSource(ds);var config=new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);config.addMapper(SkillManagementMapper.class);bean.setConfiguration(config);
        var mapper=new SqlSessionTemplate(java.util.Objects.requireNonNull(bean.getObject())).getMapper(SkillManagementMapper.class);
        var rows=mapper.activeAuthorizationsForAgent("t","c","a");
        assertEquals(java.util.List.of("market","native"),rows.stream().map(r->r.getInstallationId()).toList());
        assertEquals(java.util.List.of("market-package","native-package"),rows.stream().map(r->r.getPackageJson()).toList());
        assertTrue(mapper.activeAuthorizationsForAgent("missing","c","a").isEmpty());
        assertTrue(mapper.activeAuthorizationsForAgent("t","c","absent").isEmpty());
    }
}
