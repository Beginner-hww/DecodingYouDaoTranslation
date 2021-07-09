package com.youdao.demo.model;

import java.util.Map;

/**
 * @author : yifeng.jin
 * @Version : v1.0
 * @Description :HttpClient response
 * @Date : 2019-12-18 15:04
 * Copyright (C) : Lumi United Technology Co., Ltd
 */
public class PooledHttpResponse {

    private int httpCode;
    private Map<String, String> header;

    private String response;

    public int getHttpCode() {
        return httpCode;
    }

    public void setHttpCode(int httpCode) {
        this.httpCode = httpCode;
    }

    public Map<String, String> getHeader() {
        return header;
    }

    public void setHeader(Map<String, String> header) {
        this.header = header;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }
}
