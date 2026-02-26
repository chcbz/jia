package cn.jia.dwz.mapper;

import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.test.BaseDbUnitTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DwzRecordMapper测试类
 */
class DwzRecordMapperTest extends BaseDbUnitTest {

    @Autowired
    private DwzRecordMapper dwzRecordMapper;

    @Test
    void testSelectList() {
        // 测试selectList方法是否可用
        List<DwzRecordEntity> result = dwzRecordMapper.selectList(null);
        assertNotNull(result);
        
        // 验证BaseMapper的其他方法
        assertTrue(dwzRecordMapper != null);
        System.out.println("DwzRecordMapper注入成功，selectList方法可用");
    }

    @Test
    void testInsert() {
        DwzRecordEntity entity = new DwzRecordEntity();
        entity.setJiacn("test_user");
        entity.setOrig("https://test.com");
        entity.setUri("test123");
        entity.setStatus(1);
        entity.setPv(0);
        
        int result = dwzRecordMapper.insert(entity);
        assertTrue(result > 0);
        assertNotNull(entity.getId());
        System.out.println("插入测试数据成功，ID: " + entity.getId());
    }
}