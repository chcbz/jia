package cn.jia.point.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.point.entity.PointGiftEntity;

/**
 * 礼品 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
public interface PointGiftDao extends IBaseDao<PointGiftEntity> {

    PointGiftEntity selectScoped(String tenantId, Long id, boolean lock);
    int updateScoped(String tenantId, PointGiftEntity entity);
    int deleteScoped(String tenantId, Long id);
    java.util.List<PointGiftEntity> listScoped(String tenantId, PointGiftEntity example);
}
