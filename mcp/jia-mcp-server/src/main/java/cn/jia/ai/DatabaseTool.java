package cn.jia.ai;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DatabaseTool {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(
        name = "execute_sql",
        description = "执行SQL语句并返回结果"
    )
    public List<Map<String, Object>> executeSql(@ToolParam(description = "需要执行的SQL语句") String sql) {
        if (sql.trim().toLowerCase().startsWith("select")) {
            return jdbcTemplate.queryForList(sql);
        } else {
            jdbcTemplate.execute(sql);
            return List.of(Map.of("status", "success", "rowsAffected", 
                jdbcTemplate.queryForObject("SELECT ROW_COUNT()", Integer.class)));
        }
    }
}
