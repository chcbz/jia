package cn.jia.base.service.impl;

import cn.jia.base.dao.LogDao;
import cn.jia.base.entity.LogEntity;
import cn.jia.base.service.LogService;
import cn.jia.core.service.BaseServiceImpl;
import jakarta.inject.Named;

@Named
public class LogServiceImpl extends BaseServiceImpl<LogDao, LogEntity> implements LogService {
}
