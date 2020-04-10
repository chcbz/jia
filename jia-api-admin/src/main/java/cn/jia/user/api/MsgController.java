package cn.jia.user.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.github.pagehelper.Page;

import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.util.JSONUtil;
import cn.jia.user.common.Constants;
import cn.jia.user.entity.Msg;
import cn.jia.user.service.MsgService;

@RestController
@RequestMapping("/msg")
public class MsgController {
	
	@Autowired
	private MsgService msgService;
	
	/**
	 * 获取消息
	 * @param id
	 * @return
	 */
//	@PreAuthorize("hasAuthority('msg-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		Msg msg = msgService.find(id);
		return JSONResult.success(msg);
	}

	/**
	 * 创建消息
	 * @param msg
	 * @return
	 */
	@PreAuthorize("hasAuthority('msg-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody Msg msg) {
		msgService.create(msg);
		return JSONResult.success();
	}

	/**
	 * 读消息
	 * @param id
	 * @return
	 */
//	@PreAuthorize("hasAuthority('msg-read')")
	@RequestMapping(value = "/read", method = RequestMethod.GET)
	public Object read(@RequestParam(name = "id") Integer id) {
		Msg msg = new Msg();
		msg.setId(id);
		msg.setStatus(Constants.MSG_STATUS_READED);
		msgService.update(msg);
		return JSONResult.success();
	}

	@RequestMapping(value = "/unread", method = RequestMethod.GET)
	public Object unread(@RequestParam(name = "id") Integer id) {
		Msg msg = new Msg();
		msg.setId(id);
		msg.setStatus(Constants.MSG_STATUS_UNREAD);
		msgService.update(msg);
		return JSONResult.success();
	}
	
	/**
	 * 设置所有消息已读
	 * @param userId
	 * @return
	 */
	@RequestMapping(value = "/readall", method = RequestMethod.GET)
	public Object readAll(@RequestParam(name = "userId") Integer userId) {
		msgService.readAll(userId);
		return JSONResult.success();
	}

	/**
	 * 永久删除消息
	 * @param id
	 * @return
	 */
//	@PreAuthorize("hasAuthority('msg-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.GET)
	public Object delete(@RequestParam(name = "id") Integer id) {
		msgService.delete(id);
		return JSONResult.success();
	}

	/**
	 * 放入回收站
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/recycle", method = RequestMethod.GET)
	public Object recycle(@RequestParam(name = "id") Integer id) {
		msgService.recycle(id);
		return JSONResult.success();
	}

	/**
	 * 恢复消息
	 * @param id
	 * @return
	 */
	@RequestMapping(value = "/restore", method = RequestMethod.GET)
	public Object restore(@RequestParam(name = "id") Integer id) {
		msgService.restore(id);
		return JSONResult.success();
	}
	
	/**
	 * 获取所有消息
	 * @return
	 */
//	@PreAuthorize("hasAuthority('msg-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JSONRequestPage<String> page) {
		Msg example = JSONUtil.fromJson(page.getSearch(), Msg.class);
		Page<Msg> msgList = msgService.list(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(msgList.getResult());
		result.setPageNum(msgList.getPageNum());
		result.setTotal(msgList.getTotal());
		return result;
	}
	
}
