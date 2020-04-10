package cn.jia.material.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.service.DictService;
import cn.jia.core.util.JSONUtil;
import cn.jia.material.common.Constants;
import cn.jia.material.entity.Vote;
import cn.jia.material.entity.VoteItem;
import cn.jia.material.entity.VoteQuestion;
import cn.jia.material.entity.VoteTick;
import cn.jia.material.service.VoteService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/vote")
public class VoteController {
	
	@Autowired
	private VoteService voteService;
	@Autowired
	private DictService dictService;
	
	/**
	 * 获取投票信息
	 * @param id 投票ID
	 * @return 投票信息
	 */
	@PreAuthorize("hasAuthority('news-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Integer id) throws Exception{
		Vote vote = voteService.find(id);
		String material_url = dictService.selectByDictTypeAndDictValue(Constants.DICT_TYPE_MODULE_URL, Constants.MODULE_URL_MATERIAL).getName();
		for(VoteQuestion question : vote.getQuestions()) {
			for(VoteItem item : question.getItems()) {
				item.setPicLink(material_url + "/" + item.getPicUrl());
			}
		}
		return JSONResult.success(vote);
	}

	/**
	 * 创建投票
	 * @param vote 投票信息
	 * @return 投票信息
	 */
	@PreAuthorize("hasAuthority('news-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Vote vote) {
		vote.setClientId(EsSecurityHandler.clientId());
		voteService.create(vote);
		return JSONResult.success(vote);
	}

	/**
	 * 更新投票信息
	 * @param vote 投票信息
	 * @return 投票信息
	 */
	@PreAuthorize("hasAuthority('news-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody Vote vote) {
		voteService.update(vote);
		return JSONResult.success();
	}

	/**
	 * 删除投票
	 * @param id 投票ID
	 * @return 处理结果
	 */
	@PreAuthorize("hasAuthority('news-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		voteService.delete(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有投票信息
	 * @param page 分页搜索信息
	 * @return 投票列表
	 */
	@PreAuthorize("hasAuthority('news-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Vote example = JSONUtil.fromJson(page.getSearch(), Vote.class);
		example = example == null ? new Vote() : example;
		example.setClientId(EsSecurityHandler.clientId());
		Page<Vote> voteList = voteService.list(page.getPageNum(), page.getPageSize(), example);
		@SuppressWarnings({ "rawtypes", "unchecked" })
        JSONResultPage<Object> result = new JSONResultPage(voteList);
		result.setPageNum(voteList.getPageNum());
		result.setTotal(voteList.getTotal());
		return result;
	}
	
	/**
	 * 获取当前用户所选项
	 * @param voteTick 投票信息
	 * @return 投票结果
	 */
	@PreAuthorize("hasAuthority('news-get_ticks')")
	@RequestMapping(value = "/get/ticks", method = RequestMethod.POST)
	public Object findTicks(@RequestBody VoteTick voteTick) {
		List<VoteTick> voteTickList = voteService.findTickByJiacn(voteTick);
		return JSONResult.success(voteTickList);
	}
	
}
