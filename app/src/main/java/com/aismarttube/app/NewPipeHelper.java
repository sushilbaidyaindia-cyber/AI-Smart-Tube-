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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NewPipeHelper {

    static {
        NewPipe.init(new RealDownloader(), new Localization("bn", "IN"));
    }

    static class RealDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(request.url());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(request.httpMethod());
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);

                // হেডার সেট করা
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

                BufferedReader reader;
                if (responseCode >= 400) {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                }

                StringBuilder responseBody = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
                reader.close();

                Map<String, List<String>> responseHeaders = connection.getHeaderFields();

                return new Response(responseCode, responseMessage, responseHeaders, responseBody.toString(), request.url());

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
            return "ত্রুটি হয়েছে: " + e.getMessage();
        }
    }
}
