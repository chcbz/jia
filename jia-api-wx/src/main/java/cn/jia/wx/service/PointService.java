package cn.jia.wx.service;

import java.util.Map;

import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import cn.jia.core.entity.JSONResult;
import cn.jia.wx.config.OAuth2FeignAutoConfig;
import cn.jia.wx.service.impl.PointServiceImpl;

@FeignClient(name="jia-api-point", path="/point", configuration = {OAuth2FeignAutoConfig.class}, fallback = PointServiceImpl.class)
public interface PointService {
	/**
	 * 新用户
	 * @param record
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/init")
	public JSONResult<Map<String, Object>> init(@RequestBody Map<String, Object> record) throws Exception;
	
	/**
	 * 签到
	 * @param sign
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/sign")
	public JSONResult<Map<String, Object>> sign(@RequestBody Map<String, Object> sign) throws Exception;

	/**
	 * 推荐
	 * @param referral
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/referral")
	public JSONResult<Map<String, Object>> referral(@RequestBody Map<String, Object> referral) throws Exception;
	
	/**
	 * 试试手气
	 * @param record
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/luck")
	public JSONResult<Map<String, Object>> luck(@RequestBody Map<String, Object> record) throws Exception;
}
