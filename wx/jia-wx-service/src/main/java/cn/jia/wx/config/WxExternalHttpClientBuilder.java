package cn.jia.wx.config;

import me.chanjar.weixin.common.util.http.apache.ApacheHttpClientBuilder;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

/** A per-service Wx MP client builder for explicitly configured transport overrides. */
public final class WxExternalHttpClientBuilder implements ApacheHttpClientBuilder {
    private final int connectionRequestTimeoutMillis;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private String proxyHost;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;
    private HttpRequestRetryHandler retryHandler;
    private ConnectionKeepAliveStrategy keepAliveStrategy;
    private SSLConnectionSocketFactory sslConnectionSocketFactory;

    public WxExternalHttpClientBuilder(int connectionRequestTimeoutMillis, int connectTimeoutMillis,
            int readTimeoutMillis) {
        this.connectionRequestTimeoutMillis = connectionRequestTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public CloseableHttpClient build() {
        RequestConfig.Builder requestConfig = RequestConfig.custom();
        if (connectionRequestTimeoutMillis > 0) {
            requestConfig.setConnectionRequestTimeout(connectionRequestTimeoutMillis);
        }
        if (connectTimeoutMillis > 0) {
            requestConfig.setConnectTimeout(connectTimeoutMillis);
        }
        if (readTimeoutMillis > 0) {
            requestConfig.setSocketTimeout(readTimeoutMillis);
        }
        var builder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig.build())
                .disableAutomaticRetries();
        if (retryHandler != null) {
            builder.setRetryHandler(retryHandler);
        }
        if (keepAliveStrategy != null) {
            builder.setKeepAliveStrategy(keepAliveStrategy);
        }
        if (sslConnectionSocketFactory != null) {
            builder.setSSLSocketFactory(sslConnectionSocketFactory);
        }
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.setProxy(new HttpHost(proxyHost, proxyPort));
            if (proxyUsername != null && !proxyUsername.isBlank()) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(new AuthScope(proxyHost, proxyPort),
                        new UsernamePasswordCredentials(proxyUsername, proxyPassword));
                builder.setDefaultCredentialsProvider(credentials);
            }
        }
        return builder.build();
    }

    @Override
    public ApacheHttpClientBuilder httpProxyHost(String httpProxyHost) {
        this.proxyHost = httpProxyHost;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder httpProxyPort(int httpProxyPort) {
        this.proxyPort = httpProxyPort;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder httpProxyUsername(String httpProxyUsername) {
        this.proxyUsername = httpProxyUsername;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder httpProxyPassword(String httpProxyPassword) {
        this.proxyPassword = httpProxyPassword;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder httpRequestRetryHandler(HttpRequestRetryHandler httpRequestRetryHandler) {
        this.retryHandler = httpRequestRetryHandler;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder keepAliveStrategy(ConnectionKeepAliveStrategy keepAliveStrategy) {
        this.keepAliveStrategy = keepAliveStrategy;
        return this;
    }

    @Override
    public ApacheHttpClientBuilder sslConnectionSocketFactory(SSLConnectionSocketFactory sslConnectionSocketFactory) {
        this.sslConnectionSocketFactory = sslConnectionSocketFactory;
        return this;
    }
}
