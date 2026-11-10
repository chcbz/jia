package cn.jia.mat.service;


import cn.jia.common.service.IBaseService;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;

public interface MatPhraseService extends IBaseService<MatPhraseEntity> {
	MatPhraseEntity findRandom(MatPhraseEntity example);

    MatPhraseEntity findScheduledRandom(String clientId);

	void vote(MatPhraseVoteEntity vote);

	void read(Long id);
}
