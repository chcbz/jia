package cn.jia.mat.api;

import cn.jia.core.entity.JsonRequest;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.util.JsonUtil;
import cn.jia.mat.entity.*;
import cn.jia.mat.service.MatVoteService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/vote")
public class VoteController {
	
	@Autowired
	private MatVoteService voteService;
//	@Autowired
//	private PointService pointService;

	/**
	 * 获取投票信息
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id") Long id) throws Exception{
		MatVoteEntity vote = voteService.find(id);
//		String material_url = dictService.getValue(Constants.DICT_TYPE_MODULE_URL, Constants.MODULE_URL_MATERIAL);
//		for(VoteQuestion question : vote.getQuestions()) {
//			for(VoteItem item : question.getItems()) {
//				item.setPicLink(material_url + "/" + item.getPicUrl());
//			}
//		}
		return JsonResult.success(vote);
	}

	/**
	 * 创建投票
	 * @param vote 投票信息
	 * @return
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody JsonRequest<MatVoteResVO> protocolRequest) {
        MatVoteResVO vote = protocolRequest == null ? null : protocolRequest.data();
		voteService.create(vote);
		return JsonResult.success(vote);
	}

	/**
	 * 更新投票信息
	 * @param vote 投票信息
	 * @return
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody JsonRequest<MatVoteEntity> protocolRequest) {
        MatVoteEntity vote = protocolRequest == null ? null : protocolRequest.data();
		voteService.update(vote);
		return JsonResult.success();
	}

	/**
	 * 删除投票
	 * @param id
	 * @return
	 */
	@org.springframework.web.bind.annotation.DeleteMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Long id) {
		voteService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有投票信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JsonRequest<JsonRequestPage<MatVoteReqVO>> protocolRequest) {
        JsonRequestPage<MatVoteReqVO> page = protocolRequest == null ? null : protocolRequest.data();
		MatVoteReqVO example = Optional.ofNullable(page.getSearch()).orElse(new MatVoteReqVO());
		PageInfo<MatVoteEntity> voteList = voteService.list(page.getPageNum(), page.getPageSize(), example, page.getOrderBy());
		JsonResultPage<MatVoteEntity> result = JsonResultPage.page(voteList.getList(), voteList.getPageNum(), voteList.getPageSize(), voteList.getTotal());
		return result;
	}
	
	/**
	 * 获取当前用户所选项
	 * @param voteTick
	 * @return
	 */
	@PostMapping(value = "/get/ticks")
	public Object findTicks(@RequestBody JsonRequest<MatVoteTickEntity> protocolRequest) {
        MatVoteTickEntity voteTick = protocolRequest == null ? null : protocolRequest.data();
		List<MatVoteTickEntity> voteTickList = voteService.findTickByJiacn(voteTick);
		return JsonResult.success(voteTickList);
	}

	/**
	 * 随机获取还没做过的投票
	 * @param jiacn 用户jiacn
	 * @return 投票题目
	 */
	@GetMapping(value = "/get/random")
	public Object findRandom(@RequestParam String jiacn) {
		MatVoteQuestionVO question = voteService.findOneQuestion(jiacn);
		return JsonResult.success(question);
	}

	/**
	 * 投票
	 * @param voteTick 头票信息
	 * @return 正确与否
	 * @throws Exception 异常信息
	 */
	@PostMapping(value = "/tick")
	public Object tick(@RequestBody JsonRequest<MatVoteTickEntity> protocolRequest) throws Exception {
        MatVoteTickEntity voteTick = protocolRequest == null ? null : protocolRequest.data();
		boolean tick = voteService.tick(voteTick);
//		if(tick) {
//			VoteQuestion voteQuestion = voteService.findQuestion(voteTick.getQuestionId());
//			pointService.add(voteTick.getJiacn(), voteQuestion.getPoint(), cn.jia.point.common.Constants.POINT_TYPE_VOTE);
//		}
		return JsonResult.success(tick);
	}
	
}
