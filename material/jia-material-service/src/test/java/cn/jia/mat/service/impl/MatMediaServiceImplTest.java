package cn.jia.mat.service.impl;

import cn.jia.mat.dao.MatMediaDao;
import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatMediaReqVO;
import com.github.pagehelper.PageInfo;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MatMediaServiceImplTest {
    @Mock
    MatMediaDao matMediaDao;
    @InjectMocks
    MatMediaServiceImpl mediaService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mediaService, "baseDao", matMediaDao);
    }

    @Test
    void testCreate() {
        when(matMediaDao.insert(any())).thenReturn(1);

        MatMediaEntity result = mediaService.create(new MatMediaEntity());
        Assertions.assertNotNull(result);
    }

    @Test
    void testFind() {
        when(matMediaDao.selectById(any())).thenReturn(new MatMediaEntity());

        MatMediaEntity result = mediaService.get(1L);
        Assertions.assertEquals(new MatMediaEntity(), result);
    }

    @Test
    void testUpdate() {
        when(matMediaDao.updateById(any())).thenReturn(1);

        MatMediaEntity result = mediaService.update(new MatMediaEntity());
        Assertions.assertNotNull(result);
    }

    @Test
    void testDelete() {
        when(matMediaDao.deleteById(any())).thenReturn(0);

        mediaService.delete(1L);
    }

    @Test
    void testList() {
        when(matMediaDao.selectByEntity(any())).thenReturn(List.of(new MatMediaEntity()));

        PageInfo<MatMediaEntity> result = mediaService.findPage(new MatMediaReqVO(), 0, 0);
        Assertions.assertNotNull(result);
        Assertions.assertEquals(result.getSize(), 1);
    }
}
