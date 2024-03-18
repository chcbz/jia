package cn.jia.core.service;

import cn.jia.core.dao.IBaseDao;
import cn.jia.core.entity.BaseEntity;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class BaseServiceImplTest {
    @Mock
    IBaseDao<BaseEntity> baseDao;
    @InjectMocks
    BaseServiceImpl<IBaseDao<BaseEntity>, BaseEntity> baseServiceImpl =
            Mockito.mock(BaseServiceImpl.class, Mockito.CALLS_REAL_METHODS);

    @BeforeEach
    void setUp() {
        baseDao = Mockito.mock(IBaseDao.class);
        ReflectionTestUtils.setField(baseServiceImpl, "baseDao", baseDao);
    }

    @AfterEach
    void setDown() {
        Mockito.verifyNoMoreInteractions(baseDao);
    }

    @Test
    void testCreate() {
        when(baseDao.insert(any())).thenReturn(1);
        BaseEntity baseEntity = new BaseEntity();
        BaseEntity result = baseServiceImpl.create(baseEntity);
        assertEquals(baseEntity, result);

        when(baseDao.insert(any())).thenReturn(0);
        result = baseServiceImpl.create(baseEntity);
        Assertions.assertNull(result);

        verify(baseDao, times(2)).insert(any());
    }

    @Test
    void testGet() {
        BaseEntity baseEntity = new BaseEntity();
        when(baseDao.selectById(any())).thenReturn(baseEntity);
        BaseEntity result = baseServiceImpl.get(1L);
        assertEquals(baseEntity, result);
        verify(baseDao, times(1)).selectById(any());
    }

    @Test
    void testUpdate() {
        BaseEntity baseEntity = new BaseEntity();
        when(baseDao.updateById(any())).thenReturn(1);
        BaseEntity result = baseServiceImpl.update(baseEntity);
        assertEquals(baseEntity, result);

        when(baseDao.updateById(any())).thenReturn(0);
        result = baseServiceImpl.update(baseEntity);
        Assertions.assertNull(result);

        verify(baseDao, times(2)).updateById(any());
    }

    @Test
    void testDelete() {
        when(baseDao.deleteById(any())).thenReturn(1);
        boolean result = baseServiceImpl.delete(1L);
        Assertions.assertTrue(result);

        when(baseDao.deleteById(any())).thenReturn(0);
        result = baseServiceImpl.delete(1L);
        Assertions.assertFalse(result);

        verify(baseDao, times(2)).deleteById(any());
    }

    @Test
    void testFindOne() {
        List<BaseEntity> list = new ArrayList<>();
        BaseEntity baseEntity = new BaseEntity();
        list.add(baseEntity);
        when(baseDao.selectByEntity(any())).thenReturn(list);
        BaseEntity result = baseServiceImpl.findOne(new BaseEntity());
        assertEquals(baseEntity, result);

        verify(baseDao, times(1)).selectByEntity(any());
    }

    @Test
    void testFindList() {
        List<BaseEntity> list = new ArrayList<>();
        BaseEntity baseEntity = new BaseEntity();
        list.add(baseEntity);
        when(baseDao.selectByEntity(any())).thenReturn(list);
        List<BaseEntity> result = baseServiceImpl.findList(new BaseEntity());
        assertEquals(1, result.size());
        assertEquals(baseEntity, result.get(0));

        verify(baseDao, times(1)).selectByEntity(any());
    }

    @Test
    void testFindPage() {
        List<BaseEntity> list = new ArrayList<>();
        BaseEntity baseEntity = new BaseEntity();
        list.add(baseEntity);
        when(baseDao.selectByEntity(any())).thenReturn(list);
        PageInfo<BaseEntity> result = baseServiceImpl.findPage(new BaseEntity(), 10, 1);
        assertEquals(1, result.getSize());
        assertEquals(baseEntity, result.getList().get(0));

        verify(baseDao, times(1)).selectByEntity(any());
    }

    @Test
    void testRetBool() {
        Boolean result = baseServiceImpl.retBool(null);
        assertEquals(Boolean.FALSE, result);

        result = baseServiceImpl.retBool(0);
        assertEquals(Boolean.FALSE, result);

        result = baseServiceImpl.retBool(1);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    void testRetEntity() {
        BaseEntity baseEntity = new BaseEntity();
        BaseEntity result = baseServiceImpl.retEntity(entity -> 0, baseEntity);
        assertNull(result);

        result = baseServiceImpl.retEntity(entity -> 1, baseEntity);
        assertEquals(baseEntity, result);
    }
}
