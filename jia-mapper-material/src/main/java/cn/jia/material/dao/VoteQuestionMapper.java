package cn.jia.material.dao;

import cn.jia.material.entity.VoteQuestion;

import java.util.List;

public interface VoteQuestionMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(VoteQuestion record);

    int insertSelective(VoteQuestion record);

    VoteQuestion selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(VoteQuestion record);

    int updateByPrimaryKey(VoteQuestion record);

    List<VoteQuestion> selectByVoteId(Integer voteId);

    void deleteByVoteId(Integer voteId);

    VoteQuestion selectNoTick(String jiacn);
}