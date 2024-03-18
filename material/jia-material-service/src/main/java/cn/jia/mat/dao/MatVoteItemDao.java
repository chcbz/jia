package cn.jia.mat.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.mat.entity.MatVoteItemEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatVoteItemDao extends IBaseDao<MatVoteItemEntity> {

    List<MatVoteItemEntity> selectByQuestionId(Long questionId);

    void deleteByVoteId(Long voteId);
}
