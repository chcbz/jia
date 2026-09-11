package cn.jia.chat.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix="agent.output-delivery",name="enabled",havingValue="true")
public final class ChatOutputSchemaInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public ChatOutputSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Override public void run(ApplicationArguments args) {
        if (h2()) {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS chat_output (
                      tenant_id VARBINARY(200) NOT NULL,client_id VARBINARY(200) NOT NULL,
                      conversation_id BIGINT NOT NULL,output_id VARBINARY(400) NOT NULL,
                      output_version BIGINT NOT NULL,run_id VARBINARY(100) NOT NULL,
                      producer_agent_id VARBINARY(400) NOT NULL,title VARCHAR(255) NOT NULL,
                      file_name VARCHAR(255),artifact_type VARCHAR(30) NOT NULL,content TEXT,
                      object_id VARBINARY(100),content_hash BINARY(32) NOT NULL,
                      content_byte_length BIGINT NOT NULL,mime_type VARCHAR(100) NOT NULL,
                      state VARCHAR(20) NOT NULL,retain_until BIGINT NOT NULL,
                      created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY(tenant_id,client_id,output_id,output_version),
                      CONSTRAINT chk_chat_output_payload CHECK ((content IS NULL) &lt;&gt; (object_id IS NULL)),
                      CONSTRAINT chk_chat_output_version CHECK (output_version&gt;0),
                      CONSTRAINT chk_chat_output_length CHECK (content_byte_length&gt;=0))
                    """.replace("&lt;", "<").replace("&gt;", ">"));
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chat_output_source ON chat_output(tenant_id,client_id,conversation_id,created_at,output_id,output_version)");
            return;
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_output (
                  tenant_id VARBINARY(200) NOT NULL,client_id VARBINARY(200) NOT NULL,
                  conversation_id BIGINT NOT NULL,output_id VARBINARY(400) NOT NULL,
                  output_version BIGINT NOT NULL,run_id VARBINARY(100) NOT NULL,
                  producer_agent_id VARBINARY(400) NOT NULL,title VARCHAR(255) NOT NULL,
                  file_name VARCHAR(255) COLLATE utf8mb4_0900_bin NULL,
                  artifact_type VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,content MEDIUMTEXT NULL,
                  object_id VARBINARY(100) NULL,content_hash BINARY(32) NOT NULL,
                  content_byte_length BIGINT NOT NULL,mime_type VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  state VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,retain_until BIGINT NOT NULL,
                  created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,row_version BIGINT NOT NULL DEFAULT 0,
                  PRIMARY KEY(tenant_id,client_id,output_id,output_version),
                  KEY idx_chat_output_source(tenant_id,client_id,conversation_id,created_at,output_id,output_version),
                  CONSTRAINT chk_chat_output_payload CHECK ((content IS NULL) <> (object_id IS NULL)),
                  CONSTRAINT chk_chat_output_version CHECK (output_version>0),
                  CONSTRAINT chk_chat_output_length CHECK (content_byte_length>=0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        validateMySql();
    }

    private void validateMySql(){
        List<Map<String,Object>> rows=jdbc.queryForList("""
                SELECT column_name,data_type,column_type,is_nullable,collation_name,column_default
                FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_output'
                ORDER BY ordinal_position
                """);
        Map<String,Column> expected=new LinkedHashMap<>();
        add(expected,"tenant_id","varbinary","varbinary(200)",false,null,null);
        add(expected,"client_id","varbinary","varbinary(200)",false,null,null);
        add(expected,"conversation_id","bigint","bigint",false,null,null);
        add(expected,"output_id","varbinary","varbinary(400)",false,null,null);
        add(expected,"output_version","bigint","bigint",false,null,null);
        add(expected,"run_id","varbinary","varbinary(100)",false,null,null);
        add(expected,"producer_agent_id","varbinary","varbinary(400)",false,null,null);
        add(expected,"title","varchar","varchar(255)",false,"utf8mb4_0900_bin",null);
        add(expected,"file_name","varchar","varchar(255)",true,"utf8mb4_0900_bin",null);
        add(expected,"artifact_type","varchar","varchar(30)",false,"utf8mb4_0900_bin",null);
        add(expected,"content","mediumtext","mediumtext",true,"utf8mb4_0900_bin",null);
        add(expected,"object_id","varbinary","varbinary(100)",true,null,null);
        add(expected,"content_hash","binary","binary(32)",false,null,null);
        add(expected,"content_byte_length","bigint","bigint",false,null,null);
        add(expected,"mime_type","varchar","varchar(100)",false,"utf8mb4_0900_bin",null);
        add(expected,"state","varchar","varchar(20)",false,"utf8mb4_0900_bin",null);
        add(expected,"retain_until","bigint","bigint",false,null,null);
        add(expected,"created_at","bigint","bigint",false,null,null);
        add(expected,"updated_at","bigint","bigint",false,null,null);
        add(expected,"row_version","bigint","bigint",false,null,"0");
        Map<String,Map<String,Object>> actual=new LinkedHashMap<>();
        for(Map<String,Object> row:rows)actual.put(value(row,"column_name"),row);
        if(!actual.keySet().equals(expected.keySet()))throw incompatible("columns",expected.keySet(),actual.keySet());
        for(var entry:expected.entrySet()){
            Map<String,Object> row=actual.get(entry.getKey());Column e=entry.getValue();
            Column a=new Column(value(row,"data_type"),value(row,"column_type"),
                    "YES".equalsIgnoreCase(value(row,"is_nullable")),nullable(row,"collation_name"),
                    nullable(row,"column_default"));
            if(!e.equals(a))throw incompatible("column "+entry.getKey(),e,a);
        }
        Map<String,Object> table=jdbc.queryForMap("""
                SELECT engine,table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='chat_output'
                """);
        if(!"InnoDB".equalsIgnoreCase(value(table,"engine"))
                ||!"utf8mb4_0900_bin".equalsIgnoreCase(value(table,"table_collation")))
            throw incompatible("table", "InnoDB/utf8mb4_0900_bin", table);
        List<Map<String,Object>> indexes=jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='chat_output'
                ORDER BY index_name,seq_in_index
                """);
        Map<String,List<Map<String,Object>>> grouped=new LinkedHashMap<>();
        for(Map<String,Object> row:indexes)grouped.computeIfAbsent(value(row,"index_name"),x->new java.util.ArrayList<>()).add(row);
        if(!grouped.keySet().equals(Set.of("PRIMARY","idx_chat_output_source")))
            throw incompatible("indexes",Set.of("PRIMARY","idx_chat_output_source"),grouped.keySet());
        requireIndex(grouped,"PRIMARY",false,List.of("tenant_id","client_id","output_id","output_version"));
        requireIndex(grouped,"idx_chat_output_source",true,List.of("tenant_id","client_id","conversation_id","created_at","output_id","output_version"));
        Set<String> checks=new java.util.HashSet<>(jdbc.queryForList("""
                SELECT constraint_name FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE() AND table_name='chat_output' AND constraint_type='CHECK'
                """,String.class));
        Set<String> required=Set.of("chk_chat_output_payload","chk_chat_output_version","chk_chat_output_length");
        if(!checks.equals(required))throw incompatible("checks",required,checks);
    }

    private void requireIndex(Map<String,List<Map<String,Object>>> indexes,String name,
            boolean nonUnique,List<String> columns){List<Map<String,Object>> rows=indexes.get(name);
        boolean actualNonUnique=((Number)rows.getFirst().get("NON_UNIQUE")).intValue()!=0;
        List<String> actual=rows.stream().map(r->value(r,"column_name")).toList();
        boolean full=rows.stream().allMatch(r->r.get("SUB_PART")==null);
        if(actualNonUnique!=nonUnique||!actual.equals(columns)||!full)
            throw incompatible("index "+name,columns,rows);}
    private static void add(Map<String,Column> map,String name,String data,String type,boolean nullable,String collation,String def){map.put(name,new Column(data,type,nullable,collation,def));}
    private static String value(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase(Locale.ROOT));return v==null?"":String.valueOf(v);}
    private static String nullable(Map<String,Object> row,String key){Object v=row.get(key);if(v==null)v=row.get(key.toUpperCase(Locale.ROOT));return v==null?null:String.valueOf(v);}
    private static IllegalStateException incompatible(String item,Object expected,Object actual){return new IllegalStateException("Incompatible chat_output "+item+"; expected="+expected+", actual="+actual);}
    private record Column(String dataType,String columnType,boolean nullable,String collation,String defaultValue){}

    private boolean h2(){DataSource ds=jdbc.getDataSource();if(ds==null)return false;
        try(Connection c=ds.getConnection()){String n=c.getMetaData().getDatabaseProductName();
            return n!=null&&n.toLowerCase(Locale.ROOT).contains("h2");}
        catch(SQLException e){throw new IllegalStateException("Unable to identify chat output database",e);}}
}
