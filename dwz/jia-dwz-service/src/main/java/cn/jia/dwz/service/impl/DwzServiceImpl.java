package cn.jia.dwz.service.impl;

import cn.jia.core.common.EsHandler;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.core.util.DataUtil;
import cn.jia.core.util.DateUtil;
import cn.jia.dwz.dao.DwzRecordDao;
import cn.jia.dwz.entity.DwzRecordEntity;
import cn.jia.dwz.service.DwzService;
import jakarta.inject.Named;

import java.util.Calendar;
import java.util.List;

@Named
public class DwzServiceImpl extends BaseServiceImpl<DwzRecordDao, DwzRecordEntity> implements DwzService {
    @Override
    public DwzRecordEntity view(String uri) {
        DwzRecordEntity record = baseDao.selectByUri(uri);
        EsHandler.assertNotNull(record);
        long now = DateUtil.nowTime();
        DwzRecordEntity upRecord = new DwzRecordEntity();
        upRecord.setId(record.getId());
        upRecord.setUpdateTime(now);
        upRecord.setPv(record.getPv() + 1);
        baseDao.updateById(upRecord);
        return record;
    }

    @Override
    public String gen(String jiacn, String orig, Long expireTime) {
        DwzRecordEntity dwzRecord = new DwzRecordEntity();
        dwzRecord.setJiacn(jiacn);
        dwzRecord.setOrig(orig);
        List<DwzRecordEntity> list = baseDao.selectByEntity(dwzRecord);
        if (!list.isEmpty()) {
            return list.getFirst().getUri();
        } else {
            dwzRecord.setUri(DataUtil.getRandom(false, 8));
            Long now = DateUtil.nowTime();
            dwzRecord.setCreateTime(now);
            dwzRecord.setUpdateTime(now);
            if (expireTime == null) {
                Calendar expireTimeCalendar = Calendar.getInstance();
                expireTimeCalendar.setTimeInMillis(now);
                expireTimeCalendar.add(Calendar.YEAR, 1);
                expireTime = expireTimeCalendar.getTimeInMillis();
            }
            dwzRecord.setExpireTime(expireTime);
            baseDao.insert(dwzRecord);
            return dwzRecord.getUri();
        }
    }
}
