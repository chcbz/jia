package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatVoteDao;
import cn.jia.mat.entity.MatVoteEntity;
import cn.jia.mat.mapper.MatVoteMapper;
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
public class MatVoteDaoImpl extends BaseDaoImpl<MatVoteMapper, MatVoteEntity> implements MatVoteDao {
}
