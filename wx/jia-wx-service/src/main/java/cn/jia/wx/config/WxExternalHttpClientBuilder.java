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

/** A per-service Wx MP client builder with bounded queue, connect, and read phases. */
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
            int readTimeoutMillis, int totalTimeoutMillis) {
        if (connectionRequestTimeoutMillis <= 0 || connectTimeoutMillis <= 0 || readTimeoutMillis <= 0
                || totalTimeoutMillis <= 0
                || (long) connectionRequestTimeoutMillis + connectTimeoutMillis + readTimeoutMillis > totalTimeoutMillis) {
            throw new IllegalArgumentException("Wx external HTTP phase timeouts must fit the total timeout budget");
        }
        this.connectionRequestTimeoutMillis = connectionRequestTimeoutMillis;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public CloseableHttpClient build() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(connectionRequestTimeoutMillis)
                .setConnectTimeout(connectTimeoutMillis)
                .setSocketTimeout(readTimeoutMillis)
                .build();
        var builder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
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
