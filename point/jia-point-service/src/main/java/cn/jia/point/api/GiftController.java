package cn.jia.point.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.service.KefuService;
import cn.jia.point.entity.PointGiftEntity;
import cn.jia.point.entity.PointGiftUsageEntity;
import cn.jia.point.entity.PointGiftVO;
import cn.jia.point.service.GiftService;
import com.github.pagehelper.PageInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/gift")
public class GiftController {
	
	@Autowired
	private GiftService giftService;
	@Autowired(required = false)
	private KefuService kefuService;
	
	/**
	 * 获取礼品信息
	 * @param id
	 * @return
	 */
//	@PreAuthorize("hasAuthority('gift-get')")
	@RequestMapping(value = "/get", method = RequestMethod.GET)
	public Object findById(@RequestParam(name = "id") Long id) throws Exception {
		PointGiftEntity gift = giftService.find(id);
		return JsonResult.success(gift);
	}

	/**
	 * 创建礼品
	 * @param gift
	 * @return
	 */
	@PreAuthorize("hasAuthority('gift-create')")
	@RequestMapping(value = "/create", method = RequestMethod.POST)
	public Object create(@RequestBody PointGiftEntity gift) {
		giftService.create(gift);
		return JsonResult.success();
	}

	/**
	 * 更新礼品信息
	 * @param gift
	 * @return
	 */
	@PreAuthorize("hasAuthority('gift-update')")
	@RequestMapping(value = "/update", method = RequestMethod.POST)
	public Object update(@RequestBody PointGiftEntity gift) {
		giftService.update(gift);
		return JsonResult.success();
	}

	/**
	 * 删除礼品
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('gift-delete')")
	@RequestMapping(value = "/delete", method = RequestMethod.DELETE)
	public Object delete(@RequestParam(name = "id") Long id) {
		giftService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有礼品信息
	 * @return
	 */
//	@PreAuthorize("hasAuthority('gift-list')")
	@RequestMapping(value = "/list", method = RequestMethod.POST)
	public Object list(@RequestBody JsonRequestPage<PointGiftVO> page) {
		PageInfo<PointGiftEntity> giftList = giftService.list(page.getPageNum(), page.getPageSize(), page.getSearch(), page.getOrderBy());
		JsonResultPage<PointGiftEntity> result = new JsonResultPage<>(giftList.getList());
		result.setPageNum(giftList.getPageNum());
		result.setTotal(giftList.getTotal());
		return result;
	}
	
	/**
	 * 礼品兑换
	 * @param giftUsage
	 * @return
	 * @throws Exception 
	 */
//	@PreAuthorize("hasAuthority('gift-usage_add')")
	@RequestMapping(value = "/usage/add", method = RequestMethod.POST)
	public Object usageAdd(@RequestBody PointGiftUsageEntity giftUsage, HttpServletRequest request) throws Exception {
		String clientId = EsContextHolder.getContext().getClientId();
		giftUsage.setClientId(clientId);
		giftService.usage(giftUsage);
		// 通知管理员
		kefuService.sendMessage(KefuMsgTypeCode.GIFT_USAGE, clientId);
		return JsonResult.success(giftUsage);
	}

	/**
	 * 取消礼品兑换
	 * @param giftUsageId 订单ID
	 * @return 结果
	 * @throws Exception 异常
	 */
	@RequestMapping(value = "/usage/cancel/{giftUsageId}", method = RequestMethod.POST)
	public Object usageCancel(@PathVariable Long giftUsageId) throws Exception {
		giftService.usageCancel(giftUsageId);
		return JsonResult.success(giftUsageId);
	}

	/**
	 * 删除订单
	 * @param giftUsageId 订单ID
	 * @return 处理结果
	 * @throws Exception 异常
	 */
	@RequestMapping(value = "/usage/delete/{giftUsageId}", method = RequestMethod.POST)
	public Object usageDelete(@PathVariable Long giftUsageId) throws Exception {
		giftService.usageDelete(giftUsageId);
		return JsonResult.success(giftUsageId);
	}
	
	/**
	 * 根据礼品ID查找使用情况
	 * @param page
	 * @param giftId
	 * @return
	 */
//	@PreAuthorize("hasAuthority('gift-usage_list_gift')")
	@RequestMapping(value = "/usage/list/gift/{giftId}", method = RequestMethod.POST)
	public Object usageListByGift(@RequestBody JsonRequestPage<PointGiftUsageEntity> page, @PathVariable Long giftId) {
		PageInfo<PointGiftUsageEntity> usageList = giftService.usageListByGift(page.getPageNum(), page.getPageSize(), giftId, page.getOrderBy());
		JsonResultPage<PointGiftUsageEntity> result = new JsonResultPage<>(usageList.getList());
		result.setPageNum(usageList.getPageNum());
		result.setTotal(usageList.getTotal());
		return result;
	}
	
	/**
	 * 根据Jia账号查找使用情况
	 * @param page
	 * @param user
	 * @return
	 */
//	@PreAuthorize("hasAuthority('gift-usage_list_user')")
	@RequestMapping(value = "/usage/list/user/{user}", method = RequestMethod.POST)
	public Object usageListByUser(@RequestBody JsonRequestPage<PointGiftUsageEntity> page, @PathVariable String user) {
		PageInfo<PointGiftUsageEntity> usageList = giftService.usageListByUser(page.getPageNum(), page.getPageSize(), user, page.getOrderBy());
		JsonResultPage<PointGiftUsageEntity> result = new JsonResultPage<>(usageList.getList());
		result.setPageNum(usageList.getPageNum());
		result.setTotal(usageList.getTotal());
		return result;
	}
	
}
