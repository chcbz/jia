package cn.jia.mat.api;

import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.entity.MatTipEntity;
import cn.jia.mat.service.MatTipService;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/tip")
@Slf4j
public class TipController {
	
	@Autowired
	private MatTipService tipService;
	
	/**
	 * 获取打赏信息
	 * @param id 打赏ID
	 * @return 打赏信息
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id") Long id) throws Exception{
		MatTipEntity tip = tipService.get(id);
		if(tip == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		return JsonResult.success(tip);
	}
	
	/**
	 * 创建打赏
	 * @param tip 打赏信息
	 * @return 打赏信息
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody MatTipEntity tip) {
		tipService.create(tip);
		return JsonResult.success(tip);
	}

	/**
	 * 更新打赏信息
	 * @param tip 打赏信息
	 * @return 打赏信息
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody MatTipEntity tip) {
		tipService.update(tip);
		return JsonResult.success(tip);
	}

	/**
	 * 删除打赏
	 * @param id 打赏ID
	 * @return 处理结果
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Long id) throws Exception {
		MatTipEntity tip = tipService.get(id);
		if(tip == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		tipService.delete(id);
		return JsonResult.success();
	}

	/**
	 * 获取所有打赏信息
	 * @return 打赏信息
	 */
	@PostMapping(value = "/list")
	public Object list(@RequestBody JsonRequestPage<MatTipEntity> page) {
		PageInfo<MatTipEntity> list = tipService.findPage(page.getSearch(), page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<MatTipEntity> result = new JsonResultPage<>(list.getList());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
}
