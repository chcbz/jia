package cn.jia.point.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.point.entity.PointSignEntity;

/**
 * 签到 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
public interface PointSignDao extends IBaseDao<PointSignEntity> {
    /**
     * 查询最后一次签到时间
     *
     * @param jiacn 用户编号
     * @return 签到信息
     */
    PointSignEntity selectLatest(String jiacn);
}
