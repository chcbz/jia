package cn.jia.dwz.service;

import cn.jia.common.service.IBaseService;
import cn.jia.dwz.entity.DwzRecordEntity;

public interface DwzService extends IBaseService<DwzRecordEntity> {

    DwzRecordEntity view(String uri);

    String gen(String jiacn, String orig, Long expireTime);
}
