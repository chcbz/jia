package cn.jia.sms.service;

import java.util.Map;

import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import cn.jia.core.entity.JSONResult;
import cn.jia.sms.config.OAuth2FeignAutoConfig;
import cn.jia.sms.service.impl.WxMpServiceImpl;

@FeignClient(name="jia-api-wx", path="/wx/mp", configuration = {OAuth2FeignAutoConfig.class}, fallback = WxMpServiceImpl.class)
public interface WxMpService {
	
	/**
	 * 客服接口-发消息
	 * @param message
	 * @param request
	 * @return
	 * @throws Exception
	 */
	@PostMapping("/message/custom/send")
	@ResponseBody
	public JSONResult<Map<String, Object>> customMessageSend(@RequestParam("appid") String appid, @RequestBody Map<String, Object> message) throws Exception;
}
