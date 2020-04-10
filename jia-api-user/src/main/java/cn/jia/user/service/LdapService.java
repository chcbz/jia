package cn.jia.user.service;

import java.util.List;
import java.util.Map;

import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import cn.jia.core.entity.JSONResult;
import cn.jia.user.config.OAuth2FeignAutoConfig;
import cn.jia.user.service.impl.LdapServiceImpl;

@FeignClient(name="jia-api-isp", path="/ldap", configuration = {OAuth2FeignAutoConfig.class}, fallback = LdapServiceImpl.class)
public interface LdapService {
	
	/**
	 * 根据条件检索用户，只要其中一个匹配即可
	 * @param user
	 * @return
	 * @throws Exception
	 */
	@PostMapping(value = "/user/search")
	public JSONResult<List<Map<String, Object>>> userFind(@RequestBody Map<String, Object> user) throws Exception;
	
	/**
	 * 创建用户
	 * @param user
	 * @return
	 */
	@PostMapping(value = "/user/create")
	public JSONResult<String> userCreate(@RequestBody Map<String, Object> user) throws Exception;
}
