package cn.jia.material.dao;

import cn.jia.material.entity.VoteTick;

import java.util.List;

public interface VoteTickMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(VoteTick record);

    int insertSelective(VoteTick record);

    VoteTick selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(VoteTick record);

    int updateByPrimaryKey(VoteTick record);

    void deleteByVoteId(Integer voteId);

    List<VoteTick> selectByJiacn(VoteTick voteTick);
}