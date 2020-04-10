package cn.jia.wx.service;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import cn.jia.core.entity.JSONResult;
import cn.jia.wx.config.OAuth2FeignAutoConfig;
import cn.jia.wx.service.impl.GiftServiceImpl;

@FeignClient(name="jia-api-point", path="/gift", configuration = {OAuth2FeignAutoConfig.class}, fallback = GiftServiceImpl.class)
public interface GiftService {
	/**
	 * 获取所有礼品信息
	 * @return
	 */
	@PostMapping(value = "/list")
	public JSONResult<List<Map<String, Object>>> list(@RequestBody Map<String, Object> page) throws Exception;
	
	/**
	 * 获取礼品信息
	 * @param id
	 * @return
	 */
	@GetMapping(value = "/get")
	public JSONResult<Map<String, Object>> findById(@RequestParam(name = "id", required = true) Integer id) throws Exception;
	
	/**
	 * 礼品兑换
	 * @param giftUsage
	 * @return
	 * @throws Exception 
	 */
	@PostMapping(value = "/usage/add")
	public JSONResult<Map<String, Object>> usage(@RequestBody Map<String, Object> giftUsage) throws Exception;
}
