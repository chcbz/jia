package cn.jia.point.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.point.entity.PointReferralEntity;

/**
 * 推荐 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
public interface PointReferralDao extends IBaseDao<PointReferralEntity> {
    /**
     * 检查是否已经被推荐过
     *
     * @param referral 推荐人
     * @return 是否被推荐过
     */
    boolean checkHasReferral(String referral);
}
