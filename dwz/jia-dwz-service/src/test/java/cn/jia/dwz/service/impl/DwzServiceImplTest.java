package cn.jia.dwz.service.impl;

import cn.jia.dwz.dao.DwzRecordDao;
import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.test.BaseMockTest;
import com.github.pagehelper.Page;
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

import static org.mockito.Mockito.*;

class DwzServiceImplTest extends BaseMockTest {
    @Mock
    DwzRecordDao dwzRecordDao;
    @InjectMocks
    DwzServiceImpl dwzServiceImpl;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(dwzServiceImpl, "baseDao", dwzRecordDao);
    }

    @AfterEach
    void setDown() {
        Mockito.verifyNoMoreInteractions(dwzRecordDao);
    }

    @Test
    void testView() {
        DwzRecordEntity entity = new DwzRecordEntity();
        entity.setPv(0);
        when(dwzRecordDao.selectByUri(anyString())).thenReturn(entity);
        when(dwzRecordDao.updateById(any())).thenReturn(1);

        DwzRecordEntity result = dwzServiceImpl.view("uri");
        Assertions.assertNotNull(result);
        Assertions.assertEquals(0, result.getPv());

        Mockito.verify(dwzRecordDao, times(1)).selectByUri(anyString());
        Mockito.verify(dwzRecordDao, times(1)).updateById(any());
    }

    @Test
    void testGen() {
        when(dwzRecordDao.insert(any())).thenReturn(1);
        when(dwzRecordDao.selectByEntity(any())).thenReturn(new ArrayList<>());

        String result = dwzServiceImpl.gen("jiacn", "orig", 1L);
        Assertions.assertNotNull(result);

        Mockito.verify(dwzRecordDao, times(1)).selectByEntity(any());
        Mockito.verify(dwzRecordDao, times(1)).insert(any());
    }

    @Test
    void testFind() {
        DwzRecordEntity entity = new DwzRecordEntity();
        when(dwzRecordDao.selectById(any())).thenReturn(entity);

        DwzRecordEntity result = dwzServiceImpl.get(1L);
        Assertions.assertEquals(entity, result);

        Mockito.verify(dwzRecordDao,times(1)).selectById(any());
    }

    @Test
    void testUpdate() {
        when(dwzRecordDao.updateById(any())).thenReturn(1);

        DwzRecordEntity entity = new DwzRecordEntity();
        DwzRecordEntity result = dwzServiceImpl.update(entity);
        Assertions.assertEquals(entity, result);

        Mockito.verify(dwzRecordDao, times(1)).updateById(any());
    }

    @Test
    void testDelete() {
        when(dwzRecordDao.deleteById(any())).thenReturn(0);

        dwzServiceImpl.delete(1L);

        Mockito.verify(dwzRecordDao, times(1)).deleteById(any());
    }

    @Test
    void testList() {
        DwzRecordEntity entity = new DwzRecordEntity();
        List<DwzRecordEntity> list = new Page<>(1, 30);
        list.add(entity);
        when(dwzRecordDao.selectByEntity(any())).thenReturn(list);

        PageInfo<DwzRecordEntity> result = dwzServiceImpl.findPage(entity, 30, 1);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(result.getPageSize(), 30);
        Assertions.assertEquals(result.getPageNum(), 1);
        Assertions.assertEquals(result.getList().getFirst(), entity);

        Mockito.verify(dwzRecordDao, times(1)).selectByEntity(any());
    }
}
