package cn.jia.mat.dao.impl;

import cn.jia.mat.dao.MatMediaDao;
import cn.jia.mat.entity.MatMediaEntity;
import cn.jia.mat.entity.MatMediaReqVO;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatMediaDaoImplTest extends BaseDbUnitTest {
    @Inject
    private MatMediaDao matMediaDao;
    @Test
    void selectByEntity() {
        MatMediaReqVO matMediaReqVO = new MatMediaReqVO();
        matMediaReqVO.setTitleLike("二维码");
        List<MatMediaEntity> matMediaEntities = matMediaDao.selectByEntity(matMediaReqVO);
        assertEquals(1, matMediaEntities.size());
    }
}