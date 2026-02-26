package cn.jia.kefu;

import cn.jia.core.entity.BaseEntity;
import cn.jia.common.service.IBaseService;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DateUtil;
import cn.jia.test.BaseDbUnitTest;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.rules.DbColumnType;
import com.baomidou.mybatisplus.generator.config.rules.NamingStrategy;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.Collections;

public class MybatisGeneratorTest extends BaseDbUnitTest {
    private final static String PARENT_PACKAGE = "cn.jia";
    private final static String MODULE_NAME = "kefu";
    private final static String[] TABLES = new String[]{"kefu_faq", "kefu_message", "kefu_msg_type", "kefu_msg_subscribe", "kefu_msg_log"};

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
                                .naming(NamingStrategy.underline_to_camel)
                                .controllerBuilder().template("")
                )
                .templateEngine(new FreemarkerTemplateEngine()) // 使用Freemarker引擎模板，默认的是Velocity引擎模板
                .execute();
    }

    @Test
    void test() {
        String[] arg = {"760636800","-381398400","466704000","-238147200","148406400","29174400","429638400","1106150400","53280000","883929600","-945158400","274550400","-256464000","-55065600","1415980800","525970800","873216000","-650016000","88099200","224352000","669052800","-69148800","823276800","-809769600","306864000","967392000","-114940800","505324800","376156800","144864000","383587200","502387200","1357228800","1523808000","671641200","-377769600","827337600","671641200","-163670400","12067200","608828400","188755200","1448208000","319737600","24508800","457718400","593712000","1415980800","1321027200","-179481600","63129600","265737600","292089600","-43142400","-620294400","1080921600","31593600","-50313600","986313600","1390838400","1063900800","456940800","698256000","751651200","668620800","-200217600","-360230400","572371200","36086400","1452787200","831571200","244828800","969552000","555865200","288460800","187977600","306000000","626803200","411494400","1168358400","651250800","55872000","263232000","25200000","232905600","388080000","161366400","117302400","744998400","1225555200","115920000","249235200","-103276800","894038400","572976000","24422400","-99043200","806169600","508867200","861206400","403891200","255542400","708278400","834768000","769795200","755884800","-201513600","748540800","221155200","474048000","234547200","157046400","-137750400","387561600","261936000","713635200","779990400","750528000","670262400","653587200","-343382400","-141292800","488390400","924624000","489772800","-815990400","985622400","845913600","756489600","1110729600","425923200","69004800","846172800","-125049600","663350400","305222400","64425600","394732800","625248000","337968000","260380800","1480867200","2044800","978278400","-172224000","1518278400","450979200","328550400","529430400","1508169600","363283200","1046361600","-485164800","-413366400","5241600","412444800","755452800","342633600","-9619200","571420800","24336000","56304000","-423043200","769881600","72374400","228931200","1217260800","373305600","530121600","-223804800","146160000","686678400","704736000","536601600","-231062400","-695635200","1410969600","1229356800","683305200","202838400","1486828800","302112000","1409846400","209577600","629740800","79545600","1245772800","682354800","229622400","809884800","-156672000","-212918400","-389865600","196444800","464198400","1479139200","-445766400","2736000","716486400","593020800","5328000","-122371200","-352195200","-545472000","887644800","-380275200","-6076800","700416000","-59558400","-204969600","51379200","600710400","1041782400","246211200","272476800","4464000","157132800","389635200","-228816000","690652800","-8755200","994694400","116092800","-416131200","-353059200","1136563200","237830400","-189417600","388857600","731174400","432921600","408124800","100454400","202233600","229017600","-57225600","49046400","67536000","-985334400","140457600","-153907200","321120000","-283766400","-60422400","336672000","337708800","158601600","1373299200","-622713600","-483177600","637862400","350582400","209836800","872784000","369504000","-340358400","-164016000","185212800","655488000","735580800","234806400","56304000","473356800","-330336000","1399392000"};
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        for (String s : arg) {
            System.out.println(dateFormat.format(DateUtil.genDate(Long.valueOf(s))));
        }
    }
}
