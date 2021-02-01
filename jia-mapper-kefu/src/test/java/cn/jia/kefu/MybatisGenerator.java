package cn.jia.kefu;

import cn.jia.common.entity.BaseEntity;
import cn.jia.common.service.IBaseService;
import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.test.BaseTest;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import com.baomidou.mybatisplus.generator.AutoGenerator;
import com.baomidou.mybatisplus.generator.InjectionConfig;
import com.baomidou.mybatisplus.generator.config.*;
import com.baomidou.mybatisplus.generator.config.po.TableInfo;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;

public class MybatisGenerator extends BaseTest {
    private final static String PARENT_PACKAGE = "cn.jia";
    private final static String MODULE_NAME = "kefu";
    private final static String[] TABLES = new String[]{"kefu_faq", "kefu_message", "kefu_msg_type"};

    @Value("${spring.datasource.driverClassName}")
    String driverClassName;
    @Value("${spring.datasource.url}")
    String url;
    @Value("${spring.datasource.username}")
    String username;
    @Value("${spring.datasource.password}")
    String password;

    @Test
//    @Disabled
    void testGenerator() {
        AutoGenerator generator = new AutoGenerator();

        GlobalConfig globalConfig = new GlobalConfig();
        String projectPath = System.getProperty("user.dir");
        globalConfig.setOutputDir(projectPath + "/src/main/java");
        globalConfig.setAuthor("chc");
        globalConfig.setOpen(false);
        globalConfig.setBaseResultMap(false);
        globalConfig.setBaseColumnList(true);
        globalConfig.setEnableCache(false);
        // 实体属性 Swagger2 注解
        globalConfig.setSwagger2(true);
        generator.setGlobalConfig(globalConfig);

        DataSourceConfig dataSourceConfig = new DataSourceConfig();
        dataSourceConfig.setUrl(url);
        dataSourceConfig.setDriverName(driverClassName);
        dataSourceConfig.setUsername(username);
        dataSourceConfig.setPassword(password);
        generator.setDataSource(dataSourceConfig);

        PackageConfig packageConfig = new PackageConfig();
        packageConfig.setParent(PARENT_PACKAGE);
        packageConfig.setModuleName(MODULE_NAME);
        generator.setPackageInfo(packageConfig);

        InjectionConfig injectionConfig = new InjectionConfig() {
            @Override
            public void initMap() {
                // to do nothing
            }
        };

        // 如果模板引擎是 freemarker
//        String templatePath = "/templates/mapper.xml.ftl";
        // 如果模板引擎是 velocity
         String templatePath = "/templates/mapper.xml.vm";

        // 自定义输出配置
        List<FileOutConfig> focList = new ArrayList<>();
        // 自定义配置会被优先输出
        focList.add(new FileOutConfig(templatePath) {
            @Override
            public String outputFile(TableInfo tableInfo) {
                // 自定义输出文件名 ， 如果你 Entity 设置了前后缀、此处注意 xml 的名称会跟着发生变化！！
                return projectPath + "/src/main/resources/mapper/" + PARENT_PACKAGE.replace(".", "/")
                        + "/" + MODULE_NAME + "/" + tableInfo.getEntityName() + "Mapper" + StringPool.DOT_XML;
            }
        });
        injectionConfig.setFileOutConfigList(focList);
        generator.setCfg(injectionConfig);

        TemplateConfig templateConfig = new TemplateConfig();
        templateConfig.setController("");
        templateConfig.setXml(null);
        generator.setTemplate(templateConfig);

        StrategyConfig strategyConfig = new StrategyConfig();
        strategyConfig.setNaming(NamingStrategy.underline_to_camel);
        strategyConfig.setColumnNaming(NamingStrategy.underline_to_camel);
        strategyConfig.setSuperEntityClass(BaseEntity.class);
        strategyConfig.setSuperServiceClass(IBaseService.class.getName());
        strategyConfig.setSuperServiceImplClass(BaseServiceImpl.class.getName());
        strategyConfig.setEntityLombokModel(true);
        strategyConfig.setTablePrefix("");
        strategyConfig.setInclude(TABLES);
        generator.setStrategy(strategyConfig);

        generator.execute();
    }
}
