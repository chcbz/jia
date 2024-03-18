package cn.jia.mat.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.mat.entity.MatPhraseEntity;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatPhraseDao extends IBaseDao<MatPhraseEntity> {

    MatPhraseEntity selectRandom(MatPhraseEntity record);
}
