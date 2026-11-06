package cn.jia.economy.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Explicit schema-maintenance entry only. Does not inspect or modify legacy money/business tables. */
public final class EconomyUsageCostSchemaInitializer implements InitializingBean {
    private final DataSource dataSource;
    public EconomyUsageCostSchemaInitializer(DataSource dataSource) { this.dataSource = dataSource; }
    @Override
    public void afterPropertiesSet() {
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-usage-cost.sql")).execute(dataSource);
    }
}
