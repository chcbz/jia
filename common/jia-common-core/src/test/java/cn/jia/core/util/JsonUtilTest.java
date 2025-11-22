package cn.jia.core.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;

class JsonUtilTest {
    @Test
    void testFromJson() {
        // 测试用例1：正常JSON字符串转对象
        String json = "{\"name\":\"张三\",\"age\":25,\"email\":\"zhangsan@example.com\"}";
        TestEntity entity = JsonUtil.fromJson(json, TestEntity.class);
        
        assertNotNull(entity);
        assertEquals("张三", entity.getName());
        assertEquals(25, entity.getAge());
        assertEquals("zhangsan@example.com", entity.getEmail());
        // 注意：由于JSON中未提供duration，默认为null
        assertNull(entity.getDuration());
    }
    
    @Test
    void testFromJsonWithDuration() {
        // 测试用例2：包含Duration的JSON字符串转对象
        String json = "{\"name\":\"张三\",\"age\":25,\"email\":\"zhangsan@example.com\",\"duration\":\"PT2H\"}";
        TestEntity entity = JsonUtil.fromJson(json, TestEntity.class);
        
        assertNotNull(entity);
        assertEquals("张三", entity.getName());
        assertEquals(25, entity.getAge());
        assertEquals("zhangsan@example.com", entity.getEmail());
        assertEquals(Duration.ofHours(2), entity.getDuration());
    }
    
    @Test
    void testToJsonWithDuration() {
        // 测试用例3：对象转JSON字符串
        TestEntity entity = new TestEntity("张三", 25, "zhangsan@example.com", Duration.ofHours(2));
        String json = JsonUtil.toJson(entity);
        
        assertNotNull(json);
        assertTrue(json.contains("\"name\":\"张三\""));
        assertTrue(json.contains("\"age\":25"));
        assertTrue(json.contains("\"email\":\"zhangsan@example.com\""));
        assertTrue(json.contains("\"duration\":\"PT2H\""));
    }
    
    @Test
    void testFromJsonWithNull() {
        // 测试用例4：空JSON字符串
        TestEntity entity = JsonUtil.fromJson(null, TestEntity.class);
        assertNull(entity);
        
        // 测试用例5：空字符串
        entity = JsonUtil.fromJson("", TestEntity.class);
        assertNull(entity);
    }
    
    @Test
    void testFromJsonWithInvalidJson() {
        // 测试用例6：无效JSON字符串
        String invalidJson = "{invalid json}";
        TestEntity entity = JsonUtil.fromJson(invalidJson, TestEntity.class);
        assertNull(entity);
    }
}