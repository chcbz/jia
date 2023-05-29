package cn.jia.core.util.amazonsns;

/*
 * Copyright 2014 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
import cn.jia.core.util.amazonsns.SampleMessageGenerator.Platform;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.model.*;

import java.util.HashMap;
import java.util.Map;

;

/**
 * @author chc
 */
public class AmazonSnsClientWrapper {

	private final AmazonSNS snsClient;

	public AmazonSnsClientWrapper(AmazonSNS client) {
		this.snsClient = client;
	}

	/**
	 * 创建平台应用
	 *
	 * @param applicationName 应用名称
	 * @param platform 平台
	 * @param principal 用户名
	 * @param credential 密码
	 * @return 平台应用信息
	 */
	private CreatePlatformApplicationResult createPlatformApplication(
			String applicationName, Platform platform, String principal,
			String credential) {
		CreatePlatformApplicationRequest platformApplicationRequest = new CreatePlatformApplicationRequest();
		Map<String, String> attributes = new HashMap<>(2);
		attributes.put("PlatformPrincipal", principal);
		attributes.put("PlatformCredential", credential);
		platformApplicationRequest.setAttributes(attributes);
		platformApplicationRequest.setName(applicationName);
		platformApplicationRequest.setPlatform(platform.name());
		return snsClient.createPlatformApplication(platformApplicationRequest);
	}

	/**
	 * 创建平台终端节点
	 *
	 * @param platform
	 * @param customData
	 * @param platformToken
	 * @param applicationArn
	 * @return
	 */
	private CreatePlatformEndpointResult createPlatformEndpoint(
			Platform platform, String customData, String platformToken,
			String applicationArn) {
		CreatePlatformEndpointRequest platformEndpointRequest = new CreatePlatformEndpointRequest();
		platformEndpointRequest.setCustomUserData(customData);
		String token = platformToken;
		platformEndpointRequest.setToken(token);
		platformEndpointRequest.setPlatformApplicationArn(applicationArn);
		return snsClient.createPlatformEndpoint(platformEndpointRequest);
	}

	/**
	 * 删除平台终端节点
	 *
	 * @param endpointArn
	 */
	private void deleteEndpointResult(String endpointArn)
	{
		DeleteEndpointRequest deleteEndpointRequest=new DeleteEndpointRequest();
		deleteEndpointRequest.setEndpointArn(endpointArn);
		snsClient.deleteEndpoint(deleteEndpointRequest);
	}

	/**
	 * 删除应用平台
	 *
	 * @param applicationArn
	 */
	private void deletePlatformApplication(String applicationArn) {
		DeletePlatformApplicationRequest request = new DeletePlatformApplicationRequest();
		request.setPlatformApplicationArn(applicationArn);
		snsClient.deletePlatformApplication(request);
	}

	/**
	 * 样例
	 * @param platform
	 * @param principal
	 * @param credential
	 * @param platformToken
	 * @param applicationName
	 * @param msg
	 */
	public void demoNotification(Platform platform, String principal,
			String credential, String platformToken, String applicationName,Map<String,Object> msg) {
		//创建平台引用程序,对应一个应用平台
		CreatePlatformApplicationResult platformApplicationResult = createPlatformApplication(applicationName, platform, principal, credential);
		System.out.println("<<======platformApplicationResult:"+platformApplicationResult);

		// 平台的应用程序是可用于唯一地标识该平台的应用程序。
		String platformApplicationArn = platformApplicationResult.getPlatformApplicationArn();
		System.out.println("<<======platformApplicationArn:"+platformApplicationArn);

		// 创建一个端点。这对应于一个应用程序在设备上。
		CreatePlatformEndpointResult platformEndpointResult = createPlatformEndpoint(platform,"CustomData - Useful to store endpoint specific data",platformToken, platformApplicationArn);
		System.out.println("<<<=====platformEndpointResult:"+platformEndpointResult);

		// 推送式通知发布到一个端点。
		PublishResult publishResult = publish(platformEndpointResult.getEndpointArn(), platform,msg);
		System.out.println("Published! \n{MessageId="+ publishResult.getMessageId() + "}");
		// 删除该平台的应用程序,因为我们将不再使用它。
		deletePlatformApplication(platformApplicationArn);
	}
	
