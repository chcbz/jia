package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatVoteQuestionDao;
import cn.jia.mat.entity.MatVoteQuestionEntity;
import cn.jia.mat.mapper.MatVoteQuestionMapper;
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
public class MatVoteQuestionDaoImpl extends BaseDaoImpl<MatVoteQuestionMapper, MatVoteQuestionEntity>
        implements MatVoteQuestionDao {

    @Override
    public List<MatVoteQuestionEntity> selectByVoteId(Long voteId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(MatVoteQuestionEntity.class)
                .eq(MatVoteQuestionEntity::getVoteId, voteId));
    }

    @Override
    public int deleteByVoteId(Long voteId) {
        return baseMapper.delete(Wrappers.lambdaQuery(MatVoteQuestionEntity.class)
                .eq(MatVoteQuestionEntity::getVoteId, voteId));
    }

    @Override
    public MatVoteQuestionEntity selectNoTick(String jiacn) {
        return baseMapper.selectNoTick(jiacn);
    }
}
