package cn.jia.isp.api;

import cn.jia.core.entity.JsonRequest;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.isp.common.IspErrorConstants;
import cn.jia.isp.entity.CarBrandEntity;
import cn.jia.isp.entity.CarBrandAudiEntity;
import cn.jia.isp.entity.CarBrandMfEntity;
import cn.jia.isp.entity.CarBrandVersionEntity;
import cn.jia.isp.service.CarService;
import com.github.pagehelper.PageInfo;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.Optional;

/**
 * 车辆数据接口
 * @author chc
 */
@RestController
@RequestMapping("/car")
public class CarController {
	
	@Inject
	private CarService carService;

	/**
	 * 车辆品牌列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 品牌列表
	 */
	@RequestMapping(value = "/list/brand", method = RequestMethod.POST)
	public Object listBrand(@RequestBody JsonRequest<JsonRequestPage<CarBrandEntity>> protocolRequest, HttpServletRequest request) {
        JsonRequestPage<CarBrandEntity> page = protocolRequest == null ? null : protocolRequest.data();
		String clientId = EsSecurityHandler.checkClientId(request);
		CarBrandEntity example = Optional.ofNullable(page.getSearch()).orElse(new CarBrandEntity());
		example.setClientId(clientId);
		PageInfo<CarBrandEntity> carList = carService.listBrand(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<CarBrandEntity> result = JsonResultPage.page(carList.getList(), carList.getPageNum(), carList.getPageSize(), carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆品牌信息
	 * @param id 品牌ID
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/get", method = RequestMethod.GET)
	public Object findBrandById(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandEntity record = carService.findBrand(id);
		if(record == null) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(record);
	}

	/**
	 * 创建车辆品牌
	 * @param record 品牌信息
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/create", method = RequestMethod.POST)
	public Object createBrand(@RequestBody JsonRequest<CarBrandEntity> protocolRequest) {
        CarBrandEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.createBrand(record);
		return JsonResult.success(record);
	}

	/**
	 * 更新车辆品牌信息
	 * @param record 品牌信息
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/update", method = RequestMethod.POST)
	public Object updateBrand(@RequestBody JsonRequest<CarBrandEntity> protocolRequest) {
        CarBrandEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.updateBrand(record);
		return JsonResult.success(record);
	}

	/**
	 * 删除车辆品牌
	 * @param id 品牌ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brand/delete", method = RequestMethod.DELETE)
	public Object deleteBrand(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandEntity record = carService.findBrand(id);
		if(record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrand(id);
		return JsonResult.success();
	}
	
	/**
	 * 车辆系列列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 系列列表
	 */
	@RequestMapping(value = "/list/brandAudi", method = RequestMethod.POST)
	public Object listBrandAudi(@RequestBody JsonRequest<JsonRequestPage<CarBrandAudiEntity>> protocolRequest, HttpServletRequest request) {
        JsonRequestPage<CarBrandAudiEntity> page = protocolRequest == null ? null : protocolRequest.data();
		EsSecurityHandler.checkClientId(request);
		CarBrandAudiEntity example = Optional.ofNullable(page.getSearch()).orElse(new CarBrandAudiEntity());
		PageInfo<CarBrandAudiEntity> carList = carService.listBrandAudi(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<CarBrandAudiEntity> result = JsonResultPage.page(carList.getList(), carList.getPageNum(), carList.getPageSize(), carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆系列信息
	 * @param id 系列ID
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/get", method = RequestMethod.GET)
	public Object findBrandAudiById(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandAudiEntity record = carService.findBrandAudi(id);
		if(record == null) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(record);
	}

	/**
	 * 创建车辆系列
	 * @param record 系列信息
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/create", method = RequestMethod.POST)
	public Object createBrandAudi(@RequestBody JsonRequest<CarBrandAudiEntity> protocolRequest) {
        CarBrandAudiEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.createBrandAudi(record);
		return JsonResult.success(record);
	}

	/**
	 * 更新车辆系列信息
	 * @param record 系列信息
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/update", method = RequestMethod.POST)
	public Object updateBrandAudi(@RequestBody JsonRequest<CarBrandAudiEntity> protocolRequest) {
        CarBrandAudiEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.updateBrandAudi(record);
		return JsonResult.success(record);
	}

	/**
	 * 删除车辆系列
	 * @param id 系列ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandAudi/delete", method = RequestMethod.DELETE)
	public Object deleteBrandAudi(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandAudiEntity record = carService.findBrandAudi(id);
		if(record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandAudi(id);
		return JsonResult.success();
	}
	
	/**
	 * 车辆车型列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 车型列表
	 */
	@RequestMapping(value = "/list/brandVersion", method = RequestMethod.POST)
	public Object listBrandVersion(@RequestBody JsonRequest<JsonRequestPage<CarBrandVersionEntity>> protocolRequest, HttpServletRequest request) {
        JsonRequestPage<CarBrandVersionEntity> page = protocolRequest == null ? null : protocolRequest.data();
		EsSecurityHandler.checkClientId(request);
		CarBrandVersionEntity example = Optional.ofNullable(page.getSearch()).orElse(new CarBrandVersionEntity());
		PageInfo<CarBrandVersionEntity> carList = carService.listBrandVersion(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<CarBrandVersionEntity> result = JsonResultPage.page(carList.getList(), carList.getPageNum(), carList.getPageSize(), carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆车型信息
	 * @param id 车型ID
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/get", method = RequestMethod.GET)
	public Object findBrandVersionById(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandVersionEntity record = carService.findBrandVersion(id);
		if(record == null) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(record);
	}

	/**
	 * 创建车辆车型
	 * @param record 车型信息
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/create", method = RequestMethod.POST)
	public Object createBrandVersion(@RequestBody JsonRequest<CarBrandVersionEntity> protocolRequest) {
        CarBrandVersionEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.createBrandVersion(record);
		return JsonResult.success(record);
	}

	/**
	 * 更新车辆车型信息
	 * @param record 车型信息
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/update", method = RequestMethod.POST)
	public Object updateBrandVersion(@RequestBody JsonRequest<CarBrandVersionEntity> protocolRequest) {
        CarBrandVersionEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.updateBrandVersion(record);
		return JsonResult.success(record);
	}

	/**
	 * 删除车辆车型
	 * @param id 车型ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandVersion/delete", method = RequestMethod.DELETE)
	public Object deleteBrandVersion(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandVersionEntity record = carService.findBrandVersion(id);
		if(record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandVersion(id);
		return JsonResult.success();
	}
	
	/**
	 * 车辆制造商列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 制造商列表
	 */
	@RequestMapping(value = "/list/brandMf", method = RequestMethod.POST)
	public Object listBrandMf(@RequestBody JsonRequest<JsonRequestPage<CarBrandMfEntity>> protocolRequest, HttpServletRequest request) {
        JsonRequestPage<CarBrandMfEntity> page = protocolRequest == null ? null : protocolRequest.data();
		EsSecurityHandler.checkClientId(request);
		CarBrandMfEntity example = Optional.ofNullable(page.getSearch()).orElse(new CarBrandMfEntity());
		PageInfo<CarBrandMfEntity> carList = carService.listBrandMf(example, page.getPageNum(), page.getPageSize(), page.getOrderBy());
		JsonResultPage<CarBrandMfEntity> result = JsonResultPage.page(carList.getList(), carList.getPageNum(), carList.getPageSize(), carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆制造商信息
	 * @param id 制造商ID
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/get", method = RequestMethod.GET)
	public Object findBrandMfById(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandMfEntity record = carService.findBrandMf(id);
		if(record == null) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(record);
	}

	/**
	 * 创建车辆制造商
	 * @param record 制造商信息
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/create", method = RequestMethod.POST)
	public Object createBrandMf(@RequestBody JsonRequest<CarBrandMfEntity> protocolRequest) {
        CarBrandMfEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.createBrandMf(record);
		return JsonResult.success(record);
	}

	/**
	 * 更新车辆制造商信息
	 * @param record 制造商信息
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/update", method = RequestMethod.POST)
	public Object updateBrandMf(@RequestBody JsonRequest<CarBrandMfEntity> protocolRequest) {
        CarBrandMfEntity record = protocolRequest == null ? null : protocolRequest.data();
		carService.updateBrandMf(record);
		return JsonResult.success(record);
	}

	/**
	 * 删除车辆制造商
	 * @param id 制造商ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandMf/delete", method = RequestMethod.DELETE)
	public Object deleteBrandMf(@RequestParam(name = "id") Long id) throws Exception {
		CarBrandMfEntity record = carService.findBrandMf(id);
		if(record == null || !Objects.equals(EsContextHolder.getContext().getClientId(), record.getClientId())) {
			throw new EsRuntimeException(IspErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandMf(id);
		return JsonResult.success();
	}

}