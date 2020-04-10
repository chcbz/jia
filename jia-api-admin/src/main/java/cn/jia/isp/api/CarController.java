package cn.jia.isp.api;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONRequestPage;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.entity.JSONResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.JSONUtil;
import cn.jia.isp.common.ErrorConstants;
import cn.jia.isp.entity.CarBrand;
import cn.jia.isp.entity.CarBrandAudi;
import cn.jia.isp.entity.CarBrandMf;
import cn.jia.isp.entity.CarBrandVersion;
import cn.jia.isp.service.CarService;
import com.github.pagehelper.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * 车辆数据接口
 * @author chc
 * @date 2017年12月8日 下午3:31:20
 */
@RestController
@RequestMapping("/car")
public class CarController {
	
	@Autowired
	private CarService carService;
	
	/**
	 * 车辆品牌列表
	 * @param page
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('car-list_brand')")
	@RequestMapping(value = "/list/brand", method = RequestMethod.POST)
	public Object listBrand(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) throws Exception {
		String clientId = EsSecurityHandler.checkClientId(request);
		CarBrand example = JSONUtil.fromJson(page.getSearch(), CarBrand.class);
		if(example == null) {
			example = new CarBrand();
		}
		example.setClientId(clientId);
		Page<CarBrand> carList = carService.listBrand(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(carList.getResult());
		result.setPageNum(carList.getPageNum());
		result.setTotal(carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆品牌信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brand_get')")
	@RequestMapping(value = "/brand/get", method = RequestMethod.GET)
	public Object findBrandById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		CarBrand record = carService.findBrand(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆品牌
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brand_create')")
	@RequestMapping(value = "/brand/create", method = RequestMethod.POST)
	public Object createBrand(@RequestBody CarBrand record) {
		record.setClientId(EsSecurityHandler.clientId());
		carService.createBrand(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆品牌信息
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brand_update')")
	@RequestMapping(value = "/brand/update", method = RequestMethod.POST)
	public Object updateBrand(@RequestBody CarBrand record) {
		carService.updateBrand(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆品牌
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brand_delete')")
	@RequestMapping(value = "/brand/delete", method = RequestMethod.GET)
	public Object deleteBrand(@RequestParam(name = "id") Integer id) {
		carService.deleteBrand(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆系列列表
	 * @param page
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('car-list_brandAudi')")
	@RequestMapping(value = "/list/brandAudi", method = RequestMethod.POST)
	public Object listBrandAudi(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) throws Exception {
		EsSecurityHandler.checkClientId(request);
		CarBrandAudi example = JSONUtil.fromJson(page.getSearch(), CarBrandAudi.class);
		Page<CarBrandAudi> carList = carService.listBrandAudi(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(carList.getResult());
		result.setPageNum(carList.getPageNum());
		result.setTotal(carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆系列信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandAudi_get')")
	@RequestMapping(value = "/brandAudi/get", method = RequestMethod.GET)
	public Object findBrandAudiById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		CarBrandAudi record = carService.findBrandAudi(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆系列
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandAudi_create')")
	@RequestMapping(value = "/brandAudi/create", method = RequestMethod.POST)
	public Object createBrandAudi(@RequestBody CarBrandAudi record) {
		record.setClientId(EsSecurityHandler.clientId());
		carService.createBrandAudi(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆系列信息
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandAudi_update')")
	@RequestMapping(value = "/brandAudi/update", method = RequestMethod.POST)
	public Object updateBrandAudi(@RequestBody CarBrandAudi record) {
		carService.updateBrandAudi(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆系列
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandAudi_delete')")
	@RequestMapping(value = "/brandAudi/delete", method = RequestMethod.GET)
	public Object deleteBrandAudi(@RequestParam(name = "id") Integer id) {
		carService.deleteBrandAudi(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆车型列表
	 * @param page
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('car-list_brandVersion')")
	@RequestMapping(value = "/list/brandVersion", method = RequestMethod.POST)
	public Object listBrandVersion(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) throws Exception {
		EsSecurityHandler.checkClientId(request);
		CarBrandVersion example = JSONUtil.fromJson(page.getSearch(), CarBrandVersion.class);
		Page<CarBrandVersion> carList = carService.listBrandVersion(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(carList.getResult());
		result.setPageNum(carList.getPageNum());
		result.setTotal(carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆车型信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandVersion_get')")
	@RequestMapping(value = "/brandVersion/get", method = RequestMethod.GET)
	public Object findBrandVersionById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		CarBrandVersion record = carService.findBrandVersion(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆车型
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandVersion_create')")
	@RequestMapping(value = "/brandVersion/create", method = RequestMethod.POST)
	public Object createBrandVersion(@RequestBody CarBrandVersion record) {
		record.setClientId(EsSecurityHandler.clientId());
		carService.createBrandVersion(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆车型信息
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandVersion_update')")
	@RequestMapping(value = "/brandVersion/update", method = RequestMethod.POST)
	public Object updateBrandVersion(@RequestBody CarBrandVersion record) {
		carService.updateBrandVersion(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆车型
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandVersion_delete')")
	@RequestMapping(value = "/brandVersion/delete", method = RequestMethod.GET)
	public Object deleteBrandVersion(@RequestParam(name = "id") Integer id) {
		carService.deleteBrandVersion(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆制造商列表
	 * @param page
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PreAuthorize("hasAuthority('car-list_brandMf')")
	@RequestMapping(value = "/list/brandMf", method = RequestMethod.POST)
	public Object listBrandMf(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) throws Exception {
		EsSecurityHandler.checkClientId(request);
		CarBrandMf example = JSONUtil.fromJson(page.getSearch(), CarBrandMf.class);
		Page<CarBrandMf> carList = carService.listBrandMf(example, page.getPageNum(), page.getPageSize());
		JSONResultPage<Object> result = new JSONResultPage<>(carList.getResult());
		result.setPageNum(carList.getPageNum());
		result.setTotal(carList.getTotal());
		return result;
	}
	
	/**
	 * 获取车辆制造商信息
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandMf_get')")
	@RequestMapping(value = "/brandMf/get", method = RequestMethod.GET)
	public Object findBrandMfById(@RequestParam(name = "id", required = true) Integer id) throws Exception {
		CarBrandMf record = carService.findBrandMf(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆制造商
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandMf_create')")
	@RequestMapping(value = "/brandMf/create", method = RequestMethod.POST)
	public Object createBrandMf(@RequestBody CarBrandMf record) {
		record.setClientId(EsSecurityHandler.clientId());
		carService.createBrandMf(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆制造商信息
	 * @param record
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandMf_update')")
	@RequestMapping(value = "/brandMf/update", method = RequestMethod.POST)
	public Object updateBrandMf(@RequestBody CarBrandMf record) {
		carService.updateBrandMf(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆制造商
	 * @param id
	 * @return
	 */
	@PreAuthorize("hasAuthority('car-brandMf_delete')")
	@RequestMapping(value = "/brandMf/delete", method = RequestMethod.GET)
	public Object deleteBrandMf(@RequestParam(name = "id") Integer id) {
		carService.deleteBrandMf(id);
		return JSONResult.success();
	}
	
	/**
	 * 注册车辆查询服务
	 * @return
	 * @throws Exception
	 */
	/*@RequestMapping(value = "/register", method = RequestMethod.GET)
	public Object register() throws Exception {
		//新增客户端资源
		JSONResult<Map<String, Object>> resourceResult = oAuthService.addResource(resourceId);
		if(!ErrorConstants.SUCCESS.equals(resourceResult.getCode())) {
			throw new EsRuntimeException(resourceResult.getCode());
		}
		return JSONResult.success();
	}*/

}