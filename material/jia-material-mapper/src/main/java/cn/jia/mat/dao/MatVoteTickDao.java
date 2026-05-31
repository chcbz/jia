package cn.jia.mat.dao;

import cn.jia.core.dao.IBaseDao;
import cn.jia.mat.entity.MatVoteTickEntity;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author chc
 * @since 2021-10-29
 */
public interface MatVoteTickDao extends IBaseDao<MatVoteTickEntity> {

    void deleteByVoteId(Long voteId);

    List<MatVoteTickEntity> selectByJiacn(MatVoteTickEntity voteTick);
}
