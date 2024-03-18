package cn.jia.isp.dao.impl;

import cn.jia.isp.dao.IspFileDao;
import cn.jia.isp.entity.IspFileEntity;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class IspFileDaoImplTest extends BaseDbUnitTest {
    @Inject
    private IspFileDao ispFileDao;

    @Test
    void selectByUri() {
        IspFileEntity ispFileEntity = ispFileDao.selectByUri("avatar/20191204160906_oH2zD1IyhO9QU415JiBVCrrBcnlg.jpg");
        Assertions.assertNotNull(ispFileEntity);
        Assertions.assertEquals(ispFileEntity.getId(), 1);
    }
}