package com.aismarttube.app;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.localization.Localization;

public class NewPipeHelper {

    static {
        // NewPipe ইনিশিয়ালাইজ করা
        NewPipe.init(null, new Localization("bn", "IN"));
    }

    public static String getVideoTitle(String videoUrl) {
        try {
            StreamInfo streamInfo = StreamInfo.getInfo(videoUrl);
            return streamInfo.getName();
        } catch (Exception e) {
            return "ত্রুটি: " + e.getMessage();
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
