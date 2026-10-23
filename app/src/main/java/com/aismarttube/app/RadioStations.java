package com.aismarttube.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The Bengali All India Radio stations (main link first, backups after) + the user's own stations. */
final class RadioStations {

    private RadioStations() {}

    static final class Station {
        final String group, name;
        final String[] urls;
        final boolean custom;
        Station(String group, String name, String[] urls, boolean custom) {
            this.group = group; this.name = name; this.urls = urls; this.custom = custom;
        }
    }

    private static final Station[] BUILTIN = {
        new Station("কলকাতা", "আকাশবাণী কলকাতা গীতাঞ্জলি", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol01/air065_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio055/hlspbaudio055_Auto.m3u8"}, false),
        new Station("কলকাতা", "আকাশবাণী কলকাতা সঞ্চয়িতা", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol02/air066_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio056/hlspbaudio056_Auto.m3u8"}, false),
        new Station("কলকাতা", "আকাশবাণী মৈত্রী", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol03/air067_Auto.m3u8"}, false),
        new Station("কলকাতা", "আকাশবাণী বাংলা", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol07/air071_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio137/hlspbaudio137_Auto.m3u8"}, false),
        new Station("কলকাতা", "এফএম গোল্ড কলকাতা", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol06/air070_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio057/hlspbaudio057_Auto.m3u8"}, false),
        new Station("কলকাতা", "এফএম রেইনবো কলকাতা", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol05/air069_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio058/hlspbaudio058_Auto.m3u8"}, false),
        new Station("কলকাতা", "বিবিধ ভারতী কলকাতা", new String[]{"https://d3r9rhourdc02u.cloudfront.net/assets/radio/airkol04/air068_Auto.m3u8", "https://airhlspush.pc.cdn.bitgravity.com/httppush/hlspbaudio275/hlspbaudio275_Auto.m3u8"}, false),
        new Station("পশ্চিমবঙ্গের অন্যান্য কেন্দ্র", "আকাশবাণী শিলিগুড়ি", new String[]{"https://radio.wavespb.com/live/165856ba98ca031a/165856ba98ca031a.m3u8"}, false),
        new Station("পশ্চিমবঙ্গের অন্যান্য কেন্দ্র", "আকাশবাণী কার্শিয়াং", new String[]{"https://radio.wavespb.com/live/1f781b48497e67d3/1f781b48497e67d3.m3u8"}, false),
        new Station("পশ্চিমবঙ্গের অন্যান্য কেন্দ্র", "আকাশবাণী মুর্শিদাবাদ", new String[]{"https://air.pc.cdn.bitgravity.com/air/live/pbaudio169/playlist.m3u8"}, false),
        new Station("পশ্চিমবঙ্গের অন্যান্য কেন্দ্র", "আকাশবাণী শান্তিনিকেতন", new String[]{"https://radio.wavespb.com/live/66249dfecaf80241/66249dfecaf80241.m3u8"}, false),
        new Station("ত্রিপুরা ও আসাম (বাংলা অনুষ্ঠান)", "আকাশবাণী আগরতলা", new String[]{"https://radio.wavespb.com/live/8e8696f98500ad60/8e8696f98500ad60.m3u8"}, false),
        new Station("ত্রিপুরা ও আসাম (বাংলা অনুষ্ঠান)", "এফএম উজ্জয়ন্ত আগরতলা", new String[]{"https://radio.wavespb.com/live/d65912ef9829b8c9/d65912ef9829b8c9.m3u8"}, false),
        new Station("ত্রিপুরা ও আসাম (বাংলা অনুষ্ঠান)", "আকাশবাণী বেলোনিয়া", new String[]{"https://radio.wavespb.com/live/00ff79c493fe8353/00ff79c493fe8353.m3u8"}, false),
        new Station("ত্রিপুরা ও আসাম (বাংলা অনুষ্ঠান)", "আকাশবাণী কৈলাসহর", new String[]{"https://radio.wavespb.com/live/c84f390b94a07703/c84f390b94a07703.m3u8"}, false),
        new Station("ত্রিপুরা ও আসাম (বাংলা অনুষ্ঠান)", "আকাশবাণী শিলচর", new String[]{"https://radio.wavespb.com/live/389a55127e012f97/389a55127e012f97.m3u8"}, false)
    };

    static List<Station> all(Context c) {
        List<Station> l = new ArrayList<>();
        for (Station s : BUILTIN) l.add(s);
        try {
            String raw = Store.get(c, "radios");
            JSONArray a = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                JSONArray u = o.optJSONArray("u");
                if (u == null || u.length() == 0) continue;
                String[] us = new String[u.length()];
                for (int k = 0; k < us.length; k++) us[k] = u.optString(k);
                l.add(new Station("আমার রেডিও", o.optString("n", "স্টেশন"), us, true));
            }
        } catch (Throwable ignored) {}
        return l;
    }

    static void addCustom(Context c, String name, String url) {
        try {
            String raw = Store.get(c, "radios");
            JSONArray a = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
            a.put(new JSONObject().put("n", name).put("u", new JSONArray().put(url)));
            Store.set(c, "radios", a.toString());
        } catch (Throwable ignored) {}
    }

    /** index among the user's own stations (0 = first custom one) */
    static void removeCustom(Context c, int customIndex) {
        try {
            String raw = Store.get(c, "radios");
            JSONArray a = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != customIndex) out.put(a.get(i));
            Store.set(c, "radios", out.toString());
        } catch (Throwable ignored) {}
    }

    static int builtinCount() { return BUILTIN.length; }
}
