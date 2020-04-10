package cn.jia.core.util;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Map;
import java.util.Map.Entry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class CyberPayUtil {
	final static Logger logger = Logger.getLogger(CyberPayUtil.class);
	
	String apiKey;
	String paymentAuthorizationRequest;
	private static CloseableHttpClient XPayHttpClient;

	public static CloseableHttpResponse doXPayTokenRequest(String baseUri, String resourcePath, String queryParams,
			String testInfo, String body, String methodType, Map<String, String> headers,String apiKey, String sharedSecret) throws Exception {
		String url = baseUri + resourcePath + "?" + queryParams;
		logRequestBody(url, testInfo, body);

		HttpRequest request = createHttpRequest(methodType, url);
		request.setHeader("content-type", "application/json");
		String xPayToken = generateXpaytoken(sharedSecret, resourcePath, "apikey=" + apiKey, body);
		request.setHeader("x-pay-token", xPayToken);
		request.setHeader("x-correlation-id", RandomStringUtils.random(10, true, true) + "_SC");

		if (headers != null && headers.isEmpty() == false) {
			for (Entry<String, String> header : headers.entrySet()) {
				request.setHeader(header.getKey(), header.getValue());
			}
		}

		if (request instanceof HttpPost) {
			((HttpPost) request).setEntity(new StringEntity(body, "UTF-8"));
		} else if (request instanceof HttpPut) {
			((HttpPut) request).setEntity(new StringEntity(body, "UTF-8"));
		}

		CloseableHttpResponse response = fetchXPayHttpClient().execute((HttpUriRequest) request);
//		logResponse(response);
		return response;
	}
	
	private static CloseableHttpClient fetchXPayHttpClient() {
        XPayHttpClient = HttpClients.createDefault();
        return XPayHttpClient;
    }
	
	private static void logRequestBody(String URI, String testInfo, String payload) {
        ObjectMapper mapper = getObjectMapperInstance();
        Object tree;
        logger.info("URI: " + URI);
        logger.info(testInfo);
        if(!StringUtils.isEmpty(payload)) {
            try {
                tree = mapper.readValue(payload,Object.class);
                logger.info("RequestBody: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
            } catch (JsonProcessingException e) {
                logger.error(e.getMessage());
            } catch (IOException e) {
                logger.error(e.getMessage());
            }
        }
    }
	
	protected static ObjectMapper getObjectMapperInstance() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true); // format json
        return mapper;
    }

	/*private static void logResponse(CloseableHttpResponse response) throws IOException {
		Header[] h = response.getAllHeaders();

		// Get the response json object
		BufferedReader rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
		StringBuffer result = new StringBuffer();
		String line;
		while ((line = rd.readLine()) != null) {
			result.append(line);
		}

		// Print the response details
		HttpEntity entity = response.getEntity();
		logger.info("Response status : " + response.getStatusLine() + "\n");

		logger.info("Response Headers: \n");

		for (int i = 0; i < h.length; i++)
			logger.info(h[i].getName() + ":" + h[i].getValue());
		logger.info("\n Response Body:");

		if (!StringUtils.isEmpty(result.toString())) {
			ObjectMapper mapper = getObjectMapperInstance();
			Object tree;
			try {
				tree = mapper.readValue(result.toString(), Object.class);
				logger.info("ResponseBody: " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
			} catch (JsonProcessingException e) {
				logger.error(e.getMessage());
			} catch (IOException e) {
				logger.error(e.getMessage());
			}
		}

		EntityUtils.consume(entity);
	}*/
	
	private static HttpRequest createHttpRequest(String methodType, String url) throws Exception {
    	HttpRequest request = null;
    	switch (methodType) {
    	case "GET":
    		request = new HttpGet(url);
    	    break;
    	case "POST":
    		request = new HttpPost(url);
    	    break;
    	case "PUT":
    		request = new HttpPut(url);
    	    break;
    	case "DELETE":
    		request = new HttpDelete(url);
    	    break;
    	default:
    		logger.error("Incompatible HTTP request method " + methodType);
    	}
    	return request;
   }
	
	public static String generateXpaytoken(String sharedSecret, String resourcePath, String queryString, String requestBody) throws SignatureException {
        String timestamp = timeStamp();
        String beforeHash = timestamp + resourcePath + queryString + requestBody;
        String hash = hmacSha256Digest(sharedSecret, beforeHash);
        String token = "xv2:" + timestamp + ":" + hash;
        return token;
    }
	
	private static String timeStamp() {
        return String.valueOf(System.currentTimeMillis()/ 1000L);
    }

    private static String hmacSha256Digest(String sharedSecret, String data)
            throws SignatureException {
        return getDigest("HmacSHA256", sharedSecret, data, true);
    }


    private static String getDigest(String algorithm, String sharedSecret, String data,
            boolean toLower) throws SignatureException {
        try {
            Mac sha256HMAC = Mac.getInstance(algorithm);
            SecretKeySpec secretKey = new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), algorithm);
            sha256HMAC.init(secretKey);

            byte[] hashByte = sha256HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
            String hashString = toHex(hashByte);

            return toLower ? hashString.toLowerCase() : hashString;
        } catch (Exception e) {
            throw new SignatureException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        BigInteger bi = new BigInteger(1, bytes);
        return String.format("%0" + (bytes.length << 1) + "X", bi);
    }
}
