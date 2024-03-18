package cn.jia.point.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.point.dao.PointGiftDao;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.mapper.PointGiftMapper;
import jakarta.inject.Named;

/**
 * 短链接 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
@Named
public class PointGiftDaoImpl extends BaseDaoImpl<PointGiftMapper, PointGiftEntity> implements PointGiftDao {
}
