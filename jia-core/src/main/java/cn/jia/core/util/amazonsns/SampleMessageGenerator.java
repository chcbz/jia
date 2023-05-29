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
import org.codehaus.jackson.map.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * @author chc
 */
public class SampleMessageGenerator {

	/**
	 * This message is delivered if a platform specific message is not specified
	 * for the end point. It must be set. It is received by the device as the
	 * value of the key "default".
	 */
	public static final String DEFAULT_MESSAGE = "This is the default message";

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * 平台
	 */
	public enum Platform {
		/**
		 * APNS
		 */
		APNS,APNS_SANDBOX,GCM;}

	public static String jsonify(Object message) {
		try {
			return OBJECT_MAPPER.writeValueAsString(message);
		} catch (Exception e) {
			e.printStackTrace();
			throw (RuntimeException) e;
		}
	}

	/**
	 * 苹果发送信息模板
	 *
	 * @param msgMap
	 * @return
	 */
	public static String getAppleMessage(Map<String,Object> msgMap) {
		Map<String, Object> appleMessageMap = new HashMap<>(3);
		Map<String, Object> appMessageMap = new HashMap<>(1);
//		appMessageMap.put("alert", msgMap.get("alert").toString());
//		appMessageMap.put("sound", "default");
		appMessageMap.put("content-available", 1);
		appleMessageMap.put("aps", appMessageMap);
		appleMessageMap.put("alert", msgMap.get("alert").toString());
		appleMessageMap.put("msg_content", msgMap.get("msg").toString());
		return jsonify(appleMessageMap);
	}

	/**
	 * 安卓发送信息模板
	 *
	 * @param msgMap
	 * @return
	 */
	public static String getAndroidMessage(Map<String,Object> msgMap) {
		Map<String, Object> androidMessageMap = new HashMap<>(4);
		Map<String, Object> payload = new HashMap<>(1);
		payload.put("message", msgMap.get("msg"));
		androidMessageMap.put("collapse_key", msgMap.get("alert").toString());
		androidMessageMap.put("data", payload);
		androidMessageMap.put("time_to_live", 125);
		androidMessageMap.put("dry_run", false);
		return jsonify(androidMessageMap);
	}
}