package cn.jia.base.dao.impl;

import cn.jia.base.dao.LogDao;
import cn.jia.base.entity.LogEntity;
import cn.jia.base.mapper.LogMapper;
import cn.jia.common.dao.BaseDaoImpl;
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
public class LogDaoImpl extends BaseDaoImpl<LogMapper, LogEntity> implements LogDao {

}
