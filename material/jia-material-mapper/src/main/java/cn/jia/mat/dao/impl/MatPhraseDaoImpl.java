package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatPhraseDao;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.mapper.MatPhraseMapper;
import jakarta.inject.Named;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Named
public class MatPhraseDaoImpl extends BaseDaoImpl<MatPhraseMapper, MatPhraseEntity> implements MatPhraseDao {

    @Override
    public MatPhraseEntity selectRandom(MatPhraseEntity record) {
        return baseMapper.selectRandom(record);
    }
}
