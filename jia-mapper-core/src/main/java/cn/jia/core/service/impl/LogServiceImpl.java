package cn.jia.core.service.impl;

import cn.jia.core.entity.Log;
import cn.jia.core.mapper.LogMapper;
import cn.jia.core.service.ILogService;
import cn.jia.common.service.impl.BaseServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-02
 */
@Service
public class LogServiceImpl extends BaseServiceImpl<LogMapper, Log> implements ILogService {

}
