package cn.jia.admin.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Slf4j
public class DynamicDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        String db = DataSourceContextHolder.getDB();
        log.info("数据源为"+db);
        return db;
    }
}