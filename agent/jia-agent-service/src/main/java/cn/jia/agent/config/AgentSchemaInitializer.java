package cn.jia.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        ensureAgentPersonaColumns();
        ensureAgentRuntimeColumns();
        ensureBindingTable();
        ensureTaskNoteTable();
        seedWaterMarginPersonas();
    }

    private void ensureAgentPersonaColumns() {
        addColumnIfMissing("agent_persona", "persona_code",
                "persona_code VARCHAR(50) DEFAULT NULL COMMENT 'Water Margin persona code'");
        addColumnIfMissing("agent_persona", "rank_no",
                "rank_no INT DEFAULT NULL COMMENT 'Liangshan ranking number'");
        addColumnIfMissing("agent_persona", "star_name",
                "star_name VARCHAR(50) DEFAULT NULL COMMENT 'Star name'");
        addColumnIfMissing("agent_persona", "visual_config",
                "visual_config TEXT COMMENT 'Frontend visual config JSON'");
        addColumnIfMissing("agent_persona", "system_agent",
                "system_agent TINYINT(1) DEFAULT 0 COMMENT 'System controlled persona'");
        addIndexIfMissing("agent_persona", "uk_agent_persona_code",
                "CREATE UNIQUE INDEX uk_agent_persona_code ON agent_persona (persona_code)");
        addIndexIfMissing("agent_persona", "idx_agent_persona_system",
                "CREATE INDEX idx_agent_persona_system ON agent_persona (system_agent)");
    }

    private void ensureAgentRuntimeColumns() {
        addColumnIfMissing("agent_runtime", "owner_jiacn",
                "owner_jiacn VARCHAR(50) DEFAULT NULL COMMENT 'Bound user Jia account'");
        addColumnIfMissing("agent_runtime", "persona_code",
                "persona_code VARCHAR(50) DEFAULT NULL COMMENT 'Bound persona code'");
        addColumnIfMissing("agent_runtime", "binding_id",
                "binding_id BIGINT DEFAULT NULL COMMENT 'Persona binding ID'");
        addIndexIfMissing("agent_runtime", "idx_agent_runtime_owner",
                "CREATE INDEX idx_agent_runtime_owner ON agent_runtime (client_id, owner_jiacn)");
        addIndexIfMissing("agent_runtime", "idx_agent_runtime_persona_code",
                "CREATE INDEX idx_agent_runtime_persona_code ON agent_runtime (persona_code)");
    }

    private void ensureBindingTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_persona_binding (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    jiacn               VARCHAR(50) NOT NULL COMMENT 'Bound Jia account',
                    persona_code        VARCHAR(50) NOT NULL COMMENT 'Water Margin persona code',
                    agent_id            VARCHAR(100) NOT NULL COMMENT 'Runtime agent ID',
                    bound_at            BIGINT NOT NULL COMMENT 'Bind time',
                    status              INT NOT NULL DEFAULT 1 COMMENT '1 active, 0 inactive',
                    active_persona_code VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN persona_code ELSE NULL END) STORED,
                    active_agent_id     VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN agent_id ELSE NULL END) STORED,
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT 'Tenant ID reserved',
                    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'Client ID',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_binding_active_persona (client_id, active_persona_code),
                    UNIQUE KEY uk_agent_binding_active_agent (client_id, active_agent_id),
                    KEY idx_agent_binding_user (client_id, jiacn, status),
                    KEY idx_agent_binding_agent (client_id, agent_id, status),
                    KEY idx_agent_binding_persona (client_id, persona_code, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent persona binding'
                """);
        addColumnIfMissing("agent_persona_binding", "active_persona_code",
                "active_persona_code VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN persona_code ELSE NULL END) STORED");
        addColumnIfMissing("agent_persona_binding", "active_agent_id",
                "active_agent_id VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN agent_id ELSE NULL END) STORED");
        addIndexIfMissing("agent_persona_binding", "uk_agent_binding_active_persona",
                "CREATE UNIQUE INDEX uk_agent_binding_active_persona ON agent_persona_binding (client_id, active_persona_code)");
        addIndexIfMissing("agent_persona_binding", "uk_agent_binding_active_agent",
                "CREATE UNIQUE INDEX uk_agent_binding_active_agent ON agent_persona_binding (client_id, active_agent_id)");
    }

    private void ensureTaskNoteTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_note (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    author_id           VARCHAR(100) DEFAULT NULL COMMENT 'Author ID',
                    author_type         VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user/agent/system',
                    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary' COMMENT 'summary/report/meeting/system',
                    content             TEXT NOT NULL COMMENT 'Note content',
                    created_at          BIGINT NOT NULL COMMENT 'Note created time',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT 'Tenant ID',
                    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'Client ID',
                    PRIMARY KEY (id),
                    KEY idx_agent_task_note_task_id (task_id),
                    KEY idx_agent_task_note_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task notes'
                """);
    }

    private void seedWaterMarginPersonas() {
        for (PersonaSeed seed : PERSONAS) {
            jdbcTemplate.update("""
                    INSERT INTO agent_persona
                        (persona_code, rank_no, star_name, name, title, avatar, visual_config, abilities,
                         personality, speaking_style, background, power, intelligence, leadership, active,
                         system_agent, create_time, update_time)
                    SELECT ?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM agent_persona WHERE persona_code = ?
                    )
                    """,
                    seed.code(), seed.rankNo(), seed.starName(), seed.name(), seed.title(),
                    visualConfig(seed.rankNo()), abilities(seed), personality(seed), speakingStyle(seed), background(seed),
                    score(seed.rankNo(), 72), score(109 - seed.rankNo(), 70), score(seed.rankNo(), 66),
                    "songjiang".equals(seed.code()), System.currentTimeMillis(), System.currentTimeMillis(), seed.code());
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                    """, Integer.class, table, column);
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
            }
        } catch (Exception e) {
            log.warn("Unable to ensure column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private void addIndexIfMissing(String table, String indexName, String sql) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                    """, Integer.class, table, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(sql);
            }
        } catch (Exception e) {
            log.warn("Unable to ensure index {}.{}: {}", table, indexName, e.getMessage());
        }
    }

    private String visualConfig(int rankNo) {
        int x = (rankNo - 1) % 6;
        int y = (rankNo - 1) / 6;
        return "{\"gridX\":" + x + ",\"gridY\":" + y + "}";
    }

    private String abilities(PersonaSeed seed) {
        if ("songjiang".equals(seed.code())) {
            return "[\"coordination\",\"dispatch\",\"planning\",\"briefing\"]";
        }
        if (seed.rankNo() <= 36) {
            return "[\"strategy\",\"execution\",\"battle\"]";
        }
        return "[\"support\",\"execution\",\"scouting\"]";
    }

    private String personality(PersonaSeed seed) {
        return seed.title() + "，梁山第" + seed.rankNo() + "位好汉。";
    }

    private String speakingStyle(PersonaSeed seed) {
        return seed.rankNo() <= 36 ? "沉稳果决，重义守信。" : "直率利落，听令而行。";
    }

    private String background(PersonaSeed seed) {
        return seed.starName() + "，" + seed.name() + "，绰号" + seed.title() + "。";
    }

    private int score(int value, int base) {
        return Math.max(35, Math.min(98, base + (value % 24)));
    }

    private record PersonaSeed(String code, int rankNo, String starName, String name, String title) {
    }

    private static final PersonaSeed[] PERSONAS = new PersonaSeed[] {
            new PersonaSeed("songjiang", 1, "天魁星", "宋江", "及时雨"),
            new PersonaSeed("lujunyi", 2, "天罡星", "卢俊义", "玉麒麟"),
            new PersonaSeed("wuyong", 3, "天机星", "吴用", "智多星"),
            new PersonaSeed("gongsunsheng", 4, "天闲星", "公孙胜", "入云龙"),
            new PersonaSeed("guansheng", 5, "天勇星", "关胜", "大刀"),
            new PersonaSeed("linchong", 6, "天雄星", "林冲", "豹子头"),
            new PersonaSeed("qinming", 7, "天猛星", "秦明", "霹雳火"),
            new PersonaSeed("huyanzhuo", 8, "天威星", "呼延灼", "双鞭"),
            new PersonaSeed("huarong", 9, "天英星", "花荣", "小李广"),
            new PersonaSeed("chaijin", 10, "天贵星", "柴进", "小旋风"),
            new PersonaSeed("liying", 11, "天富星", "李应", "扑天雕"),
            new PersonaSeed("zhutong", 12, "天满星", "朱仝", "美髯公"),
            new PersonaSeed("luzhishen", 13, "天孤星", "鲁智深", "花和尚"),
            new PersonaSeed("wusong", 14, "天伤星", "武松", "行者"),
            new PersonaSeed("dongping", 15, "天立星", "董平", "双枪将"),
            new PersonaSeed("zhangqing", 16, "天捷星", "张清", "没羽箭"),
            new PersonaSeed("yangzhi", 17, "天暗星", "杨志", "青面兽"),
            new PersonaSeed("xuning", 18, "天佑星", "徐宁", "金枪手"),
            new PersonaSeed("suochao", 19, "天空星", "索超", "急先锋"),
            new PersonaSeed("daizong", 20, "天速星", "戴宗", "神行太保"),
            new PersonaSeed("liutang", 21, "天异星", "刘唐", "赤发鬼"),
            new PersonaSeed("likui", 22, "天杀星", "李逵", "黑旋风"),
            new PersonaSeed("shijin", 23, "天微星", "史进", "九纹龙"),
            new PersonaSeed("muhong", 24, "天究星", "穆弘", "没遮拦"),
            new PersonaSeed("leiheng", 25, "天退星", "雷横", "插翅虎"),
            new PersonaSeed("lijun", 26, "天寿星", "李俊", "混江龙"),
            new PersonaSeed("ruanxiaoer", 27, "天剑星", "阮小二", "立地太岁"),
            new PersonaSeed("zhangheng", 28, "天平星", "张横", "船火儿"),
            new PersonaSeed("ruanxiaowu", 29, "天罪星", "阮小五", "短命二郎"),
            new PersonaSeed("zhangshun", 30, "天损星", "张顺", "浪里白条"),
            new PersonaSeed("ruanxiaoqi", 31, "天败星", "阮小七", "活阎罗"),
            new PersonaSeed("yangxiong", 32, "天牢星", "杨雄", "病关索"),
            new PersonaSeed("shixiu", 33, "天慧星", "石秀", "拼命三郎"),
            new PersonaSeed("xiezhen", 34, "天暴星", "解珍", "两头蛇"),
            new PersonaSeed("xiebao", 35, "天哭星", "解宝", "双尾蝎"),
            new PersonaSeed("yanqing", 36, "天巧星", "燕青", "浪子"),
            new PersonaSeed("zhuwu", 37, "地魁星", "朱武", "神机军师"),
            new PersonaSeed("huangxin", 38, "地煞星", "黄信", "镇三山"),
            new PersonaSeed("sunli", 39, "地勇星", "孙立", "病尉迟"),
            new PersonaSeed("xuanzan", 40, "地杰星", "宣赞", "丑郡马"),
            new PersonaSeed("haosiwen", 41, "地雄星", "郝思文", "井木犴"),
            new PersonaSeed("hantao", 42, "地威星", "韩滔", "百胜将"),
            new PersonaSeed("pengqi", 43, "地英星", "彭玘", "天目将"),
            new PersonaSeed("shantinggui", 44, "地奇星", "单廷珪", "圣水将"),
            new PersonaSeed("weidingguo", 45, "地猛星", "魏定国", "神火将"),
            new PersonaSeed("xiaorang", 46, "地文星", "萧让", "圣手书生"),
            new PersonaSeed("peixuan", 47, "地正星", "裴宣", "铁面孔目"),
            new PersonaSeed("oupeng", 48, "地阔星", "欧鹏", "摩云金翅"),
            new PersonaSeed("dengfei", 49, "地阖星", "邓飞", "火眼狻猊"),
            new PersonaSeed("yanshun", 50, "地强星", "燕顺", "锦毛虎"),
            new PersonaSeed("yanglin", 51, "地暗星", "杨林", "锦豹子"),
            new PersonaSeed("lingzhen", 52, "地轴星", "凌振", "轰天雷"),
            new PersonaSeed("jiangjing", 53, "地会星", "蒋敬", "神算子"),
            new PersonaSeed("lvfang", 54, "地佐星", "吕方", "小温侯"),
            new PersonaSeed("guosheng", 55, "地佑星", "郭盛", "赛仁贵"),
            new PersonaSeed("andaoquan", 56, "地灵星", "安道全", "神医"),
            new PersonaSeed("huangfuduan", 57, "地兽星", "皇甫端", "紫髯伯"),
            new PersonaSeed("wangying", 58, "地微星", "王英", "矮脚虎"),
            new PersonaSeed("husanniang", 59, "地慧星", "扈三娘", "一丈青"),
            new PersonaSeed("baoxu", 60, "地暴星", "鲍旭", "丧门神"),
            new PersonaSeed("fanrui", 61, "地然星", "樊瑞", "混世魔王"),
            new PersonaSeed("kongming", 62, "地猖星", "孔明", "毛头星"),
            new PersonaSeed("kongliang", 63, "地狂星", "孔亮", "独火星"),
            new PersonaSeed("xiangchong", 64, "地飞星", "项充", "八臂哪吒"),
            new PersonaSeed("ligun", 65, "地走星", "李衮", "飞天大圣"),
            new PersonaSeed("jindajian", 66, "地巧星", "金大坚", "玉臂匠"),
            new PersonaSeed("malin", 67, "地明星", "马麟", "铁笛仙"),
            new PersonaSeed("tongwei", 68, "地进星", "童威", "出洞蛟"),
            new PersonaSeed("tongmeng", 69, "地退星", "童猛", "翻江蜃"),
            new PersonaSeed("mengkang", 70, "地满星", "孟康", "玉幡竿"),
            new PersonaSeed("houjian", 71, "地遂星", "侯健", "通臂猿"),
            new PersonaSeed("chenda", 72, "地周星", "陈达", "跳涧虎"),
            new PersonaSeed("yangchun", 73, "地隐星", "杨春", "白花蛇"),
            new PersonaSeed("zhengtianshou", 74, "地异星", "郑天寿", "白面郎君"),
            new PersonaSeed("taozongwang", 75, "地理星", "陶宗旺", "九尾龟"),
            new PersonaSeed("songqing", 76, "地俊星", "宋清", "铁扇子"),
            new PersonaSeed("yuehe", 77, "地乐星", "乐和", "铁叫子"),
            new PersonaSeed("gongwang", 78, "地捷星", "龚旺", "花项虎"),
            new PersonaSeed("dingdesun", 79, "地速星", "丁得孙", "中箭虎"),
            new PersonaSeed("muchun", 80, "地镇星", "穆春", "小遮拦"),
            new PersonaSeed("caozheng", 81, "地稽星", "曹正", "操刀鬼"),
            new PersonaSeed("songwan", 82, "地魔星", "宋万", "云里金刚"),
            new PersonaSeed("duqian", 83, "地妖星", "杜迁", "摸着天"),
            new PersonaSeed("xueyong", 84, "地幽星", "薛永", "病大虫"),
            new PersonaSeed("shien", 85, "地伏星", "施恩", "金眼彪"),
            new PersonaSeed("lizhong", 86, "地僻星", "李忠", "打虎将"),
            new PersonaSeed("zhoutong", 87, "地空星", "周通", "小霸王"),
            new PersonaSeed("tanglong", 88, "地孤星", "汤隆", "金钱豹子"),
            new PersonaSeed("duxing", 89, "地全星", "杜兴", "鬼脸儿"),
            new PersonaSeed("zouyuan", 90, "地短星", "邹渊", "出林龙"),
            new PersonaSeed("zourun", 91, "地角星", "邹润", "独角龙"),
            new PersonaSeed("zhugui", 92, "地囚星", "朱贵", "旱地忽律"),
            new PersonaSeed("zhufu", 93, "地藏星", "朱富", "笑面虎"),
            new PersonaSeed("caifu", 94, "地平星", "蔡福", "铁臂膊"),
            new PersonaSeed("caiqing", 95, "地损星", "蔡庆", "一枝花"),
            new PersonaSeed("lili", 96, "地奴星", "李立", "催命判官"),
            new PersonaSeed("liyun", 97, "地察星", "李云", "青眼虎"),
            new PersonaSeed("jiaoting", 98, "地恶星", "焦挺", "没面目"),
            new PersonaSeed("shiyong", 99, "地丑星", "石勇", "石将军"),
            new PersonaSeed("sunxin", 100, "地数星", "孙新", "小尉迟"),
            new PersonaSeed("gudasao", 101, "地阴星", "顾大嫂", "母大虫"),
            new PersonaSeed("zhangqing_gardener", 102, "地刑星", "张青", "菜园子"),
            new PersonaSeed("sunerniang", 103, "地壮星", "孙二娘", "母夜叉"),
            new PersonaSeed("wangdingliu", 104, "地劣星", "王定六", "活闪婆"),
            new PersonaSeed("yubaosi", 105, "地健星", "郁保四", "险道神"),
            new PersonaSeed("baisheng", 106, "地耗星", "白胜", "白日鼠"),
            new PersonaSeed("shiqian", 107, "地贼星", "时迁", "鼓上蚤"),
            new PersonaSeed("duanjingzhu", 108, "地狗星", "段景住", "金毛犬")
    };
}
