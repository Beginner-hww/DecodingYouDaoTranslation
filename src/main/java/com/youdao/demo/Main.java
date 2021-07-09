package com.youdao.demo;

import com.alibaba.fastjson.JSON;
import com.youdao.demo.model.PooledHttpResponse;
import com.youdao.demo.util.HttpClient;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author hww
 * @date 2021/7/9
 */
public class Main {

    public static void main(String[] args) {
        // 翻译文本
        String text = "Hello, YouDao Translation";
        // 待翻译文本语言
        String from = "AUTO";
        // 翻译后文本语言
        String to = "AUTO";

        String url = "https://fanyi.youdao.com/translate_o?smartresult=dict&smartresult=rule";
        String client = "fanyideskweb";
        long ctime = System.currentTimeMillis();
        String salt = String.valueOf(System.currentTimeMillis() + (10 * Math.random()));
        String sign = md5(client + text + salt + "Y2FYu%TNSbMCxc3t2u^XT").toLowerCase();

        Map<String, Object> params = new HashMap();
        params.put("i", text);
        params.put("from", from);
        params.put("to", to);
        params.put("smartresult", "dict");
        params.put("client", client);
        params.put("salt", salt);
        params.put("sign", sign);
        params.put("doctype", "json");
        params.put("version", "2.1");
        params.put("keyfrom", "fanyi.web");
        params.put("action", "FY_BY_REALTlME");

        Map<String, String> header = new HashMap();

        header.put("Accept","application/json, text/javascript, */*; q=0.01");
        header.put("Referer","https://fanyi.youdao.com/");
        header.put("Cookie", "OUTFOX_SEARCH_USER_ID=-938999356@10.108.160.17; OUTFOX_SEARCH_USER_ID_NCOO=400658508.6922812; _ga=GA1.2.1487965154.1584958612; _ntes_nnid=e8d66244bd6b0df63f7392b3c2fc2e23,1589266441531; UM_distinctid=179220c124170e-045cf5dcf62401-d7e163f-1fa400-179220c12426af; JSESSIONID=aaaLT76SDYdZDaq9ZgkQx; ___rl__test__cookies=1625818519508" + String.valueOf(ctime));
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

        PooledHttpResponse pooledHttpResponse = HttpClient.doPost(url, header, params, false);

        System.out.println(JSON.toJSONString(pooledHttpResponse));
    }

    /**
     * 生成32位MD5摘要
     * @param string
     * @return
     */
    public static String md5(String string) {
        if(string == null){
            return null;
        }
        char hexDigits[] = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                'A', 'B', 'C', 'D', 'E', 'F'};
        byte[] btInput = string.getBytes();
        try{
            /** 获得MD5摘要算法的 MessageDigest 对象 */
            MessageDigest mdInst = MessageDigest.getInstance("MD5");
            /** 使用指定的字节更新摘要 */
            mdInst.update(btInput);
            /** 获得密文 */
            byte[] md = mdInst.digest();
            /** 把密文转换成十六进制的字符串形式 */
            int j = md.length;
            char str[] = new char[j * 2];
            int k = 0;
            for (byte byte0 : md) {
                str[k++] = hexDigits[byte0 >>> 4 & 0xf];
                str[k++] = hexDigits[byte0 & 0xf];
            }
            return new String(str);
        }catch( NoSuchAlgorithmException e){
            return null;
        }
    }
}
