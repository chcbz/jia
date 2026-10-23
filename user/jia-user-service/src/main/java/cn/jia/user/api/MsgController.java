package cn.jia.user.api;

import cn.jia.core.entity.JsonRequest;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.user.common.UserConstants;
import cn.jia.user.entity.MsgEntity;
import cn.jia.user.service.MsgService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/msg")
public class MsgController {
	
	@Autowired
	private MsgService msgService;
	
	/**
	 * 获取消息
	 *
	 * @param id 消息ID
	 * @return 消息内容
	 */
//	@PreAuthorize("hasAuthority('msg-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) throws Exception {
		MsgEntity msg = msgService.get(id);
		return JsonResult.success(msg);
	}

	/**
	 * 创建消息
	 *
	 * @param msg 消息内容
	 * @return 结果
	 */
	@PreAuthorize("hasAuthority('msg-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody JsonRequest<MsgEntity> protocolRequest) {
        MsgEntity msg = protocolRequest == null ? null : protocolRequest.data();
		msgService.create(msg);
		return JsonResult.success();
	}

	/**
	 * 读消息
	 *
	 * @param id 消息ID
	 * @return 结果
	 */
//	@PreAuthorize("hasAuthority('msg-read')")
	@RequestMapping(value = "/read", method = RequestMethod.POST)
	public Object read(@RequestParam(name = "id") Long id) {
		MsgEntity msg = new MsgEntity();
		msg.setId(id);
		msg.setStatus(UserConstants.MSG_STATUS_READED);
		msgService.update(msg);
		return JsonResult.success();
	}

	/**
	 * 设置消息未读
	 *
	 * @param id 消息ID
	 * @return 结果
	 */
	@RequestMapping(value = "/unread", method = RequestMethod.POST)
	public Object unread(@RequestParam(name = "id") Long id) {
		MsgEntity msg = new MsgEntity();
		msg.setId(id);
		msg.setStatus(UserConstants.MSG_STATUS_UNREAD);
		msgService.update(msg);
		return JsonResult.success();
	}
	
	/**
	 * 设置所有消息已读
	 *
	 * @param userId 用户ID
	 * @return 结果
	 */
	@RequestMapping(value = "/readall", method = RequestMethod.POST)
	public Object readAll(@RequestParam(name = "userId") Long userId) {
		msgService.readAll(userId);
		return JsonResult.success();
	}

	/**
	 * 永久删除消息
	 *
	 * @param id 消息ID
	 * @return 结果
	 */
//	@PreAuthorize("hasAuthority('msg-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.DELETE)
	public Object delete(@RequestParam(name = "id") Long id) {
		msgService.delete(id);
		return JsonResult.success();
	}

	/**
	 * 放入回收站
	 *
	 * @param id 消息ID
	 * @return 结果
	 */
	@RequestMapping(value = "/recycle", method = RequestMethod.POST)
	public Object recycle(@RequestParam(name = "id") Long id) {
		msgService.recycle(id);
		return JsonResult.success();
	}

	/**
	 * 恢复消息
	 *
	 * @param id 消息ID
	 * @return 结果
	 */
	@RequestMapping(value = "/restore", method = RequestMethod.POST)
	public Object restore(@RequestParam(name = "id") Long id) {
		msgService.restore(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有消息
	 *
	 * @return 消息列表
	 */
//	@PreAuthorize("hasAuthority('msg-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequest<JsonRequestPage<MsgEntity>> protocolRequest) {
        JsonRequestPage<MsgEntity> page = protocolRequest == null ? null : protocolRequest.data();
		MsgEntity example = Optional.ofNullable(page.getSearch()).orElse(new MsgEntity());
		PageInfo<MsgEntity> msgList = msgService.findPage(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<MsgEntity> result = JsonResultPage.page(msgList.getList(), msgList.getPageNum(), msgList.getPageSize(), msgList.getTotal());
		return result;
	}
	
}
