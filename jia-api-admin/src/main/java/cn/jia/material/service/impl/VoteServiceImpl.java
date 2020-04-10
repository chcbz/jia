package cn.jia.material.service.impl;

import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.material.common.ErrorConstants;
import cn.jia.material.dao.VoteItemMapper;
import cn.jia.material.dao.VoteMapper;
import cn.jia.material.dao.VoteQuestionMapper;
import cn.jia.material.dao.VoteTickMapper;
import cn.jia.material.entity.Vote;
import cn.jia.material.entity.VoteItem;
import cn.jia.material.entity.VoteQuestion;
import cn.jia.material.entity.VoteTick;
import cn.jia.material.service.VoteService;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class VoteServiceImpl implements VoteService {
	
	@Autowired
	private VoteMapper voteMapper;
	@Autowired
	private VoteQuestionMapper voteQuestionMapper;
	@Autowired
	private VoteItemMapper voteItemMapper;
	@Autowired
	private VoteTickMapper voteTickMapper;
	
	@Override
	public Vote create(Vote vote) {
		Long now = DateUtil.genTime(new Date());
		//如果保存过，则先清空原来的记录，重新生成
		if(vote.getId() != null) {
			delete(vote.getId());
		}
		List<VoteQuestion> questions = vote.getQuestions();
		vote.setId(null);
		vote.setStartTime(now);
		voteMapper.insertSelective(vote);
		for(VoteQuestion question : questions) {
			List<VoteItem> items = question.getItems();
			question.setId(null);
			question.setVoteId(vote.getId());
			voteQuestionMapper.insertSelective(question);
			for(VoteItem item : items) {
				item.setId(null);
				item.setQuestionId(question.getId());
				voteItemMapper.insertSelective(item);
			}
		}
		
		return vote;
	}

	@Override
	public Vote find(Integer id) throws Exception {
		Vote vote = voteMapper.selectByPrimaryKey(id);
		if(vote == null) {
			throw new EsRuntimeException(ErrorConstants.MEDIA_NOT_EXIST);
		}
		List<VoteQuestion> voteQuestionList = voteQuestionMapper.selectByVoteId(id);
		vote.setQuestions(voteQuestionList);
		for(VoteQuestion question : voteQuestionList) {
			List<VoteItem> voteItemList = voteItemMapper.selectByQuestionId(question.getId());
			question.setItems(voteItemList);
		}
		return vote;
	}

	@Override
	public Vote update(Vote vote) {
		voteMapper.updateByPrimaryKeySelective(vote);
		return vote;
	}

	@Override
	public void delete(Integer id) {
		voteTickMapper.deleteByVoteId(id);
		voteItemMapper.deleteByVoteId(id);
		voteQuestionMapper.deleteByVoteId(id);
		voteMapper.deleteByPrimaryKey(id);
	}

	@Override
	public Page<Vote> list(int pageNo, int pageSize, Vote example) {
		PageHelper.startPage(pageNo, pageSize);
		return voteMapper.selectByExample(example);
	}

	@Override
	public List<VoteTick> findTickByJiacn(VoteTick voteTick) {
		return voteTickMapper.selectByJiacn(voteTick);
	}
}
