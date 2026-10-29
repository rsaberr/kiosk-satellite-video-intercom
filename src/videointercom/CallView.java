// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * The full-screen call: the other kiosk's live picture, this kiosk's own camera small in a corner, the other kiosk's
 * name and the call's state on top, and the buttons for the moment at the bottom. Main thread only.
 */
final class CallView extends FrameLayout {
    interface Callback {
        void answer();
        void decline();
        void hangup();
        void toggleMute();
        /** A double tap or a long press on the picture: "double" or "long". */
        void gesture(String kind);
    }

    enum Buttons { NONE, INCOMING, OUTGOING, IN_CALL }

    final VideoPane remote;
    final VideoPane self;
    private final KsTheme theme;
    private final Callback callback;
    private final TextView title;
    private final TextView status;
    private final LinearLayout buttons;
    private Buttons shown;
    private boolean muted;

    CallView(Context context, KsTheme theme, Callback callback) {
        super(context);
        this.theme = theme;
        this.callback = callback;
        setBackgroundColor(Color.BLACK);

        remote = new VideoPane(context, theme, 18);
        addView(remote, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Kiosk Satellite's own gestures do not reach through this screen, so it has its own on the picture.
        GestureDetector gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent event) {
                callback.gesture("double");
                return true;
            }

            @Override public void onLongPress(MotionEvent event) {
                callback.gesture("long");
            }
        });
        remote.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(theme.px(24), theme.px(28), theme.px(24), theme.px(40));
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xB0000000, 0x00000000}));
        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(theme.typeface(500));
        title.setGravity(Gravity.CENTER);
        status = new TextView(context);
        status.setTextColor(0xDDFFFFFF);
        status.setTextSize(17);
        status.setTypeface(theme.typeface(400));
        status.setGravity(Gravity.CENTER);
        top.addView(title);
        top.addView(status);
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // This kiosk's own camera, small, above the bottom buttons; a thin frame keeps it apart from the picture.
        self = new VideoPane(context, theme, 12);
        self.setMirrored(true);
        int frame = theme.px(2);
        self.setPadding(frame, frame, frame, frame);
        GradientDrawable border = new GradientDrawable();
        border.setColor(Color.BLACK);
        border.setStroke(frame, 0x99FFFFFF);
        self.setBackground(border);
        LayoutParams selfParams = new LayoutParams(theme.px(200), theme.px(150), Gravity.BOTTOM | Gravity.END);
        selfParams.setMargins(0, 0, theme.px(20), theme.px(130));
        addView(self, selfParams);

        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(theme.px(16), theme.px(40), theme.px(16), theme.px(36));
        buttons.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xB0000000, 0x00000000}));
        addView(buttons, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
    }

    void show(String name, String state, Buttons which, boolean muted, boolean showSelf) {
        title.setText(name);
        status.setText(state);
        self.setVisibility(showSelf ? VISIBLE : GONE);
        if (which != shown || muted != this.muted) {
            this.muted = muted;
            setButtons(which);
        }
    }

    private void setButtons(Buttons which) {
        shown = which;
        buttons.removeAllViews();
        switch (which) {
            case INCOMING:
                buttons.addView(button(theme, getContext(), "Decline", 0xFFC62828, v -> callback.decline()));
                buttons.addView(button(theme, getContext(), "Answer", 0xFF2E7D32, v -> callback.answer()));
                break;
            case OUTGOING:
                buttons.addView(button(theme, getContext(), "Cancel", 0xFFC62828, v -> callback.hangup()));
                break;
            case IN_CALL:
                buttons.addView(button(theme, getContext(), muted ? "Unmute" : "Mute", muted ? 0xFFF9A825 : 0xFF424242, v -> callback.toggleMute()));
                buttons.addView(button(theme, getContext(), "End call", 0xFFC62828, v -> callback.hangup()));
                break;
            default:
                break;
        }
    }

    /** A large rounded button in one color, shared by the call and home screens. */
    static TextView button(KsTheme theme, Context context, String label, int color, View.OnClickListener click) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(20);
        view.setTypeface(theme.typeface(500));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(theme.px(150));
        view.setPadding(theme.px(28), theme.px(18), theme.px(28), theme.px(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(theme.px(40));
        view.setBackground(background);
        view.setClickable(true);
        view.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(theme.px(14), 0, theme.px(14), 0);
        view.setLayoutParams(params);
        return view;
    }
}
