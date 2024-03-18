package cn.jia.core.configuration;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(RestHighLevelClient.class)
public class ElasticsearchConfig {

    @Value("${elasticsearch.host}")
    private String host;
    @Value("${elasticsearch.port}")
    private int port;
    @Value("${elasticsearch.schema:http}")
    private String schema;
    @Value("${elasticsearch.connectTimeOut:1000}")
    private int connectTimeOut;
    @Value("${elasticsearch.socketTimeOut:30000}")
    private int socketTimeOut;
    @Value("${elasticsearch.connectionRequestTimeOut:500}")
    private int connectionRequestTimeOut;
    @Value("${elasticsearch.maxConnectNum:100}")
    private int maxConnectNum;
    @Value("${elasticsearch.maxConnectPerRoute:100}")
    private int maxConnectPerRoute;
    private RestClientBuilder builder;

    /**
     * 返回一个RestHighLevelClient
     * @return RestHighLevelClient
     */
    @Bean("restHighLevelClient")
    public RestHighLevelClient client() {
        HttpHost httpHost = new HttpHost(host, port, schema);
        builder = RestClient.builder(httpHost);
        setConnectTimeOutConfig();
        setMutiConnectConfig();
        return new RestHighLevelClient(builder);
    }

    /**
     * 异步httpclient的连接延时配置
     */
    private void setConnectTimeOutConfig() {
        builder.setRequestConfigCallback(requestConfigBuilder -> {
            requestConfigBuilder.setConnectTimeout(connectTimeOut);
            requestConfigBuilder.setSocketTimeout(socketTimeOut);
            requestConfigBuilder.setConnectionRequestTimeout(connectionRequestTimeOut);
            return requestConfigBuilder;
        });
    }

    /**
     * 异步httpclient的连接数配置
     */
    private void setMutiConnectConfig() {
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            httpClientBuilder.setMaxConnTotal(maxConnectNum);
            httpClientBuilder.setMaxConnPerRoute(maxConnectPerRoute);
            return httpClientBuilder;
        });
    }


}