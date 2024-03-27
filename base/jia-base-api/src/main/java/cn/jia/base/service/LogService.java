package cn.jia.base.service;

import cn.jia.base.entity.LogEntity;
import cn.jia.common.service.IBaseService;
import cn.jia.core.common.EsRequestWrapper;

public interface LogService extends IBaseService<LogEntity> {
    LogEntity addLog(EsRequestWrapper esRequestWrapper);
}
