package cn.jia.material.dao;

import cn.jia.material.entity.VoteItem;

import java.util.List;

public interface VoteItemMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(VoteItem record);

    int insertSelective(VoteItem record);

    VoteItem selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(VoteItem record);

    int updateByPrimaryKey(VoteItem record);

    List<VoteItem> selectByQuestionId(Integer questionId);

    void deleteByVoteId(Integer voteId);
}