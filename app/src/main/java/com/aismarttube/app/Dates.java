package com.aismarttube.app;

import java.util.Calendar;

/** day grouping helpers shared by the history lists */
final class Dates {

    private Dates() {}

    private static final String[] MONTHS = {"জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
            "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"};

    static String bn(String t) {
        String d = "০১২৩৪৫৬৭৮৯";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            s.append(ch >= '0' && ch <= '9' ? d.charAt(ch - '0') : ch);
        }
        return s.toString();
    }

    /** midnight (local) of the day of t; 0 when t is unknown */
    static long dayKey(long t) {
        if (t <= 0) return 0;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String dayLabel(long dk) {
        if (dk == 0) return "আগের";
        long today = dayKey(System.currentTimeMillis());
        if (dk == today) return "আজ";
        if (dk == dayKey(today - 12 * 3600_000L)) return "গতকাল";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dk);
        return bn(c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR));
    }
}
