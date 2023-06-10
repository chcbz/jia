package cn.jia.core.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.core.entity.Log;
import cn.jia.core.mapper.LogMapper;
import cn.jia.core.service.ILogService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-02
 */
@Named
public class LogServiceImpl extends BaseServiceImpl<LogMapper, Log> implements ILogService {

}
