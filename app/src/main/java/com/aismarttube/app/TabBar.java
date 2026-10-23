package com.aismarttube.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;

/** The always-visible bottom tabs (Home, Subscriptions, Radio, You) of the native screens. */
final class TabBar {

    static final int HOME = 0, SUBS = 1, RADIO = 2, YOU = 3;
    private static final int[] IDS = {R.id.tabHome, R.id.tabSubs, R.id.tabRadio, R.id.tabYou};
    private static final String[] NAMES = {"হোম", "সাবস্ক্রিপশন", "রেডিও", "আপনি"};

    private TabBar() {}

    static void setup(final Activity a, final int current) {
        for (int i = 0; i < 4; i++) {
            Button b = a.findViewById(IDS[i]);
            if (b == null) continue;
            final int to = i;
            boolean on = i == current;
            b.setText(NAMES[i]);
            b.setTextColor(on ? 0xFFFFFFFF : 0xFFA4BCC0);
            b.setBackgroundColor(on ? 0xFF1B4450 : 0x00000000);
            b.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            b.setSelected(on);
            b.setContentDescription(NAMES[i] + " ট্যাব" + (on ? ", নির্বাচিত" : ""));
            b.setOnClickListener(v -> go(a, current, to));
        }
    }

    /** the screen for a tab; screens are moved to the front, never stacked twice, and nothing is closed
     *  (so radio / video keep playing underneath) */
    static Intent intentFor(Context c, int to) {
        Intent in;
        switch (to) {
            case HOME: in = new Intent(c, BrowseActivity.class); break;
            case SUBS: in = new Intent(c, SubsActivity.class); break;
            case YOU: in = new Intent(c, YouActivity.class); break;
            default: in = new Intent(c, RadioActivity.class); break;
        }
        return in.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static void go(Activity a, int cur, int to) {
        if (to == cur) return;
        a.startActivity(intentFor(a, to));
        a.overridePendingTransition(0, 0);
    }

    /** used by the web screens (radio / settings) for their tab buttons */
    static void open(Context c, String name) {
        int to = "subs".equals(name) ? SUBS : "you".equals(name) ? YOU : "radio".equals(name) ? RADIO : HOME;
        c.startActivity(intentFor(c, to));
    }
}
