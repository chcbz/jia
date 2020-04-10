package cn.jia.material.dao;

import cn.jia.material.entity.PhraseVote;

public interface PhraseVoteMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(PhraseVote record);

    int insertSelective(PhraseVote record);

    PhraseVote selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(PhraseVote record);

    int updateByPrimaryKey(PhraseVote record);
}