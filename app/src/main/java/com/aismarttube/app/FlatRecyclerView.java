package com.aismarttube.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.recyclerview.widget.RecyclerView;

/**
 * A list that sits inside a ScrollView at full height (channel page, "up next" list).
 * It never scrolls by itself, so it must not tell TalkBack that it can: otherwise the two-finger scroll
 * gesture is handed to this list, which cannot move, instead of to the ScrollView around it.
 */
public class FlatRecyclerView extends RecyclerView {
    public FlatRecyclerView(Context c) { super(c); }
    public FlatRecyclerView(Context c, AttributeSet a) { super(c, a); }
    public FlatRecyclerView(Context c, AttributeSet a, int d) { super(c, a, d); }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(false);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
    }
}
