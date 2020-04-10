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
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;

/**
 * 车辆数据接口
 * @author chc
 */
@RestController
@RequestMapping("/car")
public class CarController {
	
	@Autowired
	private CarService carService;

	/**
	 * 车辆品牌列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 品牌列表
	 */
	@RequestMapping(value = "/list/brand", method = RequestMethod.POST)
	public Object listBrand(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
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
	 * @param id 品牌ID
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/get", method = RequestMethod.GET)
	public Object findBrandById(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrand record = carService.findBrand(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆品牌
	 * @param record 品牌信息
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/create", method = RequestMethod.POST)
	public Object createBrand(@RequestBody CarBrand record) {
		carService.createBrand(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆品牌信息
	 * @param record 品牌信息
	 * @return 品牌信息
	 */
	@RequestMapping(value = "/brand/update", method = RequestMethod.POST)
	public Object updateBrand(@RequestBody CarBrand record) {
		carService.updateBrand(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆品牌
	 * @param id 品牌ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brand/delete", method = RequestMethod.GET)
	public Object deleteBrand(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrand record = carService.findBrand(id);
		if(record == null || !Objects.equals(EsSecurityHandler.clientId(), record.getClientId())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrand(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆系列列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 系列列表
	 */
	@RequestMapping(value = "/list/brandAudi", method = RequestMethod.POST)
	public Object listBrandAudi(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
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
	 * @param id 系列ID
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/get", method = RequestMethod.GET)
	public Object findBrandAudiById(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandAudi record = carService.findBrandAudi(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆系列
	 * @param record 系列信息
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/create", method = RequestMethod.POST)
	public Object createBrandAudi(@RequestBody CarBrandAudi record) {
		carService.createBrandAudi(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆系列信息
	 * @param record 系列信息
	 * @return 系列信息
	 */
	@RequestMapping(value = "/brandAudi/update", method = RequestMethod.POST)
	public Object updateBrandAudi(@RequestBody CarBrandAudi record) {
		carService.updateBrandAudi(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆系列
	 * @param id 系列ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandAudi/delete", method = RequestMethod.GET)
	public Object deleteBrandAudi(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandAudi record = carService.findBrandAudi(id);
		if(record == null || !Objects.equals(EsSecurityHandler.clientId(), record.getClientId())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandAudi(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆车型列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 车型列表
	 */
	@RequestMapping(value = "/list/brandVersion", method = RequestMethod.POST)
	public Object listBrandVersion(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
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
	 * @param id 车型ID
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/get", method = RequestMethod.GET)
	public Object findBrandVersionById(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandVersion record = carService.findBrandVersion(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆车型
	 * @param record 车型信息
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/create", method = RequestMethod.POST)
	public Object createBrandVersion(@RequestBody CarBrandVersion record) {
		carService.createBrandVersion(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆车型信息
	 * @param record 车型信息
	 * @return 车型信息
	 */
	@RequestMapping(value = "/brandVersion/update", method = RequestMethod.POST)
	public Object updateBrandVersion(@RequestBody CarBrandVersion record) {
		carService.updateBrandVersion(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆车型
	 * @param id 车型ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandVersion/delete", method = RequestMethod.GET)
	public Object deleteBrandVersion(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandVersion record = carService.findBrandVersion(id);
		if(record == null || !Objects.equals(EsSecurityHandler.clientId(), record.getClientId())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandVersion(id);
		return JSONResult.success();
	}
	
	/**
	 * 车辆制造商列表
	 * @param page 查询条件
	 * @param request HTTP请求信息
	 * @return 制造商列表
	 */
	@RequestMapping(value = "/list/brandMf", method = RequestMethod.POST)
	public Object listBrandMf(@RequestBody JSONRequestPage<String> page, HttpServletRequest request) {
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
	 * @param id 制造商ID
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/get", method = RequestMethod.GET)
	public Object findBrandMfById(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandMf record = carService.findBrandMf(id);
		if(record == null) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		return JSONResult.success(record);
	}

	/**
	 * 创建车辆制造商
	 * @param record 制造商信息
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/create", method = RequestMethod.POST)
	public Object createBrandMf(@RequestBody CarBrandMf record) {
		carService.createBrandMf(record);
		return JSONResult.success(record);
	}

	/**
	 * 更新车辆制造商信息
	 * @param record 制造商信息
	 * @return 制造商信息
	 */
	@RequestMapping(value = "/brandMf/update", method = RequestMethod.POST)
	public Object updateBrandMf(@RequestBody CarBrandMf record) {
		carService.updateBrandMf(record);
		return JSONResult.success(record);
	}

	/**
	 * 删除车辆制造商
	 * @param id 制造商ID
	 * @return 处理结果
	 */
	@RequestMapping(value = "/brandMf/delete", method = RequestMethod.GET)
	public Object deleteBrandMf(@RequestParam(name = "id") Integer id) throws Exception {
		CarBrandMf record = carService.findBrandMf(id);
		if(record == null || !Objects.equals(EsSecurityHandler.clientId(), record.getClientId())) {
			throw new EsRuntimeException(ErrorConstants.DATA_NOT_FOUND);
		}
		carService.deleteBrandMf(id);
		return JSONResult.success();
	}

}