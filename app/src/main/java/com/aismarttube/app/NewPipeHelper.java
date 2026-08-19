package com.aismarttube.app;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class NewPipeHelper {

    static {
        NewPipe.init(new RealDownloader(), new Localization("bn", "IN"));
    }

    static class RealDownloader extends Downloader {

        private static final String USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(request.url());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(request.httpMethod());
                connection.setConnectTimeout(25000);
                connection.setReadTimeout(25000);
                connection.setInstanceFollowRedirects(true);

                // আধুনিক ব্রাউজারের মতো হেডার (br বাদ দেওয়া হয়েছে)
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,bn;q=0.8");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
                connection.setRequestProperty("Accept-Encoding", "gzip, deflate");   // br বাদ দেওয়া হয়েছে
                connection.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"128\", \"Not;A=Brand\";v=\"24\", \"Google Chrome\";v=\"128\"");
                connection.setRequestProperty("Sec-Ch-Ua-Mobile", "?0");
                connection.setRequestProperty("Sec-Ch-Ua-Platform", "\"Windows\"");
                connection.setRequestProperty("Sec-Fetch-Dest", "document");
                connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
                connection.setRequestProperty("Sec-Fetch-Site", "none");
                connection.setRequestProperty("Sec-Fetch-User", "?1");
                connection.setRequestProperty("Upgrade-Insecure-Requests", "1");
                connection.setRequestProperty("Cache-Control", "max-age=0");

                // রিকোয়েস্ট থেকে আসা অতিরিক্ত হেডার যোগ করা
                Map<String, List<String>> headers = request.headers();
                if (headers != null) {
                    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                        for (String value : entry.getValue()) {
                            connection.addRequestProperty(entry.getKey(), value);
                        }
                    }
                }

                connection.connect();

                int responseCode = connection.getResponseCode();
                String responseMessage = connection.getResponseMessage();

                java.io.InputStream inputStream;
                if (responseCode >= 400) {
                    inputStream = connection.getErrorStream();
                } else {
                    inputStream = connection.getInputStream();
                }

                // GZIP সাপোর্ট
                String contentEncoding = connection.getContentEncoding();
                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                    inputStream = new GZIPInputStream(inputStream);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                StringBuilder responseBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
                reader.close();

                Map<String, List<String>> responseHeaders = connection.getHeaderFields();

                return new Response(
                        responseCode,
                        responseMessage,
                        responseHeaders,
                        responseBody.toString(),
                        request.url()
                );

            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    public static String getVideoInfo(String videoUrl) {
        try {
            StreamInfo streamInfo = StreamInfo.getInfo(videoUrl);
            StringBuilder info = new StringBuilder();
            info.append("শিরোনাম: ").append(streamInfo.getName()).append("\n\n");
            info.append("আপলোডার: ").append(streamInfo.getUploaderName()).append("\n\n");
            info.append("ভিউ: ").append(streamInfo.getViewCount()).append("\n");
            return info.toString();
        } catch (Exception e) {
            return "ত্রুটি হয়েছে: " + e.getMessage();
        }
    }
}
