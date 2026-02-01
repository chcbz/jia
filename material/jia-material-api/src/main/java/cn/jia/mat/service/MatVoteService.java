package cn.jia.mat.service;

import cn.jia.mat.entity.*;
import com.github.pagehelper.PageInfo;

import java.util.List;

public interface MatVoteService {
	
	MatVoteEntity create(MatVoteResVO vote);

	MatVoteResVO find(Long id) throws Exception;

	MatVoteEntity update(MatVoteEntity vote);

	void delete(Long id);
	
	PageInfo<MatVoteEntity> list(int pageNum, int pageSize, MatVoteReqVO example, String orderBy);
	
	List<MatVoteTickEntity> findTickByJiacn(MatVoteTickEntity voteTick);

	boolean tick(MatVoteTickEntity voteTick);

	MatVoteQuestionVO findOneQuestion(String jiacn);

	MatVoteQuestionEntity findQuestion(Long id);

	void batchImport(String voteName, String txt, String answer) throws Exception;
}
