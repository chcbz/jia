package cn.jia.sms.service;

import cn.jia.core.entity.JSONResult;
import cn.jia.sms.config.OAuth2FeignAutoConfig;
import cn.jia.sms.service.impl.OAuthServiceImpl;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name="jia-api-oauth", path="/oauth", configuration = {OAuth2FeignAutoConfig.class}, fallback = OAuthServiceImpl.class)
public interface OAuthService {
	
	/**
	 * 添加客户端资源
	 * @param resourceId 资源ID
	 * @return 资源信息
	 * @throws Exception 异常
	 */
	@GetMapping(value = "/client/addresource")
	JSONResult<Map<String, Object>> addResource(@RequestParam(name = "resourceId") String resourceId) throws Exception;
}
