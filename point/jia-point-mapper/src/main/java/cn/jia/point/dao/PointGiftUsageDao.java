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

    PointGiftUsageEntity selectScoped(String tenantId, Long id, boolean lock);
    int updateScoped(String tenantId, PointGiftUsageEntity entity);
    int deleteScoped(String tenantId, Long id);
    List<PointGiftUsageEntity> listByUser(String tenantId, String jiacn);

    List<PointGiftUsageEntity> listByGift(String tenantId, Long giftId);
}
