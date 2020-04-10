package cn.jia.core.configuration;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
public class RestTemplateConfig {

	@LoadBalanced
    @Bean
    public RestTemplate restTemplate(ClientHttpRequestFactory factory){
    	RestTemplate template = new RestTemplate(factory);
    	// 使用 utf-8 编码集的 conver 替换默认的 conver（默认的 string conver 的编码集为 "ISO-8859-1"） 
        List<HttpMessageConverter<?>> messageConverters = template.getMessageConverters(); 
        Iterator<HttpMessageConverter<?>> iterator = messageConverters.iterator(); 
        while (iterator.hasNext()) { 
            HttpMessageConverter<?> converter = iterator.next(); 
            if (converter instanceof StringHttpMessageConverter) { 
                ((StringHttpMessageConverter)converter).setDefaultCharset(Charset.forName("utf-8"));
            } else if(converter instanceof AllEncompassingFormHttpMessageConverter) {
            	StringHttpMessageConverter stringHttpMessageConverter=new StringHttpMessageConverter(Charset.forName("utf-8"));
                List<HttpMessageConverter<?>> cos2=new ArrayList<>();  
                cos2.add(stringHttpMessageConverter);  
                cos2.add(new ByteArrayHttpMessageConverter());  
                cos2.add(new ResourceHttpMessageConverter());  
                ((AllEncompassingFormHttpMessageConverter)converter).setPartConverters(cos2);
            }
        }
        //异常处理
        template.setErrorHandler(new RestErrorHandler());
        return template;
    }
	
	@Bean
    public RestTemplate singleRestTemplate(ClientHttpRequestFactory factory){
    	RestTemplate template = new RestTemplate(factory);
    	// 使用 utf-8 编码集的 conver 替换默认的 conver（默认的 string conver 的编码集为 "ISO-8859-1"） 
        List<HttpMessageConverter<?>> messageConverters = template.getMessageConverters(); 
        Iterator<HttpMessageConverter<?>> iterator = messageConverters.iterator(); 
        while (iterator.hasNext()) { 
            HttpMessageConverter<?> converter = iterator.next(); 
            if (converter instanceof StringHttpMessageConverter) { 
                ((StringHttpMessageConverter)converter).setDefaultCharset(Charset.forName("utf-8"));
            } else if(converter instanceof AllEncompassingFormHttpMessageConverter) {
            	StringHttpMessageConverter stringHttpMessageConverter=new StringHttpMessageConverter(Charset.forName("utf-8"));
                List<HttpMessageConverter<?>> cos2=new ArrayList<>();  
                cos2.add(stringHttpMessageConverter);  
                cos2.add(new ByteArrayHttpMessageConverter());  
                cos2.add(new ResourceHttpMessageConverter());  
                ((AllEncompassingFormHttpMessageConverter)converter).setPartConverters(cos2);
            }
        }
        //异常处理
        template.setErrorHandler(new RestErrorHandler());
        return template;
    }

    @Bean
    public ClientHttpRequestFactory simpleClientHttpRequestFactory(){
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(60000);//单位为ms
        factory.setConnectTimeout(60000);//单位为ms
        return factory;
    }
    
    class RestErrorHandler implements ResponseErrorHandler {

		@Override
		public boolean hasError(ClientHttpResponse response) throws IOException {
			return false;
		}

		@Override
		public void handleError(ClientHttpResponse response) throws IOException {
			log.error(response.getStatusText());
		}
    	
    }
}