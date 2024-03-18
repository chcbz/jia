package cn.jia.mat.dao.impl;

import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.mat.dao.MatVoteItemDao;
import cn.jia.mat.entity.MatVoteItemEntity;
import cn.jia.mat.mapper.MatVoteItemMapper;
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
public class MatVoteItemDaoImpl extends BaseDaoImpl<MatVoteItemMapper, MatVoteItemEntity> implements MatVoteItemDao {

    @Override
    public List<MatVoteItemEntity> selectByQuestionId(Long questionId) {
        return baseMapper.selectList(Wrappers.lambdaQuery(MatVoteItemEntity.class)
                .eq(MatVoteItemEntity::getQuestionId, questionId));
    }

    @Override
    public void deleteByVoteId(Long voteId) {
        baseMapper.deleteByVoteId(voteId);
    }
}
