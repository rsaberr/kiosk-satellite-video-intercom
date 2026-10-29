// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The Video call screen: this kiosk's own camera as a preview, and a large button for every kiosk that can be called.
 * Side by side in landscape, stacked in portrait. Main thread only.
 */
final class HomeView extends LinearLayout {
    interface Callback {
        void call(int index);
        void close();
    }

    final VideoPane self;
    private final KsTheme theme;
    private final Callback callback;
    private final LinearLayout list;

    HomeView(Context context, KsTheme theme, Callback callback) {
        super(context);
        this.theme = theme;
        this.callback = callback;
        setBackgroundColor(0xFF101214);
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setOrientation(landscape ? HORIZONTAL : VERTICAL);

        FrameLayout preview = new FrameLayout(context);
        self = new VideoPane(context, theme, 18);
        self.setMirrored(true);
        preview.addView(self, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView you = new TextView(context);
        you.setText("You");
        you.setTextColor(Color.WHITE);
        you.setTextSize(15);
        you.setTypeface(theme.typeface(500));
        int pad = theme.px(10);
        you.setPadding(theme.px(14), pad / 2, theme.px(14), pad / 2);
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x80000000);
        chip.setCornerRadius(theme.px(20));
        you.setBackground(chip);
        FrameLayout.LayoutParams youParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        youParams.setMargins(theme.px(16), theme.px(16), 0, 0);
        preview.addView(you, youParams);
        addView(preview, landscape ? new LayoutParams(0, LayoutParams.MATCH_PARENT, 3) : new LayoutParams(LayoutParams.MATCH_PARENT, 0, 3));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(VERTICAL);
        int inset = theme.px(24);
        panel.setPadding(inset, inset, inset, inset);
        TextView title = new TextView(context);
        title.setText("Video call");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(theme.typeface(500));
        panel.addView(title);
        TextView hint = new TextView(context);
        hint.setText("Who do you want to call?");
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(16);
        hint.setTypeface(theme.typeface(400));
        hint.setPadding(0, theme.px(4), 0, theme.px(16));
        panel.addView(hint);

        ScrollView scroll = new ScrollView(context);
        list = new LinearLayout(context);
        list.setOrientation(VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        TextView close = CallView.button(theme, context, "Close", 0xFF424242, v -> callback.close());
        LayoutParams closeParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(0, theme.px(12), 0, 0);
        panel.addView(close, closeParams);
        addView(panel, landscape ? new LayoutParams(0, LayoutParams.MATCH_PARENT, 2) : new LayoutParams(LayoutParams.MATCH_PARENT, 0, 2));
        setKiosks(java.util.Collections.<String>emptyList(), true);
    }

    /** The kiosks to offer; searching shows that the list may still grow. */
    void setKiosks(List<String> names, boolean searching) {
        list.removeAllViews();
        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            TextView row = CallView.button(theme, getContext(), names.get(i), 0xFF2E7D32, v -> callback.call(index));
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.setMargins(0, theme.px(6), 0, theme.px(6));
            list.addView(row, params);
        }
        if (names.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(searching ? "Looking for kiosks…" : "No other kiosks found. Install Video Intercom on them with the same intercom key.");
            empty.setTextColor(0xB3FFFFFF);
            empty.setTextSize(16);
            empty.setTypeface(theme.typeface(400));
            list.addView(empty);
        }
    }
}
