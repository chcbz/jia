package cn.jia.mat.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.DateUtil;
import cn.jia.mat.common.MatConstants;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.dao.MatVoteDao;
import cn.jia.mat.dao.MatVoteItemDao;
import cn.jia.mat.dao.MatVoteQuestionDao;
import cn.jia.mat.dao.MatVoteTickDao;
import cn.jia.mat.entity.*;
import cn.jia.mat.service.MatVoteService;
import cn.jia.mat.vomapper.MatVoteQuestionVOMapper;
import cn.jia.mat.vomapper.MatVoteVOMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MatVoteServiceImpl implements MatVoteService {
	
	@Autowired
	private MatVoteDao matVoteDao;
	@Autowired
	private MatVoteQuestionDao matVoteQuestionDao;
	@Autowired
	private MatVoteItemDao matVoteItemDao;
	@Autowired
	private MatVoteTickDao matVoteTickDao;
	
	@Override
	public MatVoteEntity create(MatVoteResVO vote) {
		Long now = DateUtil.nowTime();
		//如果保存过，则先清空原来的记录，重新生成
		if(vote.getId() != null) {
			delete(vote.getId());
		}
		List<MatVoteQuestionVO> questions = vote.getQuestions();
		vote.setId(null);
		vote.setStartTime(now);
		vote.setClientId(EsContextHolder.getContext().getClientId());
		matVoteDao.insert(vote);
		for(MatVoteQuestionVO question : questions) {
			List<MatVoteItemEntity> items = question.getItems();
			question.setId(null);
			question.setVoteId(vote.getId());
			matVoteQuestionDao.insert(question);
			for(MatVoteItemEntity item : items) {
				item.setId(null);
				item.setQuestionId(question.getId());
				matVoteItemDao.insert(item);
			}
		}
		
		return vote;
	}

	@Override
	public MatVoteResVO find(Long id) throws Exception {
		MatVoteEntity vote = matVoteDao.selectById(id);
		if(vote == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		MatVoteResVO voteVO = MatVoteVOMapper.INSTANCE.toVO(vote);
		List<MatVoteQuestionEntity> voteQuestionList = matVoteQuestionDao.selectByVoteId(id);
		List<MatVoteQuestionVO> voteQuestionVOList = new ArrayList<>();
		for(MatVoteQuestionEntity question : voteQuestionList) {
			List<MatVoteItemEntity> voteItemList = matVoteItemDao.selectByQuestionId(question.getId());
			MatVoteQuestionVO questionVO = MatVoteQuestionVOMapper.INSTANCE.toVO(question);
			questionVO.setItems(voteItemList);
			voteQuestionVOList.add(questionVO);
		}
		voteVO.setQuestions(voteQuestionVOList);
		return voteVO;
	}

	@Override
	public MatVoteEntity update(MatVoteEntity vote) {
		matVoteDao.updateById(vote);
		return vote;
	}

	@Override
	public void delete(Long id) {
		matVoteTickDao.deleteByVoteId(id);
		matVoteItemDao.deleteByVoteId(id);
		matVoteQuestionDao.deleteByVoteId(id);
		matVoteDao.deleteById(id);
	}

	@Override
	public PageInfo<MatVoteEntity> list(int pageNo, int pageSize, MatVoteReqVO example) {
		if(example == null) {
			example = new MatVoteReqVO();
		}
		example.setClientId(EsContextHolder.getContext().getClientId());
		PageHelper.startPage(pageNo, pageSize);
		return PageInfo.of(matVoteDao.selectByEntity(example));
	}

	@Override
	public List<MatVoteTickEntity> findTickByJiacn(MatVoteTickEntity voteTick) {
		return matVoteTickDao.selectByJiacn(voteTick);
	}

	@Override
	public boolean tick(MatVoteTickEntity voteTick) {
		MatVoteQuestionEntity question = matVoteQuestionDao.selectById(voteTick.getQuestionId());
		voteTick.setVoteId(question.getVoteId());
		char[] questionOption = question.getOpt().toCharArray();
		Arrays.sort(questionOption);
		char[] tickOption = voteTick.getOpt().toCharArray();
		Arrays.sort(tickOption);
		boolean tick = String.valueOf(questionOption).equalsIgnoreCase(String.valueOf(tickOption));
		voteTick.setTick(tick ? MatConstants.COMMON_YES : MatConstants.COMMON_NO);
		matVoteTickDao.insert(voteTick);
		//增加投票数
		MatVoteEntity vote = matVoteDao.selectById(voteTick.getVoteId());
		MatVoteEntity upVote = new MatVoteEntity();
		upVote.setId(voteTick.getVoteId());
		upVote.setNum(vote.getNum() + 1);
		matVoteDao.updateById(upVote);
		List<MatVoteItemEntity> itemList = matVoteItemDao.selectByQuestionId(voteTick.getQuestionId());
		for(MatVoteItemEntity item : itemList) {
			if(item.getOpt().equalsIgnoreCase(voteTick.getOpt())) {
				MatVoteItemEntity upItem = new MatVoteItemEntity();
				upItem.setId(item.getId());
				upItem.setNum(item.getNum() + 1);
				matVoteItemDao.updateById(upItem);
			}
		}

		return tick;
	}

	@Override
	public MatVoteQuestionVO findOneQuestion(String jiacn) {
		MatVoteQuestionEntity question = matVoteQuestionDao.selectNoTick(jiacn);
		MatVoteQuestionVO questionVO = MatVoteQuestionVOMapper.INSTANCE.toVO(question);
		if (question != null) {
			List<MatVoteItemEntity> voteItemList = matVoteItemDao.selectByQuestionId(question.getId());
			questionVO.setItems(voteItemList);
		}
		return questionVO;
	}

	@Override
	public MatVoteQuestionEntity findQuestion(Long id) {
		return matVoteQuestionDao.selectById(id);
	}

	@Override
	public void batchImport(String voteName, String txt, String answer) {
		txt = txt.replaceAll("([ABCDEFGH]|\\d+)[.．]", "$1、")
				.replaceAll("[ 　]+\r\n", "\r\n")
				.replaceAll("、[ 　]+", "、");
		long now = DateUtil.nowTime();
		MatVoteEntity vote = new MatVoteEntity();
		vote.setClientId(EsContextHolder.getContext().getClientId());
		vote.setStartTime(now);
		vote.setCloseTime(now + 365 * 24 * 60 * 60);
		vote.setName(voteName);
		matVoteDao.insert(vote);

		answer = answer.replaceAll("([ABCDEFGH]|\\d+)[.．]", "$1、")
				.replaceAll("、[ 　]+", "、");

		int i = 0;
		while (txt.contains((++i) + "、")) {
			String seq = i + "、";
			txt = txt.substring(txt.indexOf(seq));
			MatVoteQuestionEntity voteQuestion = new MatVoteQuestionEntity();
			voteQuestion.setVoteId(vote.getId());
			voteQuestion.setPoint(1);
			voteQuestion.setTitle(txt.substring(seq.length(), txt.indexOf("\n")));
			txt = txt.substring(txt.indexOf("\n") + 1);
			Pattern pattern = Pattern.compile("[^\\d]" + i + "、([ABCDEFGH])");
			Matcher m = pattern.matcher(answer);
			String opt = "";
			if (m.find()) {
				opt = m.group(1);
			}
			voteQuestion.setOpt(opt);
			matVoteQuestionDao.insert(voteQuestion);

			String[] abcd = {"A", "B", "C", "D", "E", "F", "G", "H"};
			for (String s : abcd) {
				MatVoteItemEntity item = new MatVoteItemEntity();
				item.setQuestionId(voteQuestion.getId());
				item.setOpt(s);
				if(s.equalsIgnoreCase(opt)) {
					item.setTick(MatConstants.COMMON_YES);
				}
				if(!txt.contains(s + "、") || txt.indexOf(s + "、") > Math.abs(txt.indexOf("\n"))) {
					break;
				}
				txt = txt.substring(txt.indexOf(s + "、"));
				int endIndex = Math.min(txt.indexOf(" "), txt.indexOf("\n"));
				endIndex = endIndex == -1 ? Math.max(txt.indexOf(" "), txt.indexOf("\n")) : endIndex;
				item.setContent(txt.substring(2, endIndex == -1 ? txt.length() : endIndex));
				txt = txt.substring(endIndex == -1 ? 0 : endIndex + 1);
				matVoteItemDao.insert(item);
			}
		}
	}
}
