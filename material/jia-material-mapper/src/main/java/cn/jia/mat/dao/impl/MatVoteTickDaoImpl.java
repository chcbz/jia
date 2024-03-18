package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatVoteTickDao;
import cn.jia.mat.entity.MatVoteTickEntity;
import cn.jia.mat.mapper.MatVoteTickMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.inject.Named;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
@Named
public class MatVoteTickDaoImpl extends BaseDaoImpl<MatVoteTickMapper, MatVoteTickEntity> implements MatVoteTickDao {

    @Override
    public void deleteByVoteId(Long voteId) {
        baseMapper.delete(Wrappers.lambdaQuery(MatVoteTickEntity.class).eq(MatVoteTickEntity::getVoteId, voteId));
    }

    @Override
    public List<MatVoteTickEntity> selectByJiacn(MatVoteTickEntity voteTick) {
        return baseMapper.selectList(Wrappers.lambdaQuery(MatVoteTickEntity.class).eq(MatVoteTickEntity::getJiacn,
                voteTick.getJiacn()).eq(MatVoteTickEntity::getVoteId, voteTick.getVoteId()));
    }
}
