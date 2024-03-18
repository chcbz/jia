package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatPhraseVoteDao;
import cn.jia.mat.entity.MatPhraseVoteEntity;
import cn.jia.mat.mapper.MatPhraseVoteMapper;
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
public class MatPhraseVoteDaoImpl extends BaseDaoImpl<MatPhraseVoteMapper, MatPhraseVoteEntity>
        implements MatPhraseVoteDao {

}
