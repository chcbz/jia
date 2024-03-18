package cn.jia.mat.service;


import cn.jia.common.service.IBaseService;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;

public interface MatPhraseService extends IBaseService<MatPhraseEntity> {
	MatPhraseEntity findRandom(MatPhraseEntity example);

	void vote(MatPhraseVoteEntity vote) throws Exception;

	void read(Long id) throws Exception;
}
