package cn.jia.workflow.listener;

import cn.jia.core.common.EsSecurityHandler;
import cn.jia.core.entity.JSONResult;
import cn.jia.core.util.*;
import cn.jia.workflow.common.ErrorConstants;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.delegate.*;
import org.camunda.bpm.engine.impl.el.JuelExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Rest接口调用通用方法
 * @author chcbz
 * @date 2018年9月11日 下午3:37:55
 */
@Slf4j
@Service
public class RestListener implements ExecutionListener, TaskListener {
	
	private static final long serialVersionUID = -507889727531125820L;
	
	private JuelExpression url;
	private JuelExpression method;
	private JuelExpression params;
	@Autowired
	@Qualifier("singleRestTemplate")
	private RestTemplate restTemplate;
	@Autowired
	private TaskService taskService;

	@Override
	public void notify(DelegateTask delegateTask) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
//		OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)SecurityContextHolder.getContext().getAuthentication().getDetails();
//		headers.set("Authorization", "Bearer " + details.getTokenValue());
		String rqurl = fixedValueToString(url, delegateTask);
		String random = DataUtil.getRandom(false, 16);
		rqurl = HttpUtil.addUrlValue(rqurl, "nonce", random);
		String signature = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(random)+EsSecurityHandler.clientId());
		rqurl = HttpUtil.addUrlValue(rqurl, "signature", signature);
		String businessKey = String.valueOf(taskService.getVariable(delegateTask.getId(), "businessKey"));
		rqurl = HttpUtil.addUrlValue(rqurl, "businessKey", businessKey);
		String assignee = delegateTask.getAssignee();
		rqurl = HttpUtil.addUrlValue(rqurl, "assignee", assignee);

		String paramsStr = fixedValueToString(params, delegateTask);
		if(StringUtils.isNotEmpty(paramsStr)){
			Map<String, Object> paramsMap = JSONUtil.jsonToMap(paramsStr);
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
//		rqurl = HttpUtil.addUrlValue(rqurl, "candidate", StringUtils.isEmpty(candidate) ? "" : candidate.substring(1));
		log.info("======================rqurl: " + rqurl);
		log.info("======================method: " + fixedValueToString(method, delegateTask));
		log.info("======================params: " + paramsStr);
		HttpEntity<String> entity = new HttpEntity<>(fixedValueToString(params, delegateTask), headers);
		JSONResult<?> result = restTemplate.exchange(rqurl, HttpMethod.resolve(fixedValueToString(method, delegateTask)), entity, JSONResult.class).getBody();
		if(!ErrorConstants.SUCCESS.equals(result.getCode())) {
			log.error(result.getMsg());
		}
	}

	@Override
	public void notify(DelegateExecution execution) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON_UTF8);
//		OAuth2AuthenticationDetails details = (OAuth2AuthenticationDetails)SecurityContextHolder.getContext().getAuthentication().getDetails();
//		headers.set("Authorization", "Bearer " + details.getTokenValue());
		String rqurl = fixedValueToString(url, execution);
		String random = DataUtil.getRandom(false, 16);
		rqurl = HttpUtil.addUrlValue(rqurl, "nonce", random);
		String signature = MD5Util.str2Base32MD5(MD5Util.str2Base32MD5(random)+EsSecurityHandler.clientId());
		rqurl = HttpUtil.addUrlValue(rqurl, "signature", signature);
		String businessKey = execution.getBusinessKey();
		rqurl = HttpUtil.addUrlValue(rqurl, "businessKey", businessKey);
		HttpEntity<String> entity = new HttpEntity<>(fixedValueToString(params, execution), headers);
		JSONResult<?> result = restTemplate.exchange(rqurl, HttpMethod.resolve(fixedValueToString(method, execution)), entity, JSONResult.class).getBody();
		if(!ErrorConstants.SUCCESS.equals(result.getCode())) {
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
