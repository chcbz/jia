package cn.jia.material.service;

import cn.jia.material.entity.Vote;
import cn.jia.material.entity.VoteTick;
import com.github.pagehelper.Page;

import java.util.List;

public interface VoteService {
	
	Vote create(Vote vote);

	Vote find(Integer id) throws Exception;

	Vote update(Vote vote);

	void delete(Integer id);
	
	Page<Vote> list(int pageNo, int pageSize, Vote example);
	
	List<VoteTick> findTickByJiacn(VoteTick voteTick);
}
