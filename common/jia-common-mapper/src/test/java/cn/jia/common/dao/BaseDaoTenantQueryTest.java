package cn.jia.common.dao;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaseDaoTenantQueryTest {
    interface RowMapper extends BaseMapper<BaseEntity> { }
    static final class Dao extends BaseDaoImpl<RowMapper, BaseEntity> {
        Dao(RowMapper mapper) { baseMapper = mapper; }
        @Override protected Class<RowMapper> currentMapperClass() { return RowMapper.class; }
        @Override protected Class<BaseEntity> currentModelClass() { return BaseEntity.class; }
    }

    @Test void everyExplicitBusinessPartitionIsExactIncludingZero() {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            RowMapper mapper = mock(RowMapper.class);
            Dao dao = new Dao(mapper);
            BaseEntity entity = new BaseEntity().setTenantId(tenant);
            dao.selectByEntity(entity);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<QueryWrapper<BaseEntity>> query = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(mapper).selectList(query.capture());
            String sql = query.getValue().getSqlSegment();
            assertTrue(sql.contains("tenant_id ="), sql);
            assertFalse(sql.contains(" OR "), sql);
            assertEquals(List.of(tenant), List.copyOf(query.getValue().getParamNameValuePairs().values()));
            assertEquals(tenant, entity.getTenantId(), "do not mutate caller scope");
        }
    }

    @Test void malformedExplicitScopeFailsBeforeMapperAndRestoresCaller() {
        for (String tenant : List.of("", " ", "1 ", " 0", "1\n")) {
            RowMapper mapper = mock(RowMapper.class);
            BaseEntity entity = new BaseEntity().setTenantId(tenant);
            assertThrows(IllegalArgumentException.class, () -> new Dao(mapper).selectByEntity(entity));
            assertEquals(tenant, entity.getTenantId());
            verifyNoInteractions(mapper);
        }
    }
}
