package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict opt-in OD02 schema bootstrap. Existing partial installs fail closed. */
public final class OutputObjectSchemaInitializer implements InitializingBean {
    private static final Pattern TABLE_NAME=Pattern.compile("(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([a-z0-9_]+)");
    private static final Pattern COLUMN=Pattern.compile("(?is)^([a-z0-9_]+)\\s+([a-z]+(?:\\([^)]*\\))?)(.*)$");
    private static final Pattern INDEX=Pattern.compile("(?is)^(UNIQUE\\s+)?KEY\\s+([a-z0-9_]+)\\s*\\(([^)]*)\\)$");
    private static final Pattern CHECK=Pattern.compile("(?is)^CONSTRAINT\\s+([a-z0-9_]+)\\s+CHECK\\s*\\((.*)\\)$");
    static final String RESOURCE = "db/output-delivery-002-object-schema.sql";
    static final List<String> TABLES = List.of("output_scope_quota", "output_binding_upload_quota",
            "output_run_upload_quota",
            "output_object", "output_upload_session", "output_object_reference",
            "output_mutation_receipt", "output_storage_cleanup_job");
    private final JdbcTemplate jdbc;

    public OutputObjectSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void afterPropertiesSet() throws Exception {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) throw new IllegalStateException("OD02 requires a JDBC DataSource");
        try (Connection c = ds.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql"))
                throw new IllegalStateException("OD02 object schema requires MySQL");
        }
        List<String> present = presentTables();
        if (!present.isEmpty() && present.size() != TABLES.size())
            throw new IllegalStateException("OD02 partial object schema: " + present);
        if (present.isEmpty()) for (String statement : ddlStatements()) jdbc.execute(statement);
        if (presentTables().size() != TABLES.size())
            throw new IllegalStateException("OD02 object schema is incomplete");
        Map<String,TableContract> contracts=contracts();
        if(!contracts.keySet().equals(new java.util.LinkedHashSet<>(TABLES)))throw new IllegalStateException("OD02 contract table drift");
        for(String table:TABLES)requireContract(table,contracts.get(table));
    }

    private List<String> presentTables() {
        String placeholders = String.join(",", TABLES.stream().map(x -> "?").toList());
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ("
                + placeholders + ") ORDER BY table_name", String.class, TABLES.toArray());
    }

    private void requireContract(String table,TableContract expected) {
        List<String> columns=jdbc.queryForList("SELECT CONCAT(column_name,'|',LOWER(column_type),'|',is_nullable,'|',COALESCE(CAST(column_default AS CHAR),'<NULL>'),'|',COALESCE(collation_name,'<NULL>'),'|',COALESCE(character_set_name,'<NULL>'),'|',COALESCE(extra,''),'|',COALESCE(generation_expression,'')) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table);
        List<String> indexes=jdbc.queryForList("SELECT CONCAT(index_name,'|',non_unique,'|',seq_in_index,'|',column_name,'|',COALESCE(CAST(sub_part AS CHAR),'<NULL>'),'|',index_type) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",String.class,table);
        indexes.sort(Comparator.naturalOrder());
        List<Map<String,Object>> checkRows=jdbc.queryForList("SELECT cc.constraint_name,cc.check_clause,tc.enforced FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.table_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK' ORDER BY cc.constraint_name",table);
        List<String> checks=new ArrayList<>();for(Map<String,Object> row:checkRows)checks.add(value(row,"constraint_name")+"|"+normalizeCheck(value(row,"check_clause"))+"|"+value(row,"enforced"));
        String engine=jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",String.class,table);
        String collation=jdbc.queryForObject("SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",String.class,table);
        requireMetadata(table,"columns",expected.columns(),columns);
        requireMetadata(table,"indexes",expected.indexes(),indexes);
        requireMetadata(table,"checks",expected.checks(),checks);
        if(!"InnoDB".equalsIgnoreCase(engine))throw new IllegalStateException("OD02 schema drift: "+table+" engine expected InnoDB but was "+engine);
        if(!"utf8mb4_0900_bin".equalsIgnoreCase(collation))throw new IllegalStateException("OD02 schema drift: "+table+" collation expected utf8mb4_0900_bin but was "+collation);
    }

    private static Map<String,TableContract> contracts()throws Exception{
        Map<String,TableContract> result=new LinkedHashMap<>();
        for(String statement:ddlStatements()){
            Matcher tableMatcher=TABLE_NAME.matcher(statement);if(!tableMatcher.find())throw new IllegalStateException("OD02 table declaration drift");String table=tableMatcher.group(1).toLowerCase(Locale.ROOT);
            int open=statement.indexOf('(',tableMatcher.end()),close=statement.toUpperCase(Locale.ROOT).lastIndexOf(") ENGINE");if(open<0||close<open)throw new IllegalStateException("OD02 table body drift: "+table);
            List<String> columns=new ArrayList<>(),indexes=new ArrayList<>(),checks=new ArrayList<>();
            for(String definition:splitDefinitions(statement.substring(open+1,close))){String part=definition.trim();String upper=part.toUpperCase(Locale.ROOT);
                if(upper.startsWith("PRIMARY KEY")){addIndex(indexes,"PRIMARY",false,columnsOf(part));continue;}
                Matcher index=INDEX.matcher(part);if(index.matches()){addIndex(indexes,index.group(2),index.group(1)==null,columnsOf(index.group(3)));continue;}
                Matcher check=CHECK.matcher(part);if(check.matches()){checks.add(check.group(1)+"|"+normalizeCheck(check.group(2))+"|YES");continue;}
                Matcher column=COLUMN.matcher(part);if(!column.matches())throw new IllegalStateException("OD02 column declaration drift: "+table);
                String name=column.group(1),type=column.group(2).toLowerCase(Locale.ROOT),tail=column.group(3);if(type.equals("boolean"))type="tinyint(1)";
                boolean nullable=!tail.toUpperCase(Locale.ROOT).contains("NOT NULL");Matcher defaultValue=Pattern.compile("(?i)\\bDEFAULT\\s+([^\\s]+)").matcher(tail);String defaultText=defaultValue.find()?normalizeDefault(defaultValue.group(1)):"<NULL>";
                Matcher explicitCollation=Pattern.compile("(?i)\\bCOLLATE\\s+([a-z0-9_]+)").matcher(tail);boolean character=type.startsWith("varchar(");String columnCollation=character?(explicitCollation.find()?explicitCollation.group(1).toLowerCase(Locale.ROOT):"utf8mb4_0900_bin"):"<NULL>";String charset=character?"utf8mb4":"<NULL>";
                columns.add(name+"|"+type+"|"+(nullable?"YES":"NO")+"|"+defaultText+"|"+columnCollation+"|"+charset+"||");
            }
            indexes.sort(Comparator.naturalOrder());checks.sort(Comparator.naturalOrder());result.put(table,new TableContract(List.copyOf(columns),List.copyOf(indexes),List.copyOf(checks)));
        }
        return result;
    }

    private static List<String> splitDefinitions(String body){List<String> parts=new ArrayList<>();int depth=0,start=0;for(int i=0;i<body.length();i++){char c=body.charAt(i);if(c=='(')depth++;else if(c==')')depth--;else if(c==','&&depth==0){parts.add(body.substring(start,i));start=i+1;}}parts.add(body.substring(start));return parts;}
    private static List<String> columnsOf(String definition){int open=definition.indexOf('('),close=definition.lastIndexOf(')');String body=open>=0&&close>open?definition.substring(open+1,close):definition;return java.util.Arrays.stream(body.split(",")).map(String::trim).map(x->x.replace("`","")).toList();}
    private static void addIndex(List<String> target,String name,boolean nonUnique,List<String> columns){for(int i=0;i<columns.size();i++)target.add(name+"|"+(nonUnique?1:0)+"|"+(i+1)+"|"+columns.get(i)+"|<NULL>|BTREE");}
    private static String normalizeDefault(String value){String normalized=value.replace("'","").toLowerCase(Locale.ROOT);return normalized.equals("false")?"0":normalized;}
    private static String normalizeCheck(String value){return value.replace("`","").replaceAll("[\\s()]","").toLowerCase(Locale.ROOT);}
    private static void requireMetadata(String table,String kind,List<String> expected,List<String> actual){if(!expected.equals(actual))throw new IllegalStateException("OD02 schema drift: "+table+" "+kind+" expected "+expected+" but was "+actual);}
    private static String value(Map<String,Object> row,String key){for(var entry:row.entrySet())if(entry.getKey().equalsIgnoreCase(key))return Objects.toString(entry.getValue(),"");throw new IllegalStateException("OD02 metadata field missing: "+key);}
    private record TableContract(List<String> columns,List<String> indexes,List<String> checks){}

    static List<String> ddlStatements() throws Exception {
        String sql = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            String clean = part.replaceAll("(?m)^\\s*--.*$", " ").trim();
            if (!clean.isEmpty()) statements.add(clean);
        }
        if (statements.size() != TABLES.size()) throw new IllegalStateException("OD02 migration statement count drift");
        return statements;
    }
}
