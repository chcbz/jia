package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointRecordDao;
import cn.jia.point.entity.PointRecordEntity;
import cn.jia.point.mapper.PointRecordMapper;
import jakarta.inject.Named;

@Named
public class PointRecordDaoImpl extends BaseDaoImpl<PointRecordMapper, PointRecordEntity> implements PointRecordDao {
}
