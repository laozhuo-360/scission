package com.linye.netblock.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.linye.netblock.R;

/**
 * uiverse.io 风格胶囊总开关（参考 Galahhad/silent-robin-40 与 Nawsome/silent-owl-45）：
 * 关 = 白天跑道：浅灰轨道 + 虚线 + 白云装饰，白色圆钮 + 灰色 WiFi 图标
 * 开 = 深夜星空：深蓝渐变轨道 + 星星 + 月亮，白色圆钮 + 蓝色断网图标
 */
public class PillSwitchView extends View {

    private boolean checked = false;
    private float progress = 0f; // 0=关 1=开

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint decoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();

    private int dayTrack, nightTrackTop, nightTrackBottom;
    private int dayDeco, dayKnobIcon, nightKnobIcon;

    private ValueAnimator animator;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(boolean checked);
    }

    private OnCheckedChangeListener listener;

    public PillSwitchView(Context context) {
        super(context);
        init();
    }

    public PillSwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PillSwitchView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        dayTrack = ContextCompat.getColor(getContext(), R.color.pill_day_track);
        nightTrackTop = ContextCompat.getColor(getContext(), R.color.pill_night_top);
        nightTrackBottom = ContextCompat.getColor(getContext(), R.color.pill_night_bottom);
        dayDeco = ContextCompat.getColor(getContext(), R.color.pill_day_deco);
        dayKnobIcon = ContextCompat.getColor(getContext(), R.color.pill_icon_day);
        nightKnobIcon = ContextCompat.getColor(getContext(), R.color.pill_icon_night);

        knobPaint.setColor(Color.WHITE);
        knobShadowPaint.setColor(0x33000000);
        decoPaint.setColor(dayDeco);
        decoPaint.setStrokeCap(Paint.Cap.ROUND);
        starPaint.setColor(Color.WHITE);
        moonPaint.setColor(0xFFF6E7B4);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);

        setClickable(true);
        setFocusable(true);
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    public boolean isChecked() {
        return checked;
    }

    /** 不触发回调的静默同步（如外部状态刷新）。 */
    public void setCheckedSilent(boolean c) {
        checked = c;
        progress = c ? 1f : 0f;
        invalidate();
    }

    public void toggle() {
        setChecked(!checked, true);
    }

    public void setChecked(boolean c, boolean animate) {
        if (checked == c) return;
        checked = c;
        cancelAnim();
        if (!animate) {
            progress = c ? 1f : 0f;
            invalidate();
        } else {
            final float from = progress;
            final float to = c ? 1f : 0f;
            animator = ValueAnimator.ofFloat(from, to);
            animator.setDuration(380);
            animator.setInterpolator(new DecelerateInterpolator(1.6f));
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    progress = to;
                    invalidate();
                }
            });
            animator.start();
        }
        if (listener != null) {
            listener.onCheckedChanged(checked);
        }
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (w * 0.46f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;

        /* ---- 轨道 ---- */
        trackRect.set(0, 0, w, h);
        int top = blend(dayTrack, nightTrackTop, progress);
        int bottom = blend(dayTrack, nightTrackBottom, progress);
        Shader trackShader = new LinearGradient(
                0, 0, w, h,
                top, bottom, Shader.TileMode.CLAMP);
        trackPaint.setShader(trackShader);
        canvas.drawRoundRect(trackRect, r, r, trackPaint);

        /* ---- 轨道装饰 ---- */
        drawDayDeco(canvas, w, h);
        drawNightDeco(canvas, w, h);

        /* ---- 圆钮 ---- */
        float pad = h * 0.06f;
        float knobR = (h - pad * 2) / 2f;
        float cx = pad + knobR + (w - (pad + knobR) * 2) * progress;
        float cy = h / 2f;

        // 阴影
        knobShadowPaint.setAlpha((int) (0x30));
        canvas.drawCircle(cx, cy + knobR * 0.12f, knobR * 1.02f, knobShadowPaint);
        // 本体
        canvas.drawCircle(cx, cy, knobR, knobPaint);

        /* ---- 圆钮图标：WiFi（开 = 断开的 WiFi）---- */
        drawWifiIcon(canvas, cx, cy, knobR, progress);
    }

    /** 关：跑道虚线 + 云朵（随开启淡出） */
    private void drawDayDeco(Canvas canvas, float w, float h) {
        float alpha = 1f - progress;
        if (alpha <= 0.02f) return;
        decoPaint.setAlpha((int) (alpha * 255));
        decoPaint.setStyle(Paint.Style.STROKE);
        decoPaint.setStrokeWidth(h * 0.035f);

        // 三段跑道中线虚线（从右往左，滑块从右侧出发）
        float lineY = h * 0.62f;
        float seg = w * 0.055f;
        float gap = w * 0.035f;
        float x = w * 0.10f;
        for (int i = 0; i < 4 && x < w * 0.55f; i++) {
            canvas.drawLine(x, lineY, x + seg, lineY, decoPaint);
            x += seg + gap;
        }

        // 两朵小白云（圆点组合）
        float cloudAlpha = alpha * 255;
        decoPaint.setStyle(Paint.Style.FILL);
        decoPaint.setAlpha((int) cloudAlpha);
        drawCloud(canvas, w * 0.30f, h * 0.30f, h * 0.05f);
        drawCloud(canvas, w * 0.46f, h * 0.24f, h * 0.038f);
        decoPaint.setAlpha(255);
    }

    private void drawCloud(Canvas c, float cx, float cy, float r) {
        c.drawCircle(cx, cy, r, decoPaint);
        c.drawCircle(cx + r * 1.1f, cy + r * 0.25f, r * 0.75f, decoPaint);
        c.drawCircle(cx - r * 1.1f, cy + r * 0.3f, r * 0.65f, decoPaint);
    }

    /** 开：星星 + 月亮（随开启淡入） */
    private void drawNightDeco(Canvas canvas, float w, float h) {
        float alpha = progress;
        if (alpha <= 0.02f) return;
        starPaint.setAlpha((int) (alpha * 255));
        moonPaint.setAlpha((int) (alpha * 255));

        // 星星（模拟 BB-8 开关的星空）
        float[][] stars = {
                {0.14f, 0.30f, 2.4f}, {0.24f, 0.62f, 1.7f}, {0.34f, 0.26f, 2.0f},
                {0.44f, 0.55f, 1.5f}, {0.54f, 0.28f, 2.2f}, {0.64f, 0.60f, 1.6f},
                {0.74f, 0.30f, 1.9f}, {0.84f, 0.58f, 2.3f}
        };
        for (float[] s : stars) {
            canvas.drawCircle(w * s[0], h * s[1], s[2] * (h / 110f) * 2f, starPaint);
        }

        // 月亮（右上角，新月造型）
        float mx = w * 0.895f;
        float my = h * 0.32f;
        float mr = h * 0.11f;
        canvas.drawCircle(mx, my, mr, moonPaint);
        // 用轨道色遮出月牙
        int coverTop = blend(dayTrack, nightTrackTop, 1f);
        Paint cover = new Paint(Paint.ANTI_ALIAS_FLAG);
        cover.setColor(coverTop);
        cover.setAlpha((int) (progress * 255));
        canvas.drawCircle(mx - mr * 0.45f, my - mr * 0.25f, mr * 0.92f, cover);
    }

    /** WiFi 图标：progress<0.5 时实心弧=有网(灰)，>0.5 时弧+斜杠=断网(蓝) */
    private void drawWifiIcon(Canvas canvas, float cx, float cy, float knobR, float progress) {
        float size = knobR * 0.52f;
        iconPaint.setStrokeWidth(size * 0.22f);

        int iconColor = blendColor(dayKnobIcon, nightKnobIcon, progress);
        iconPaint.setColor(iconColor);

        // WiFi 弧（三条）圆心在下方
        float baseY = cy + size * 0.55f;
        float[] radii = {size * 0.42f, size * 0.86f, size * 1.30f};
        // 有网：画 2 条弧 + 点；断网：弧变暗 + 斜杠
        int arcs = progress < 0.5f ? 2 : 1;
        for (int i = 0; i < arcs; i++) {
            RectF arc = new RectF(cx - radii[i], baseY - radii[i], cx + radii[i], baseY + radii[i]);
            canvas.drawArc(arc, -135, 90, false, iconPaint);
        }
        if (progress < 0.5f) {
            canvas.drawCircle(cx, baseY - size * 0.10f, size * 0.14f, iconPaint);
        } else {
            // 斜杠
            canvas.drawLine(
                    cx - size * 0.9f, cy - size * 0.9f,
                    cx + size * 0.9f, cy + size * 1.1f,
                    iconPaint);
        }
    }

    private int blend(int from, int to, float p) {
        return (int) new ArgbEvaluator().evaluate(p, from, to);
    }

    /** ArgbEvaluator.evaluate 返回 Integer（颜色值），必须按 int 强转，不能转 float。 */
    private int blendColor(int from, int to, float p) {
        return (int) new ArgbEvaluator().evaluate(p, from, to);
    }
}
