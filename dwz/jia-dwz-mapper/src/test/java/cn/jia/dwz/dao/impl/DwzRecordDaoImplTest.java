package cn.jia.dwz.dao.impl;

import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.test.BaseDbUnitTest;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class DwzRecordDaoImplTest extends BaseDbUnitTest {
    @Inject
    DwzRecordDaoImpl dwzRecordDao;
    @Test
    void selectByUri() {
        DwzRecordEntity dwzRecord = dwzRecordDao.selectByUri("e8eV8UFQ");
        Assertions.assertNull(dwzRecord);

        dwzRecord = new DwzRecordEntity();
        dwzRecord.setUri("e8eV8UFQ");
        dwzRecord.setOrig("https://test.com");
        dwzRecordDao.insert(dwzRecord);

        dwzRecord = dwzRecordDao.selectByUri("e8eV8UFQ");
        Assertions.assertNotNull(dwzRecord);
        Assertions.assertEquals(dwzRecord.getUri(), "e8eV8UFQ");
    }

    @Test
    void selectPage() {
        PageHelper.startPage(1, 20);
        DwzRecordEntity entity = new DwzRecordEntity();
        entity.setUri("FdeHq4BU");
        List<DwzRecordEntity> result = dwzRecordDao.selectByEntity(entity);
        PageInfo<DwzRecordEntity> pageInfo = PageInfo.of(result);

        Assertions.assertEquals(pageInfo.getPageNum(), 1);
        Assertions.assertEquals(pageInfo.getPageSize(), 20);
        Assertions.assertEquals(pageInfo.getSize(), 1);
    }
}