package cn.jia.point.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.point.entity.PointGiftUsageEntity;

import java.util.List;

/**
 * 礼品使用情况 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
public interface PointGiftUsageDao extends IBaseDao<PointGiftUsageEntity> {
    List<PointGiftUsageEntity> listByUser(String jiacn);

    List<PointGiftUsageEntity> listByGift(Long giftId);
}
