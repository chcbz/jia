package cn.jia.test;

import cn.jia.core.util.JSONUtil;
import cn.jia.core.util.StreamUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.database.QueryDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSet;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceUtils;

import javax.sql.DataSource;
import java.io.StringWriter;
import java.lang.reflect.Type;
import java.util.List;

@Slf4j
public class DbUnitHelper {

    /**
     * 通过SQL生成DataSet
     * @param dataSource 数据源
     * @param tableName 表名
     * @param query sql
     * @return dataset
     */
    public static String printDataSet(DataSource dataSource, String tableName, String query){
        try {
            IDatabaseConnection connection = new DatabaseConnection(DataSourceUtils.getConnection(dataSource));
            QueryDataSet dataSet = new QueryDataSet(connection);
            dataSet.addTable(tableName,query);
            StringWriter out = new StringWriter();
            FlatXmlDataSet.write(dataSet, out);
            return out.toString();
        } catch (Exception e) {
            log.error("[printDataSet]error", e);
            return null;
        }
    }

    /**
     * 通过JSON文件生成实体
     * @param resource 资源
     * @param clazz 实体类型
     * @param <T> 类类型
     * @return 实体
     */
    public static <T> T readJsonEntity(Resource resource, Class<T> clazz) {
        try {
            String str = StreamUtil.readText(resource.getInputStream());
            return JSONUtil.fromJson(str, clazz);
        } catch (Exception e) {
            log.error("[readJsonEntity]error", e);
            return null;
        }
    }

    /**
     * 通过JSON文件生成实体列表
     * @param resource 资源
     * @param clazz 实体类型
     * @param <T> 类类型
     * @return 实体列表
     */
    public static <T> List<T> readJsonEntitys(Resource resource, Class<T> clazz) {
        try {
            String str = StreamUtil.readText(resource.getInputStream());
            return JSONUtil.jsonToList(str, new TypeReference<List<T>>() {
                @Override
                public Type getType() {
                    return clazz;
                }
            });
        } catch (Exception e) {
            log.error("[readJsonEntity]error", e);
            return null;
        }
    }
}
