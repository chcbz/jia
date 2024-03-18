package cn.jia.sms.dao.impl;

import cn.jia.sms.dao.SmsSendDao;
import cn.jia.sms.entity.SmsSendVO;
import cn.jia.test.BaseDbUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmsSendDaoImplTest extends BaseDbUnitTest {
    @Inject
    private SmsSendDao smsSendDao;
    @Test
    void groupByMobile() {
        SmsSendVO smsSendVO = new SmsSendVO();
        smsSendVO.setTimeStart(1574840533L);
        smsSendVO.setTimeEnd(1575453643L);
        List<Map<String, Object>> mapList = smsSendDao.groupByMobile(smsSendVO);
        assertEquals(3, mapList.size());
        assertEquals("13538788751", mapList.get(0).get("mobile"));
        assertEquals(3L, mapList.get(0).get("num"));
        assertEquals("13827228333", mapList.get(1).get("mobile"));
        assertEquals(2L, mapList.get(1).get("num"));
        assertEquals("18666460519", mapList.get(2).get("mobile"));
        assertEquals(1L, mapList.get(2).get("num"));
    }
}