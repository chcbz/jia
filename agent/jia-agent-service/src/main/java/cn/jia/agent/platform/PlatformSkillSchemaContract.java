package cn.jia.agent.platform;

import java.util.*;

/** Exact persistence proof; identity, bytes, nullability and enforced constraints all matter. */
final class PlatformSkillSchemaContract {
    private PlatformSkillSchemaContract() { }
    record Column(String type,boolean nullable) { }
    record Index(String columns,boolean unique) { }
    static Map<String,Column> columns(String table) {
        var m=new LinkedHashMap<String,Column>();
        add(m,"tenant_id","varchar(1)");add(m,"client_id","varchar(50)");add(m,"owner_jiacn","varchar(50)");
        if(table.endsWith("_scope"))return m;
        add(m,"installation_id","varchar(100)");add(m,"actor_id","varchar(100)");add(m,"request_key","varchar(128)");
        add(m,"request_sha256","char(64)");add(m,"agent_id","varchar(100)");add(m,"binding_id","bigint");
        add(m,"runtime_instance_id","varchar(100)");add(m,"registration_hash","binary(32)");
        add(m,"skill_key","varchar(64)");add(m,"skill_version","varchar(64)");add(m,"package_sha256","char(64)");
        add(m,"challenge_id","varchar(100)");add(m,"command_id","varchar(100)");add(m,"origin","varchar(32)");
        add(m,"state","varchar(32)");add(m,"revision","bigint");add(m,"created_at","bigint");
        m.put("result_sha256",new Column("char(64)",true));m.put("error_code",new Column("varchar(100)",true));m.put("reclaim_batch_json",new Column("longtext",true));return m;
    }
    private static void add(Map<String,Column> m,String n,String type) { m.put(n,new Column(type,false)); }
    static Map<String,Index> indexes(String table) {
        if(table.endsWith("_scope"))return Map.of("PRIMARY",new Index("tenant_id,client_id,owner_jiacn",true));
        return Map.of("PRIMARY",new Index("installation_id",true),
                "uk_platform_install_request",new Index("tenant_id,client_id,owner_jiacn,actor_id,request_key",true),
                "uk_platform_install_challenge",new Index("challenge_id",true),
                "uk_platform_install_command",new Index("tenant_id,client_id,owner_jiacn,command_id",true),
                "ix_platform_install_reconcile",new Index("state,created_at,installation_id",false),
                "ix_platform_install_resolve",new Index("tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,state,created_at,installation_id",false),
                "ix_platform_install_resolve_history",new Index("tenant_id,client_id,owner_jiacn,origin,agent_id,skill_key,skill_version,package_sha256,binding_id,runtime_instance_id,registration_hash,created_at,installation_id",false));
    }
    static Map<String,String> checks(String table) {
        if(table.endsWith("_scope"))return Map.of();
        return Map.of("ck_platform_install_origin","origin='PLATFORM_PROVISIONED'",
                "ck_platform_install_state","stateIN('REQUESTED','SUCCEEDED','FAILED','RECLAIMABLE')",
                "ck_platform_install_revision","revision>0");
    }
    static void validate(String table,Map<String,Object> properties,List<Map<String,Object>> actualColumns,
            List<Map<String,Object>> actualIndexes,List<Map<String,Object>> actualChecks) {
        validate(table,properties,actualColumns,actualIndexes,actualChecks,columns(table),checks(table));
    }
    static void validatePreReclamation(String table,Map<String,Object> properties,List<Map<String,Object>> actualColumns,
            List<Map<String,Object>> actualIndexes,List<Map<String,Object>> actualChecks) {
        var predecessor=new LinkedHashMap<>(checks(table));
        if(!table.endsWith("_scope")) predecessor.put("ck_platform_install_state","stateIN('REQUESTED','SUCCEEDED','FAILED')");
        var oldColumns=new LinkedHashMap<>(columns(table));
        oldColumns.remove("reclaim_batch_json");
        validate(table,properties,actualColumns,actualIndexes,actualChecks,oldColumns,predecessor);
    }
    private static void validate(String table,Map<String,Object> properties,List<Map<String,Object>> actualColumns,
            List<Map<String,Object>> actualIndexes,List<Map<String,Object>> actualChecks,Map<String,Column> expectedColumns,Map<String,String> expectedCheckSource) {
        require("InnoDB".equalsIgnoreCase(String.valueOf(properties.get("ENGINE"))) && "utf8mb4_0900_bin".equals(properties.get("TABLE_COLLATION")),table);
        var expected=expectedColumns;var found=new HashSet<String>();
        for(var c:actualColumns) {
            String name=String.valueOf(c.get("COLUMN_NAME"));Column column=expected.get(name);
            require(column!=null && found.add(name),table);
            require(column.type().equals(String.valueOf(c.get("COLUMN_TYPE")).toLowerCase(Locale.ROOT)),table+"."+name);
            require((column.nullable()?"YES":"NO").equals(c.get("IS_NULLABLE")) && c.get("COLUMN_DEFAULT")==null
                    && "".equals(c.get("EXTRA")) && (c.get("GENERATION_EXPRESSION")==null || "".equals(c.get("GENERATION_EXPRESSION"))),table+"."+name);
            if(column.type().startsWith("varchar") || column.type().startsWith("char") || column.type().equals("longtext")) require("utf8mb4_0900_bin".equals(c.get("COLLATION_NAME")),table+"."+name);
            else require(c.get("COLLATION_NAME")==null,table+"."+name);
        }
        require(found.equals(expected.keySet()),table);
        var indexMap=new HashMap<String,Index>();
        for(var i:actualIndexes) {
            require(i.get("NON_UNIQUE") instanceof Number && i.get("PREFIX_COUNT") instanceof Number,table);
            int nonUnique=((Number)i.get("NON_UNIQUE")).intValue();
            require((nonUnique==0 || nonUnique==1) && ((Number)i.get("PREFIX_COUNT")).intValue()==0
                    && "BTREE".equals(i.get("INDEX_TYPE")),table);
            var index=new Index(String.valueOf(i.get("cols")),nonUnique==0);
            require(indexMap.put(String.valueOf(i.get("INDEX_NAME")),index)==null,table);
        }
        require(indexMap.equals(indexes(table)),table);
        var checkMap=new HashMap<String,String>();
        for(var c:actualChecks) {
            require("YES".equals(c.get("ENFORCED")),table);
            require(checkMap.put(String.valueOf(c.get("CONSTRAINT_NAME")),normalize(String.valueOf(c.get("CHECK_CLAUSE"))))==null,table);
        }
        var expectedChecks=new HashMap<String,String>();expectedCheckSource.forEach((n,v)->expectedChecks.put(n,normalize(v)));
        require(checkMap.equals(expectedChecks),table);
    }
    static String normalize(String sql) {
        if (sql == null) return null;
        StringBuilder normalized = new StringBuilder(sql.length());
        boolean quoted = false;
        boolean metadataEscapedQuotes = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (quoted) {
                if (metadataEscapedQuotes && current == '\\' && index + 1 < sql.length()
                        && sql.charAt(index + 1) == '\'') {
                    normalized.append(sql.charAt(++index));
                    quoted = false;
                    metadataEscapedQuotes = false;
                } else {
                    normalized.append(current);
                    if (current == '\\' && index + 1 < sql.length()) {
                        normalized.append(sql.charAt(++index));
                    } else if (current == '\'' && index + 1 < sql.length()
                            && sql.charAt(index + 1) == '\'') {
                        normalized.append(sql.charAt(++index));
                    } else if (current == '\'') {
                        quoted = false;
                    }
                }
                continue;
            }
            int introducerLength = charsetIntroducerLengthAt(sql, index);
            if (current == '\'') {
                quoted = true;
                normalized.append(current);
            } else if (current == '`' || Character.isWhitespace(current)) {
                continue;
            } else if (introducerLength < 0) {
                quoted = true;
                metadataEscapedQuotes = true;
                normalized.append('\'');
                index += -introducerLength;
            } else if (introducerLength > 0) {
                index += introducerLength - 1;
            } else {
                normalized.append(Character.toUpperCase(current));
            }
        }
        String result = normalized.toString();
        while (result.startsWith("(") && result.endsWith(")") && balancedOuterParentheses(result)) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }
    private static int charsetIntroducerLengthAt(String value, int index) {
        for (String introducer : List.of("_utf8mb4", "_utf8mb3", "_ascii")) {
            if (!value.regionMatches(true, index, introducer, 0, introducer.length())) continue;
            int after = index + introducer.length();
            if (after < value.length() && value.charAt(after) == '\'') return introducer.length();
            if (after + 1 < value.length() && value.charAt(after) == '\\'
                    && value.charAt(after + 1) == '\'') return -(introducer.length() + 1);
        }
        return 0;
    }
    private static boolean balancedOuterParentheses(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (current == '\\' && index + 1 < value.length()) index++;
                else if (current == '\'' && index + 1 < value.length() && value.charAt(index + 1) == '\'') index++;
                else if (current == '\'') quoted = false;
                continue;
            }
            if (current == '\'') quoted = true;
            else if (current == '(') depth++;
            else if (current == ')') depth--;
            if (depth == 0 && index < value.length() - 1) return false;
            if (depth < 0) return false;
        }
        return depth == 0 && !quoted;
    }
    private static void require(boolean condition,String location) { if(!condition)throw new IllegalStateException("Platform skill schema drift: "+location); }
}



