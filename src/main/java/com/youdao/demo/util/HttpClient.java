package com.youdao.demo.util;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.youdao.demo.model.PooledHttpResponse;
import org.apache.http.*;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.*;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeaderElementIterator;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.CodingErrorAction;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class HttpClient {

    private static final String CONTENT_JSON = "application/json; charset=utf-8";
    private static final String CONTENT_X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded; charset=utf-8";

    private static final int DEFAULT_POOL_MAX_TOTAL = 500;
    private static final int DEFAULT_POOL_MAX_PER_ROUTE = 50;

    /**
     * 这定义了通过网络与服务器建立连接的超时时间。
     * Httpclient包中通过一个异步线程去创建与服务器的socket连接，这就是该socket连接的超时时间，
     */
    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
    /**
     * 从连接池中获取连接的超时时间，假设：连接池中已经使用的连接数等于setMaxTotal，新来的线程在等待1500
     * 后超时，错误内容：org.apache.http.conn.ConnectionPoolTimeoutException: Timeout waiting for connection from pool
     */
    private static final int DEFAULT_CONNECT_REQUEST_TIMEOUT = 1500;
    /**
     * 从server获取数据超时
     * 指的是连接上一个url，获取response的返回等待时间，假设：url程序中存在阻塞、或者response
     * 返回的文件内容太大，在指定的时间内没有读完，则出现
     * java.net.SocketTimeoutException: Read timed out
     */
    private static final int DEFAULT_SOCKET_TIMEOUT = 5 * 1000;

    private static final int DEFAULT_KEEPALIVE_DURATION = 60 * 1000;

    private static final int DEFAULT_MAX_RETRY_TIMES = 1;

    private static volatile CloseableHttpClient httpClient = null;
    private static IdleConnectionMonitorThread idleThread = null;


    private HttpClient() {
    }

    private static CloseableHttpClient createHttpClient() {

        CloseableHttpClient client = null;
        PoolingHttpClientConnectionManager pcm = null;
        try {
            SSLContext sslContext = new SSLContextBuilder().build();
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs,
                                               String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] certs,
                                               String authType) {
                }
            }}, null);
            SSLConnectionSocketFactory sslsfl = new SSLConnectionSocketFactory(sslContext, new String[]{"TLSv1"}, null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

            ConnectionSocketFactory socketFactory = PlainConnectionSocketFactory.getSocketFactory();
            Registry<ConnectionSocketFactory> registry = RegistryBuilder
                    .<ConnectionSocketFactory>create()
                    .register("http", socketFactory)
                    .register("https", new SSLConnectionSocketFactory(sslContext))
                    .build();

            //创建连接管理器
            pcm = new PoolingHttpClientConnectionManager(registry);
            // Create socket configuration
            SocketConfig socketConfig = SocketConfig.custom()
                    .setTcpNoDelay(true).build();
            pcm.setDefaultSocketConfig(socketConfig);
            // Create message constraints
            MessageConstraints messageConstraints = MessageConstraints.custom()
                    .setMaxHeaderCount(200).setMaxLineLength(2000).build();
            // Create connection configuration
            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setMalformedInputAction(CodingErrorAction.IGNORE)
                    .setUnmappableInputAction(CodingErrorAction.IGNORE)
                    .setCharset(Consts.UTF_8)
                    .setMessageConstraints(messageConstraints).build();
            pcm.setDefaultConnectionConfig(connectionConfig);
            pcm.setMaxTotal(DEFAULT_POOL_MAX_TOTAL);
            pcm.setDefaultMaxPerRoute(DEFAULT_POOL_MAX_PER_ROUTE);

            //RetryHandler
            HttpRequestRetryHandler httpRequestRetryHandler = new HttpRequestRetryHandler() {
                public boolean retryRequest(IOException e, int count, HttpContext httpContext) {
                    if (count >= DEFAULT_MAX_RETRY_TIMES) {
                        return false;
                    }
                    if (e instanceof NoHttpResponseException) {
                        //连接丢失，尝试重连
//                    // log.warn("NoHttpResponseException,retry count:{}", count);
                        return true;
                    }
                    if (e instanceof SocketTimeoutException) {
                        //连接超时
//                    // log.warn("SocketTimeoutException,retry count:{}", count);
                        return true;
                    }
                    if (e instanceof InterruptedIOException) {
                        return false;
                    }
                    if (e instanceof UnknownHostException) {
                        return false;
                    }

                    HttpClientContext clientContext = HttpClientContext.adapt(httpContext);
                    HttpRequest httpRequest = clientContext.getRequest();
                    //请求幂等，重试
                    if (!(httpRequest instanceof HttpEntityEnclosingRequest)) {
//                    // log.warn("httpRequest,retry count:{}", count);
                        return true;
                    }
                    return false;
                }
            };

            //KeepAliveStrategy
            ConnectionKeepAliveStrategy keepAliveStrategy = new DefaultConnectionKeepAliveStrategy() {
                @Override
                public long getKeepAliveDuration(final HttpResponse response, final HttpContext context) {
                    HeaderElementIterator it = new BasicHeaderElementIterator
                            (response.headerIterator(HTTP.CONN_KEEP_ALIVE));
                    while (it.hasNext()) {
                        HeaderElement he = it.nextElement();
                        String param = he.getName();
                        String value = he.getValue();
                        if (value != null && "timeout".equalsIgnoreCase(param)) {
                            return Long.parseLong(value) * 1000;
                        }
                    }
                    return DEFAULT_KEEPALIVE_DURATION;
                }
            };


            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(DEFAULT_CONNECT_REQUEST_TIMEOUT)
                    .setConnectTimeout(DEFAULT_CONNECT_TIMEOUT)
                    .setSocketTimeout(DEFAULT_SOCKET_TIMEOUT)
                    .build();

            client = HttpClients.custom()
                    .setConnectionManager(pcm)
                    .setKeepAliveStrategy(keepAliveStrategy)
                    .setRetryHandler(httpRequestRetryHandler)
                    .setDefaultRequestConfig(requestConfig)
                    .setSSLSocketFactory(sslsfl)
                    .build();

        } catch (NoSuchAlgorithmException e) {
//            // log.error("SslContext builder init. NoSuchAlgorithmException", e);
        } catch (KeyManagementException e) {
//            // log.error("SslContext builder init. KeyManagementException.", e);
        } catch (Exception e) {
//            // log.error("httpUtil init error.", e);
        }
        return client;
    }

    /**
     * 获取httpclient对象
     *
     * @return httpclient对象
     */
    public static CloseableHttpClient getHttpClient() {

        if (httpClient == null) {
            // 双重校验
            synchronized (HttpClient.class) {
                if (httpClient == null) {
                    httpClient = createHttpClient();
                }
            }
        }

        return httpClient;
    }

    public static PooledHttpResponse doGet(String url) {
        return doGet(url, Collections.EMPTY_MAP, Collections.EMPTY_MAP);
    }

    public static PooledHttpResponse doGet(String url, Map<String, Object> params) {
        return doGet(url, Collections.EMPTY_MAP, params);
    }

    // TODO 做sonos数据埋点用，后期删除
    private volatile static long reqBadCount;
    public static PooledHttpResponse doSonosGet(String url, Map<String, String> headers, Map<String, Object> params) {

        httpClient = getHttpClient();
        /**
         * 构建GET请求头
         */
        String apiUrl = getUrlWithParams(url, params);
        HttpGet httpGet = new HttpGet(apiUrl);

        /**
         * 设置header信息
         */
        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpGet.addHeader(entry.getKey(), entry.getValue());
            }
        }

        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(httpGet);

            return getPooledResponseResult(response);
        } catch (IOException e) {
            reqBadCount++;
//            // log.warn("httpClient execute get url:{}", url, e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
//                    // log.error("response close url:{}", url, e);
                }
            }
        }
        return null;
    }

    public static long getReqBadCount() {
        return reqBadCount;
    }

    public static PooledHttpResponse doGet(String url, Map<String, String> headers, Map<String, Object> params) {

        httpClient = getHttpClient();
        /**
         * 构建GET请求头
         */
        String apiUrl = getUrlWithParams(url, params);
        HttpGet httpGet = new HttpGet(apiUrl);

        /**
         * 设置header信息
         */
        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpGet.addHeader(entry.getKey(), entry.getValue());
            }
        }

        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(httpGet);

            return getPooledResponseResult(response);
        } catch (IOException e) {
            // log.error("httpClient execute get url:{}", url, e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    // log.error("response close url:{}", url, e);
                }
            }
        }
        return null;
    }

    /**
     * @param response
     * @return
     */
    private static PooledHttpResponse getPooledResponseResult(CloseableHttpResponse response) {
        if (response == null || response.getStatusLine() == null) {
            return null;
        }
        PooledHttpResponse pooledHttpResponse = new PooledHttpResponse();
        int statusCode = response.getStatusLine().getStatusCode();
        pooledHttpResponse.setHttpCode(statusCode);
        Header[] headers = response.getAllHeaders();
        if (headers != null) {
            Map<String, String> headerMap = new HashMap(headers.length*2);
            for (int i = 0; i < headers.length; i++) {
                Header header = headers[i];
                headerMap.put(header.getName(), header.getValue());
            }
            pooledHttpResponse.setHeader(headerMap);
        }
        HttpEntity entityRes = response.getEntity();
        if (entityRes != null) {
            try {
                pooledHttpResponse.setResponse(EntityUtils.toString(entityRes, "UTF-8"));
                return pooledHttpResponse;
            } catch (IOException e) {
                // log.error("entityUtils toString parse exception", e);
            }
        }
        return pooledHttpResponse;
    }

    /**
     * @param response
     * @return
     */
    private static String getResponseResult(CloseableHttpResponse response) {
        if (response == null || response.getStatusLine() == null) {
            return null;
        }
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_OK) {
            HttpEntity entityRes = response.getEntity();
            if (entityRes != null) {
                try {
                    return EntityUtils.toString(entityRes, "UTF-8");
                } catch (IOException e) {
                    // log.error("entityUtils toString parse exception", e);
                }
            }
        } else {
            // log.warn("the response code is not success,statusCode:{}", statusCode);
        }
        return null;
    }

    public static PooledHttpResponse doPost(String apiUrl, Map<String, Object> params) {
        return doPost(apiUrl, Collections.EMPTY_MAP, params);
    }

    public static PooledHttpResponse doPost(String apiUrl, Map<String, String> headers, Object params) {
        return doPost(apiUrl, headers, params, true);
    }

    public static PooledHttpResponse doPost(String apiUrl, Map<String, String> headers, Object params, boolean paramsFormatStringEntity) {
        return doPost(apiUrl, Collections.EMPTY_MAP, headers, params, paramsFormatStringEntity);
    }

    public static PooledHttpResponse doPost(String apiUrl, Map<String, String> queryParams, Map<String, String> headers, Map<String, Object> params) {
        return doPost(apiUrl, queryParams, headers, params, true);
    }

    public static PooledHttpResponse doPost(String apiUrl, Map<String, String> queryParams, Map<String, String> headers, Object params, boolean paramsFormatStringEntity) {
        HttpPost httpPost = null;
        if (null != queryParams && queryParams.size() > 0) {
            try {
                URIBuilder uriBuilder = new URIBuilder(apiUrl);
                for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                    uriBuilder.addParameter(entry.getKey(), entry.getValue());
                }
                String uri = uriBuilder.build().toString();
                httpPost = new HttpPost(uri);
            } catch (Exception e) {
                // log.error("error when process http post url:{}", apiUrl, e);
                return null;
            }
        } else {
            httpPost = new HttpPost(apiUrl);
        }

        httpClient = getHttpClient();

        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpPost.addHeader(entry.getKey(), entry.getValue());
            }
        }

        HttpEntity entityReq;
        if (paramsFormatStringEntity) {
            httpPost.addHeader(HTTP.CONTENT_TYPE, CONTENT_JSON);
            entityReq = getUrlEncodedFormStringEntity(params);
        } else {
            httpPost.addHeader(HTTP.CONTENT_TYPE, CONTENT_X_WWW_FORM_URLENCODED);
            entityReq = getUrlEncodedFormNameValuePairEntity(params);
        }
        httpPost.setEntity(entityReq);

        long beginTime = System.currentTimeMillis();

        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(httpPost);

            return getPooledResponseResult(response);
        } catch (IOException e) {
            // log.warn("httpClient execute post cost:{} ms, url:{}", System.currentTimeMillis() - beginTime, apiUrl, e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    // log.error("response close url:{}", apiUrl, e);
                }
            }
        }
        return null;
    }

    public static String doPut(String apiUrl, Map<String, Object> params) {
        return doPut(apiUrl, Collections.EMPTY_MAP, params);
    }

    public static String doPut(String apiUrl, Map<String, String> headers, Map<String, Object> params) {
        return doPut(apiUrl, headers, params, true);
    }

    public static String doPut(String apiUrl, Map<String, String> headers, Map<String, Object> params, boolean paramsFormatStringEntity) {
        return doPut(apiUrl, Collections.EMPTY_MAP, headers, params, paramsFormatStringEntity);
    }

    public static String doPut(String apiUrl, Map<String, String> queryParams, Map<String, String> headers, Map<String, Object> params) {
        return doPut(apiUrl, queryParams, headers, params, true);
    }

    public static String doPut(String apiUrl, Map<String, String> queryParams, Map<String, String> headers, Map<String, Object> params, boolean paramsFormatStringEntity) {
        HttpPut httpPut = null;
        if (null != queryParams && queryParams.size() > 0) {
            try {
                URIBuilder uriBuilder = new URIBuilder(apiUrl);
                for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                    uriBuilder.addParameter(entry.getKey(), entry.getValue());
                }
                String uri = uriBuilder.build().toString();
                httpPut = new HttpPut(uri);
            } catch (Exception e) {
                // log.error("error when process http put url:{},exception:{}", apiUrl, e);
                return null;
            }
        } else {
            httpPut = new HttpPut(apiUrl);
        }

        httpClient = getHttpClient();

        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpPut.addHeader(entry.getKey(), entry.getValue());
            }
        }

        HttpEntity entityReq;
        if (paramsFormatStringEntity) {
            httpPut.addHeader(HTTP.CONTENT_TYPE, CONTENT_JSON);
            entityReq = getUrlEncodedFormStringEntity(params);
        } else {
            httpPut.addHeader(HTTP.CONTENT_TYPE, CONTENT_X_WWW_FORM_URLENCODED);
            entityReq = getUrlEncodedFormNameValuePairEntity(params);
        }
        httpPut.setEntity(entityReq);

        CloseableHttpResponse response = null;
        try {
            response = httpClient.execute(httpPut);

            return getResponseResult(response);
        } catch (IOException e) {
            // log.error("httpClient execute put url:{},exception:{}", apiUrl, e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    // log.error("response close url:{},exception:{}", apiUrl, e);
                }
            }
        }
        return null;
    }

    private static HttpEntity getUrlEncodedFormStringEntity(Object params) {
        if (params == null) {
            return null;
        }
        if (params instanceof Map) {
            Map<String, Object> paramValues = (Map) params;
            JSONObject pairList = new JSONObject();
            for (Map.Entry<String, Object> entry : paramValues.entrySet()) {
                if (null != entry.getValue()) {
                    pairList.put(entry.getKey(), entry.getValue());
                }
            }
            StringEntity entity = new StringEntity(pairList.toString(), Consts.UTF_8);
            entity.setContentType("application/json");
            return entity;
        } else if (params instanceof List) {
            List<Object> paramsValues = (List) params;
            JSONArray pairList = new JSONArray();
            for (Object param : paramsValues) {
                if (param != null) {
                    pairList.add(param);
                }
            }
            StringEntity entity = new StringEntity(pairList.toString(), Consts.UTF_8);
            entity.setContentType("application/json");
            return entity;
        } else {
            StringEntity entity = new StringEntity(params.toString(), Consts.UTF_8);
            entity.setContentType("application/json");
            return entity;
        }
    }

    private static HttpEntity getUrlEncodedFormNameValuePairEntity(Object params) {
        if (params == null) {
            return null;
        }
        if (params instanceof Map) {
            Map<String, Object> paramValues = (Map) params;
            List<NameValuePair> nameValuePairList = new ArrayList();
            for (Map.Entry<String, Object> entry : paramValues.entrySet()) {
                String value;
                Object obj = entry.getValue();
                if (obj == null) {
                    continue;
                }
                if (!(obj instanceof String)) {
                    value = JSONObject.toJSONString(obj);
                } else {
                    value = (String) obj;
                }
                nameValuePairList.add(new BasicNameValuePair(entry.getKey(), value));
            }
            return new UrlEncodedFormEntity(nameValuePairList, Consts.UTF_8);
        } else {
            throw new IllegalArgumentException("params not support, params:" + params.toString());
        }
    }

    private static String getUrlWithParams(String url, Map<String, Object> params) {
        if (null == params) {
            return url;
        }
        boolean first = true;
        StringBuilder sb = new StringBuilder(url);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            char ch = '&';
            if (first == true) {
                ch = '?';
                first = false;
            }
            // value可能为空
            String value = "";
            if(null != entry.getValue()){
                value = entry.getValue().toString();
            }

            try {
                String sval = URLEncoder.encode(value, "UTF-8");
                sb.append(ch).append(entry.getKey()).append("=").append(sval);
            } catch (UnsupportedEncodingException e) {
                // log.error("unsupportedEncodingException exception", e);
            }
        }
        return sb.toString();
    }


    public static void shutdown() {
        if (idleThread != null) {
            idleThread.shutdown();
        }
    }


    /**
     * 清理线程, 定期主动处理过期/空闲连接
     * 当流量为0时, 你会发现存在处于ClOSE_WAIT的连接. 由于httpclient清理过期/被动关闭的socket,是采用懒惰清理的策略.
     * 它是在连接从连接池取出使用的时候, 检测状态并做相应处理. 如果没有流量, 那这些socket将一直处于CLOSE_WAIT(半连接的状态), 系统资源被浪费
     */
    private static class IdleConnectionMonitorThread extends Thread {

        private final HttpClientConnectionManager connMgr;
        private volatile boolean exitFlag = false;

        public IdleConnectionMonitorThread(HttpClientConnectionManager connMgr) {
            this.connMgr = connMgr;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                while (!exitFlag) {
                    synchronized (this) {
                        wait(5000);
                        // Close expired connections，停止过期的连接
                        connMgr.closeExpiredConnections();
                        // Optionally, close connections that have been idle longer than 30 sec
                        connMgr.closeIdleConnections(30, TimeUnit.SECONDS);
                    }
                }
            } catch (InterruptedException ex) {
                // terminate
            }
        }

        public void shutdown() {
            exitFlag = true;
            synchronized (this) {
                notifyAll(); // 让run方法不再wait
            }
        }
    }
}
