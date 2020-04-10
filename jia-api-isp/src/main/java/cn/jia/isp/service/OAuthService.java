package cn.jia.isp.service;

import cn.jia.core.entity.JSONResult;
import cn.jia.isp.config.OAuth2FeignAutoConfig;
import cn.jia.isp.service.impl.OAuthServiceImpl;
import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@FeignClient(name="jia-api-oauth", path="/oauth", configuration = {OAuth2FeignAutoConfig.class}, fallback = OAuthServiceImpl.class)
public interface OAuthService {
	
	/**
	 * 添加客户端资源
	 * @param resourceId
	 * @return
	 * @throws Exception
	 */
	@GetMapping(value = "/client/addresource")
	JSONResult<Map<String, Object>> addResource(@RequestParam(name = "resourceId") String resourceId) throws Exception;
}
