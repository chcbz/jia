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
import cn.jia.wx.service.impl.UserServiceImpl;

@FeignClient(name="jia-api-user", path="/user", configuration = {OAuth2FeignAutoConfig.class}, fallback = UserServiceImpl.class)
public interface UserService {
	@GetMapping(value = "/get")
	JSONResult<Map<String, Object>> find(@RequestParam(name = "type", defaultValue = "id") String type, @RequestParam(name = "key", required = true) String key) throws Exception;
	
	/**
	 * 创建用户
	 * @param user
	 * @return
	 */
	@PostMapping(value = "/create")
	public JSONResult<Map<String, Object>> create(@RequestBody Map<String, Object> user) throws Exception;

	/**
	 * 批量同步用户
	 * @param userList
	 * @return
	 */
	@PostMapping(value = "/sync")
	public JSONResult<Map<String, Object>> sync(@RequestBody List<Map<String, Object>> userList) throws Exception;
}
