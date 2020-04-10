package cn.jia.material.dao;

import cn.jia.material.entity.Phrase;

public interface PhraseMapper {
    int deleteByPrimaryKey(Integer id);

    int insert(Phrase record);

    int insertSelective(Phrase record);

    Phrase selectByPrimaryKey(Integer id);

    int updateByPrimaryKeySelective(Phrase record);

    int updateByPrimaryKey(Phrase record);

    Phrase selectRandom(Phrase record);
}