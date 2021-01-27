package com.yoloho.training;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;

import com.google.common.io.CharStreams;
import com.google.common.io.LineProcessor;
import com.yoloho.enhanced.common.util.HttpClientUtil;
import com.yoloho.enhanced.common.util.StringUtil;

public class Main {
    private static final Pattern PAIR_PATTERN = Pattern.compile("([a-zA-Z]+)=[\"]?([^\",]+)[\"]?");
    private static class KeyInfo {
        private byte[] iv;
        private byte[] key;
        private String algorithm;
        private int keysize;
    }
    private static Map<String, String> ALGORITHM_MAP = new HashMap<>();
    static {
        ALGORITHM_MAP.put("AES", "AES/CBC/PKCS5PADDING");
    }
    
    private static <T> T doWithRetry(Callable<T> callable) throws Exception {
        int retryCount = 0;
        Exception lastException = null;
        while (retryCount < 10) {
            try {
                return callable.call();
            } catch (Exception e) {
                retryCount ++;
                lastException = e;
                System.out.println("下载文件失败，重试第" + retryCount + "次");
                Thread.sleep(1000);
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new RuntimeException();
    }
    
    public static boolean download(final String url, final File localFile) {
        HttpGet request = new HttpGet(url);
        try {
            return doWithRetry(new Callable<Boolean>() {

                @Override
                public Boolean call() throws Exception {
                    try (CloseableHttpResponse resp = HttpClientUtil.executeRequestDirect(request, 10000, "utf-8")) {
                        try (BufferedInputStream bis = new BufferedInputStream(resp.getEntity().getContent(), 1024 * 100)) {
                            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(localFile))) {
                                int cnt = 0;
                                byte[] buf = new byte[512];
                                do {
                                    cnt = bis.read(buf);
                                    if (cnt > 0) {
                                        bos.write(buf, 0, cnt);
                                    }
                                } while (cnt > 0);
                            }
                        } catch (MalformedURLException e) {
                            System.out.println("文件地址错误：" + e.getMessage());
                            return false;
                        }
                    }
                    return true;
                }
            });
        } catch (Exception e) {
            System.out.println("经过重试后，依然错误：" + e.getMessage());
            return false;
        }
    }
    
    private static String baseUrl(String url) {
        if (url.indexOf('?') > 0) {
            url = url.substring(0, url.indexOf('?'));
        }
        if (url.indexOf('/') > 0) {
            url = url.substring(0, url.lastIndexOf('/') + 1);
        }
        return url;
    }
    
    private static KeyInfo parseKey(String m3u8Url, String str) throws IOException {
        URL url = new URL(m3u8Url);
        KeyInfo keyInfo = new KeyInfo();
        Matcher m = PAIR_PATTERN.matcher(str);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2);
            switch (key) {
                case "IV":
                    keyInfo.iv = StringUtil.toBytes(val.substring(2));
                    break;
                case "METHOD": {
                    int pos = val.indexOf('-');
                    keyInfo.algorithm = val.substring(0, pos);
                    keyInfo.keysize = NumberUtils.toInt(val.substring(pos + 1));
                    break;
                }
                case "URI":
                    if (val.startsWith("/")) {
                        val = url.getProtocol() + "://" + url.getHost() + val;
                    }
                    System.out.println("获取加密key：" + val); 
                    CloseableHttpResponse resp = HttpClientUtil.executeRequestDirect(new HttpGet(val), 10000, "utf-8");
                    keyInfo.key = EntityUtils.toByteArray(resp.getEntity());
                    break;

                default:
                    break;
            }
        }
        return keyInfo;
    }
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("命令语法：java -jar m3u8-downloader.jar <m3u8文件url>");
            System.out.println("Usage：java -jar m3u8-downloader.jar <m3u8_url>");
            System.out.println();
            System.exit(1);
        }
        String urlStr = args[0];
        String baseUrl = baseUrl(urlStr);
        String m3u8Body = HttpClientUtil.getRequest(urlStr);
        List<String> fileList = new ArrayList<String>();
        HttpClientUtil.setCustomUserAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.5 Safari/605.1.15");
        Cipher cipher = null;
        try {
            cipher = CharStreams.readLines(new StringReader(m3u8Body), new LineProcessor<Cipher>() {
                private boolean urlNext = false;
                private Cipher cipher = null;
    
                @Override
                public boolean processLine(String line) throws IOException {
                    if (StringUtils.isEmpty(line)) return true;
                    if (line.startsWith("#EXT-X-KEY:")) {
                        line = line.substring("#EXT-X-KEY:".length());
                        KeyInfo keyInfo = parseKey(urlStr, line);
                        try {
                            cipher = Cipher.getInstance(ALGORITHM_MAP.get(keyInfo.algorithm));
                            SecretKeySpec sKeySpec = new SecretKeySpec(keyInfo.key, keyInfo.algorithm);
                            cipher.init(Cipher.DECRYPT_MODE, sKeySpec, new IvParameterSpec(keyInfo.iv));
                        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException e) {
                            e.printStackTrace();
                            System.out.println("解析加密信息出错");
                            System.exit(2);
                        }
                    } else if (line.startsWith("#EXTINF:")) {
                        urlNext = true;
                    } else if (urlNext) {
                        urlNext = false;
                        if (line.startsWith("http://") || line.startsWith("https://")) {
                            fileList.add(line);
                        } else {
                            fileList.add(baseUrl + line);
                        }
                    }
                    return true;
                }
    
                @Override
                public Cipher getResult() {
                    return cipher;
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("读取网址失败: " + e.getMessage());
            return;
        }
        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream("final.ts"))) {
            byte[] buf = new byte[512];
            for (int i = 0; i < fileList.size(); i ++) {
                File tmpFile = null;
                System.out.print("下载文件" + (i + 1) + "/" + fileList.size() + " ... ");
                try {
                    tmpFile = File.createTempFile("m3u8-downloader-", String.valueOf(i));
                    download(fileList.get(i), tmpFile);
                    try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(tmpFile))) {
                        while (true) {
                            int read = bis.read(buf);
                            if (read < 0) break;
                            if (cipher != null) {
                                byte[] data = cipher.update(buf, 0, read);
                                if (data != null) {
                                    bos.write(data);
                                }
                            } else {
                                bos.write(buf, 0, read);
                            }
                        }
                    }
                    if (cipher != null) {
                        byte[] data = cipher.doFinal();
                        if (data != null) {
                            bos.write(data);
                        }
                    }
                    System.out.println("下载完成");
                } catch (Exception e) {
                    System.out.println("下载文件出错，中断: " + e.getMessage());
                } finally {
                    if (tmpFile != null) tmpFile.delete();
                }
            }
        } catch (IOException e1) {
            System.out.println("文件写入失败: " + e1.getMessage());
        }
        System.out.println("文件已下载完成，文件名字 final.ts");
    }
}