	/**
	 * 测试Amazon SNS
	 */
	public void iosNotification(Platform platform,String platformApplicationArn,String platformToken,Map<String,Object> msg) {
		// 平台的应用程序是可用于唯一地标识该平台的应用程序。 SNS上面已经设置
		System.out.println("<<======平台应用程序Arn:"+platformApplicationArn);
		// 创建一个端点。这对应于一个应用程序在设备上。
		CreatePlatformEndpointResult platformEndpointResult = createPlatformEndpoint(platform,"Test Amazon SNS for IOS",platformToken, platformApplicationArn);
		System.out.println("<<<=====平台端点结果:"+platformEndpointResult);
		// 推送式通知发布到一个端点。
		String endpointArn="";
		if("gcm".equalsIgnoreCase(platform.name()))
		{
			System.out.println("<<<<<======================当前平台为："+platform.name());
			endpointArn="arn:aws:sns:eu-central-1:773320271894:endpoint/GCM/SfereFare_User_Android/5df78b07-8b89-35f0-a109-49682f83c9db";
		}else{
			endpointArn=platformEndpointResult.getEndpointArn();
		}
		PublishResult publishResult = publish(endpointArn, platform,msg);
		System.out.println("Published! \n{MessageId="+ publishResult.getMessageId() + "}");
		// 删除该平台的应用程序,因为我们将不再使用它。
		deleteEndpointResult(platformEndpointResult.getEndpointArn());
	}
	
	/**
	 * 发送通知
	 */
	public void notification(Platform platform,String platformApplicationArn,String platformToken,Map<String,Object> msg) {
		// 平台的应用程序是可用于唯一地标识该平台的应用程序。 SNS上面已经设置
		// 创建一个端点。这对应于一个应用程序在设备上。
		CreatePlatformEndpointResult platformEndpointResult = createPlatformEndpoint(platform,"Test Amazon SNS",platformToken, platformApplicationArn);
		// 推送式通知发布到一个端点。
		PublishResult publishResult = publish(platformEndpointResult.getEndpointArn(), platform,msg);
		System.out.println("Published! \n{MessageId="+ publishResult.getMessageId() + "}");
		// 删除该平台的应用程序,因为我们将不再使用它。
		deleteEndpointResult(platformEndpointResult.getEndpointArn());
	}


	/**
	 * 发布推送
	 *
	 * @param endpointArn
	 * @param platform
	 * @param msg
	 * @return
	 */
	private PublishResult publish(String endpointArn, Platform platform,Map<String,Object> msg) {
		PublishRequest publishRequest = new PublishRequest();
		publishRequest.setMessageStructure("json");
		// If the message attributes are not set in the requisite method, notification is sent with default attributes
		String message = getPlatformSampleMessage(platform,msg);
		Map<String, String> messageMap = new HashMap<>(1);
		messageMap.put(platform.name(), message);
		message = SampleMessageGenerator.jsonify(messageMap);
		// For direct publish to mobile end points, topicArn is not relevant.
		publishRequest.setTargetArn(endpointArn);
		// Display the message that will be sent to the endpoint/
		System.out.println("{Message Body: " + message + "}");
		publishRequest.setMessage(message);
		return snsClient.publish(publishRequest);
	}

	/**
	 * 根据不同平台选择发送信息模板
	 *
	 * @param platform
	 * @param msg
	 * @return
	 */
	private String getPlatformSampleMessage(Platform platform,Map<String,Object> msg) {
		switch (platform) {
		case APNS:
			return SampleMessageGenerator.getAppleMessage(msg);
		case APNS_SANDBOX:
			return SampleMessageGenerator.getAppleMessage(msg);
		case GCM:
			return SampleMessageGenerator.getAndroidMessage(msg);
		default:
			throw new IllegalArgumentException("Platform not supported : "
					+ platform.name());
		}
	}
}
