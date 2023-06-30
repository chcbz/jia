package cn.jia.core.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.core.entity.LogEntity;
import cn.jia.core.mapper.LogMapper;
import cn.jia.core.service.ILogService;
import jakarta.inject.Named;

/**
 * <p>
 * 日志 服务实现类
 * </p>
 *
 * @author chc
 * @since 2023-06-18
 */
@Named
public class LogServiceImpl extends BaseServiceImpl<LogMapper, LogEntity> implements ILogService {

}
