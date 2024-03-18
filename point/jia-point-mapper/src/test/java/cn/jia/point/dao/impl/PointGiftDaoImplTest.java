package cn.jia.point.dao.impl;

import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointGiftDaoImplTest extends BaseDbUnitTest {
    @Inject
    private PointGiftDao pointGiftDao;
    @Test
    void selectByEntity() {
        PointGiftVO pointGiftVO = new PointGiftVO();
        pointGiftVO.setNameLike("耳机");
        List<PointGiftEntity> pointGiftEntities = pointGiftDao.selectByEntity(pointGiftVO);
        assertEquals(1, pointGiftEntities.size());
        assertEquals("精美耳机", pointGiftEntities.get(0).getName());
    }
}