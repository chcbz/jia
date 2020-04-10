package cn.jia.user.service;

import org.springframework.cloud.netflix.feign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import cn.jia.core.entity.JSONResult;
import cn.jia.user.service.impl.SmsServiceImpl;

@FeignClient(name="jia-api-sms", path="/sms", fallback = SmsServiceImpl.class)
public interface SmsService {
	/**
	 * 验证码已经被使用
	 * @param id
	 */
	@RequestMapping(value = "/use", method = RequestMethod.GET)
	public JSONResult<String> useSmsCode(@RequestParam("phone") String phone, @RequestParam("smsType") Integer smsType) throws Exception;
	
	/**
	 * 生成验证码信息
	 * @param phone 电话号码
	 * @param smsType 验证码类型
	 * @return 最新验证码
	 * @throws Exception
	 */
	@RequestMapping(value = "/gen", method = RequestMethod.GET)
	public JSONResult<String> gen(@RequestParam("phone") String phone, @RequestParam("smsType") Integer smsType) throws Exception;
}
