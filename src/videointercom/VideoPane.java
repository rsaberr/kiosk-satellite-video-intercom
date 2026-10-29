// SPDX-License-Identifier: Apache-2.0
package videointercom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * A live picture on black, as large as fits without stretching, with a message in the middle until the first frame.
 * A TextureView, which KS overlays support. Main thread only.
 */
final class VideoPane extends FrameLayout {
    interface SurfaceListener {
        void ready(Surface surface);
        void gone(Surface surface);
    }

    private final FitTextureView picture;
    private final TextView message;
    private SurfaceListener listener;
    private Surface surface;
    private boolean framesArrived;
    private String text;

    VideoPane(Context context, KsTheme theme, float textSp) {
        super(context);
        setBackgroundColor(Color.BLACK);
        picture = new FitTextureView(context);
        picture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                surface = new Surface(texture);
                if (listener != null) listener.ready(surface);
            }

            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}

            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                Surface gone = surface;
                surface = null;
                if (listener != null && gone != null) listener.gone(gone);
                if (gone != null) gone.release();
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (!framesArrived) {
                    framesArrived = true;
                    refreshMessage();
                }
            }
        });
        addView(picture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        message = new TextView(context);
        message.setTextColor(0xB3FFFFFF);
        message.setTextSize(textSp);
        message.setTypeface(theme.typeface(400));
        message.setGravity(Gravity.CENTER);
        int pad = theme.px(12);
        message.setPadding(pad, pad, pad, pad);
        addView(message, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    void setSurfaceListener(SurfaceListener next) {
        listener = next;
        if (surface != null && next != null) next.ready(surface);
    }

    /** Mirrored, as people expect to see themselves. */
    void setMirrored(boolean mirrored) {
        picture.setScaleX(mirrored ? -1f : 1f);
    }

    void setVideoSize(int width, int height) {
        picture.setVideoSize(width, height);
    }

    /** What to say until there is a picture, or an error after it; null for nothing. */
    void setMessage(String next) {
        text = next;
        refreshMessage();
    }

    private void refreshMessage() {
        boolean error = text != null && text.startsWith("No ");
        boolean show = text != null && (!framesArrived || error);
        message.setText(show ? text : "");
        message.setVisibility(show ? VISIBLE : GONE);
    }

    /** A TextureView that keeps the picture's shape: as large as fits, centered. */
    private static final class FitTextureView extends TextureView {
        private int videoWidth, videoHeight;

        FitTextureView(Context context) {
            super(context);
        }

        void setVideoSize(int width, int height) {
            if (width <= 0 || height <= 0 || (width == videoWidth && height == videoHeight)) return;
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
                if ((long) width * videoHeight > (long) height * videoWidth) width = (int) ((long) height * videoWidth / videoHeight);
                else height = (int) ((long) width * videoHeight / videoWidth);
            }
            setMeasuredDimension(width, height);
        }
    }
}
