package cn.jia.workflow.listener;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsErrorConstants;
import cn.jia.core.util.*;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.*;
import org.camunda.bpm.engine.impl.el.JuelExpression;
import jakarta.inject.Inject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import jakarta.inject.Named;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Rest接口调用通用方法
 * @author chcbz
 * @since 2018年9月11日 下午3:37:55
 */
@Slf4j
@Named
public class RestListener implements ExecutionListener, TaskListener {
	
	private static final long serialVersionUID = -507889727531125820L;
	
	private JuelExpression url;
	private JuelExpression method;
	private JuelExpression params;
	@Inject
	@Qualifier("singleRestTemplate")
	private RestTemplate restTemplate;
	@Inject
	private TaskService taskService;

	@Override
	public void notify(DelegateTask delegateTask) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
//		OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)SecurityContextHolder.getContext().getAuthentication().getDetails();
//		headers.set("Authorization", "Bearer " + details.getTokenValue());
		String rqurl = fixedValueToString(url, delegateTask);
		String random = DataUtil.getRandom(false, 16);
		rqurl = HttpUtil.addUrlValue(rqurl, "nonce", random);
		String signature = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(random)+EsSecurityHandler.clientId());
		rqurl = HttpUtil.addUrlValue(rqurl, "signature", signature);
		String businessKey = String.valueOf(taskService.getVariable(delegateTask.getId(), "businessKey"));
		rqurl = HttpUtil.addUrlValue(rqurl, "businessKey", businessKey);
		String assignee = delegateTask.getAssignee();
		rqurl = HttpUtil.addUrlValue(rqurl, "assignee", assignee);

		String paramsStr = fixedValueToString(params, delegateTask);
		if(StringUtil.isNotEmpty(paramsStr)){
			Map<String, Object> paramsMap = JsonUtil.jsonToMap(paramsStr);
			if(paramsMap != null){
				for (String paramsKey : paramsMap.keySet()) {
					rqurl = HttpUtil.addUrlValue(rqurl, paramsKey, String.valueOf(paramsMap.get(paramsKey)));
				}
			}

		}

//		String candidate = "";
//		Set<IdentityLink> candidates = delegateTask.getCandidates();
//		for(IdentityLink id : candidates){
//			candidate += "," + id.getUserId();
//		}
//		rqurl = HttpUtil.addUrlValue(rqurl, "candidate", StringUtil.isEmpty(candidate) ? "" : candidate.substring(1));
		log.info("======================rqurl: " + rqurl);
		log.info("======================method: " + fixedValueToString(method, delegateTask));
		log.info("======================params: " + paramsStr);
		HttpEntity<String> entity = new HttpEntity<>(fixedValueToString(params, delegateTask), headers);
		JsonResult<?> result = restTemplate.exchange(rqurl,
				HttpMethod.valueOf(fixedValueToString(method, delegateTask)), entity, JsonResult.class).getBody();
		if(!EsErrorConstants.SUCCESS.getCode().equals(result.getCode())) {
			log.error(result.getMsg());
		}
	}

	@Override
	public void notify(DelegateExecution execution) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
//		OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)SecurityContextHolder.getContext().getAuthentication().getDetails();
//		headers.set("Authorization", "Bearer " + details.getTokenValue());
		String rqurl = fixedValueToString(url, execution);
		String random = DataUtil.getRandom(false, 16);
		rqurl = HttpUtil.addUrlValue(rqurl, "nonce", random);
		String signature = Md5Util.str2Base32Md5(Md5Util.str2Base32Md5(random)+EsSecurityHandler.clientId());
		rqurl = HttpUtil.addUrlValue(rqurl, "signature", signature);
		String businessKey = execution.getBusinessKey();
		rqurl = HttpUtil.addUrlValue(rqurl, "businessKey", businessKey);
		HttpEntity<String> entity = new HttpEntity<>(fixedValueToString(params, execution), headers);
		JsonResult<?> result = restTemplate.exchange(rqurl,
				HttpMethod.valueOf(fixedValueToString(method, execution)), entity, JsonResult.class).getBody();
		if(!EsErrorConstants.SUCCESS.getCode().equals(result.getCode())) {
			log.error(result.getMsg());
		}
	}
	
	private String fixedValueToString(Expression fixedValue, VariableScope variableScope) {
		if(fixedValue == null) {
			return null;
		}
		return fixedValue.getValue(variableScope).toString();
	}

}
