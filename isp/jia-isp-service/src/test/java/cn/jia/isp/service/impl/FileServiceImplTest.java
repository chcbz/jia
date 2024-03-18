package cn.jia.isp.service.impl;

import cn.jia.isp.dao.IspFileDao;
import cn.jia.isp.entity.IspFileEntity;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.mockito.Mockito.*;

class FileServiceImplTest {
    @Mock
    IspFileDao ispFileDao;
    @InjectMocks
    FileServiceImpl fileServiceImpl;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreate() {
        when(ispFileDao.insert(any())).thenReturn(1);

        IspFileEntity result = fileServiceImpl.create(new IspFileEntity());
        Assertions.assertNotNull(result);
    }

    @Test
    void testFind() {
        when(ispFileDao.selectById(any())).thenReturn(new IspFileEntity());

        IspFileEntity result = fileServiceImpl.find(1L);
        Assertions.assertEquals(new IspFileEntity(), result);
    }

    @Test
    void testUpdate() {
        when(ispFileDao.updateById(any())).thenReturn(1);

        IspFileEntity result = fileServiceImpl.update(new IspFileEntity());
        Assertions.assertNotNull(result);
    }

    @Test
    void testDelete() {
        when(ispFileDao.deleteById(any())).thenReturn(0);

        fileServiceImpl.delete(1L);
    }

    @Test
    void testFindByUri() {
        when(ispFileDao.selectByUri(anyString())).thenReturn(new IspFileEntity());

        IspFileEntity result = fileServiceImpl.findByUri("uri");
        Assertions.assertEquals(new IspFileEntity(), result);
    }

    @Test
    void testList() {
        when(ispFileDao.selectByEntity(any())).thenReturn(List.of(new IspFileEntity()));

        PageInfo<IspFileEntity> result = fileServiceImpl.list(new IspFileEntity(), 0, 0);
        Assertions.assertEquals(result.getSize(), 1);
    }
}