package cn.jia.dwz.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.dwz.entity.DwzRecordEntity;

/**
 * 短链接 Dao
 *
 * @author chc
 * @since 2023-08-05
 */
public interface DwzRecordDao extends IBaseDao<DwzRecordEntity> {
    DwzRecordEntity selectByUri(String uri);
}
