package com.aismarttube.app;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class NewPipeHelper {

    static {
        // সাধারণ Downloader দিয়ে NewPipe ইনিশিয়ালাইজ করা
        NewPipe.init(new SimpleDownloader(), new Localization("bn", "IN"));
    }

    // একটা সাধারণ Downloader ক্লাস
    static class SimpleDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            // এখানে আপাতত খালি রেসপন্স দিচ্ছি। পরে উন্নত করা হবে।
            return new Response(200, "OK", null, "", request.url());
        }
    }

    public static String getVideoInfo(String videoUrl) {
        try {
            StreamInfo streamInfo = StreamInfo.getInfo(videoUrl);
            StringBuilder info = new StringBuilder();
            info.append("শিরোনাম: ").append(streamInfo.getName()).append("\n");
            info.append("আপলোডার: ").append(streamInfo.getUploaderName()).append("\n");
            info.append("ভিউ: ").append(streamInfo.getViewCount()).append("\n");
            return info.toString();
        } catch (Exception e) {
            return "ত্রুটি হয়েছে: " + e.getMessage();
        }
    }
}
