package cn.jia.user;

import cn.jia.common.entity.BaseEntity;
import cn.jia.common.service.IBaseService;
import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.test.BaseTest;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Types;
import java.util.Collections;

public class MybatisGeneratorTest extends BaseTest {
    private final static String PARENT_PACKAGE = "cn.jia";
    private final static String MODULE_NAME = "user";
    private final static String[] TABLES = new String[]{"user_auth", "user_group", "user_group_rel", "user_info", "user_msg", "user_org", "user_org_rel", "user_role", "user_role_rel"};

    @Value("${spring.datasource.driverClassName}")
    String driverClassName;
    @Value("${spring.datasource.url}")
    String url;
    @Value("${spring.datasource.username}")
    String username;
    @Value("${spring.datasource.password}")
    String password;

    @Test
    @Disabled
    void testGenerator() {
        String projectPath = System.getProperty("user.dir");
        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> {
                    builder.author("chc") // 设置作者
                            .enableSpringdoc()
                            .disableOpenDir()
                            .outputDir(projectPath + "/src/main/java"); // 指定输出目录
                })
                .dataSourceConfig(builder -> builder.typeConvertHandler((globalConfig, typeRegistry, metaInfo) -> {
                    int typeCode = metaInfo.getJdbcType().TYPE_CODE;
                    if (typeCode == Types.SMALLINT) {
                        // 自定义类型转换
                        return DbColumnType.INTEGER;
                    }
                    return typeRegistry.getColumnType(metaInfo);

                }))
                .packageConfig(builder -> {
                    builder.parent(PARENT_PACKAGE) // 设置父包名
                            .moduleName(MODULE_NAME) // 设置父包模块名
                            .pathInfo(Collections.singletonMap(OutputFile.xml,
                                    projectPath + "/src/main/resources/mapper/" +
                                            PARENT_PACKAGE.replace(".", "/") + "/" +
                                            MODULE_NAME)); // 设置mapperXml生成路径
                })
                .strategyConfig(builder ->
                        builder.addInclude(TABLES) // 设置需要生成的表名
                                .addTablePrefix(MODULE_NAME + "_") // 设置过滤表前缀
                                .serviceBuilder().superServiceClass(IBaseService.class)
                                .superServiceImplClass(BaseServiceImpl.class)
                                .mapperBuilder().enableBaseColumnList()
                                .enableBaseResultMap()
                                .entityBuilder().superClass(BaseEntity.class)
                                .enableChainModel()
                                .enableLombok().disableSerialVersionUID()
                                .columnNaming(NamingStrategy.underline_to_camel)
                                .naming(NamingStrategy.underline_to_camel))
                .templateEngine(new FreemarkerTemplateEngine()) // 使用Freemarker引擎模板，默认的是Velocity引擎模板
                .templateConfig(builder -> builder.controller(""))
                .execute();
    }
}
