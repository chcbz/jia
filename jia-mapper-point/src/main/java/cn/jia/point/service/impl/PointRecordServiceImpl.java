package cn.jia.point.service.impl;

import cn.jia.common.service.impl.BaseServiceImpl;
import cn.jia.point.entity.PointRecord;
import cn.jia.point.mapper.PointRecordMapper;
import cn.jia.point.service.IPointRecordService;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-02-14
 */
@Named
public class PointRecordServiceImpl extends BaseServiceImpl<PointRecordMapper, PointRecord> implements IPointRecordService {

}
