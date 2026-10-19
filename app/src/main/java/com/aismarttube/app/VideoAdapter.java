package com.aismarttube.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/**
 * One focusable row per video — TalkBack reads a single contentDescription.
 * Like the Radio list: simple rows, not a heavy WebView tree.
 */
public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VH> {

    public interface Listener {
        void onOpen(VideoItem item, int position);
    }

    private final List<VideoItem> data = new ArrayList<>();
    private final Listener listener;

    public VideoAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    public void submit(List<VideoItem> items) {
        data.clear();
        if (items != null) data.addAll(items);
        notifyDataSetChanged();
    }

    public void append(List<VideoItem> items) {
        if (items == null || items.isEmpty()) return;
        int start = data.size();
        data.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_video_card, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        VideoItem it = data.get(position);
        h.url = it.url;
        h.title.setText(it.title.isEmpty() ? "ভিডিও" : it.title);
        String meta = it.uploader;
        if (it.duration > 0) {
            meta = (meta.isEmpty() ? "" : meta + " · ") + formatDur(it.duration);
        }
        h.meta.setText(meta);
        h.itemView.setContentDescription(it.talkBackLabel());
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(it, h.getBindingAdapterPosition());
        });

        // avatar letter
        String letter = it.uploader.isEmpty() ? "?" : it.uploader.substring(0, 1).toUpperCase();
        h.avatar.setText(letter);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(colorFor(it.uploader));
        h.avatar.setBackground(bg);

        if (it.thumb != null && it.thumb.startsWith("http")) {
            Glide.with(h.thumb.getContext())
                    .load(it.thumb)
                    .centerCrop()
                    .override(640, 360)
                    .into(h.thumb);
        } else {
            Glide.with(h.thumb.getContext()).clear(h.thumb);
            h.thumb.setImageDrawable(null);
            h.thumb.setBackgroundColor(0xFF102229);
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title, meta, avatar;
        String url = "";

        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            avatar = v.findViewById(R.id.avatar);
            // Important for TalkBack: one action on the whole row
            v.setClickable(true);
            v.setFocusable(true);
            // TalkBack landed on this card -> start loading its stream in the background
            v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                        NativeFeedLoader.prefetch(url);
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }
    }

    private static String formatDur(long sec) {
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + ":" + two(m) + ":" + two(s);
        return m + ":" + two(s);
    }

    private static String two(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private static int colorFor(String name) {
        int h = 0;
        String s = name == null ? "?" : name;
        for (int i = 0; i < s.length(); i++) h = (h * 31 + s.charAt(i)) % 360;
        float[] hsv = new float[]{h, 0.42f, 0.34f};
        return Color.HSVToColor(hsv);
    }
}
