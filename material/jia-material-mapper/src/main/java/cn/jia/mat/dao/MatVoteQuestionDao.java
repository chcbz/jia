package cn.jia.mat.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.mat.entity.MatVoteQuestionEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatVoteQuestionDao extends IBaseDao<MatVoteQuestionEntity> {

    List<MatVoteQuestionEntity> selectByVoteId(Long voteId);

    int deleteByVoteId(Long voteId);

    MatVoteQuestionEntity selectNoTick(String jiacn);
}
