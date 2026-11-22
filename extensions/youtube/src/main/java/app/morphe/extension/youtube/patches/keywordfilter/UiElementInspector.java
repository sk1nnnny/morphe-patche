package app.morphe.extension.youtube.patches.keywordfilter;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Native YouTube-style UI Element Inspector.
 * When enabled in settings, shows a floating vector pencil button (zero emojis).
 * Tapping the pencil toggles Inspect Mode: touching any element outlines it with a contour
 * and opens a detail sheet with its Android resource ID, View hierarchy, texts, and matched
 * Litho component path/identifier, with one-tap copy.
 */
@SuppressWarnings("unused")
public final class UiElementInspector {

    private static final String FAB_TAG = "morphe_inspector_fab";
    private static final String OVERLAY_TAG = "morphe_inspector_overlay";
    private static final int MAX_LITHO_RECORDS = 150;

    private record LithoRecord(String identifier, String path, String accessibility, List<String> texts) {}

    private static final Deque<LithoRecord> recentLithoRecords = new ArrayDeque<>(MAX_LITHO_RECORDS);

    private static volatile boolean pickingActive = false;
    private static WeakReference<FrameLayout> fabRef = new WeakReference<>(null);
    private static WeakReference<InspectorOverlayView> overlayRef = new WeakReference<>(null);
    private static WeakReference<Activity> lastActivityRef = new WeakReference<>(null);
    private static volatile boolean syncScheduled = false;

    public static void recordLithoComponent(@Nullable String identifier, @Nullable CharSequence path,
                                            @Nullable String accessibility, @Nullable List<String> texts) {
        if (!KeywordFilterSettings.HIDE_KEYWORD_CONTENT_INSPECTOR.get()) {
            return;
        }
        String p = path != null ? path.toString() : "";
        String id = identifier != null ? identifier : "";
        String a11y = accessibility != null ? accessibility : "";
        if (p.isEmpty() && id.isEmpty()) return;

        List<String> copy = texts != null && !texts.isEmpty()
                ? new ArrayList<>(texts)
                : java.util.Collections.emptyList();
        synchronized (recentLithoRecords) {
            if (recentLithoRecords.size() >= MAX_LITHO_RECORDS) {
                recentLithoRecords.removeLast();
            }
            recentLithoRecords.addFirst(new LithoRecord(id, p, a11y, copy));
        }
    }

    public static void syncVisibilityFromLayout() {
        boolean enabled = KeywordFilterSettings.HIDE_KEYWORD_CONTENT_INSPECTOR.get();
        if (!enabled && fabRef.get() == null && !pickingActive) {
            return;
        }
        if (syncScheduled) return;
        syncScheduled = true;
        Utils.runOnMainThread(() -> {
            syncScheduled = false;
            syncOnMainThread();
        });
    }

    public static void onSettingChanged(boolean enabled) {
        if (!enabled) {
            pickingActive = false;
        }
        Utils.runOnMainThread(UiElementInspector::syncOnMainThread);
    }

    private static void syncOnMainThread() {
        try {
            boolean enabled = KeywordFilterSettings.HIDE_KEYWORD_CONTENT_INSPECTOR.get();
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;

            if (!enabled) {
                pickingActive = false;
                removeOverlay(content);
                removeFab(content);
                return;
            }

            Activity prev = lastActivityRef.get();
            if (prev != activity) {
                lastActivityRef = new WeakReference<>(activity);
                pickingActive = false;
                removeOverlay(content);
                removeFab(content);
            }

            FrameLayout fab = ensureFab(activity, content);
            updateFabVisual(fab);
            if (pickingActive) {
                ensureOverlay(activity, content, fab);
            } else {
                removeOverlay(content);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "UiElementInspector.syncOnMainThread failure", ex);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static FrameLayout ensureFab(Activity activity, ViewGroup content) {
        View existing = content.findViewWithTag(FAB_TAG);
        if (existing instanceof FrameLayout f) {
            fabRef = new WeakReference<>(f);
            f.bringToFront();
            return f;
        }

        int size = Dim.dp(44);
        FrameLayout fab = new FrameLayout(activity);
        fab.setTag(FAB_TAG);
        fab.setElevation(Dim.dp12);
        fab.setClickable(true);
        fab.setFocusable(true);

        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Dim.dp10;
        icon.setPadding(pad, pad, pad, pad);
        fab.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.rightMargin = Dim.dp12;

        // Support both tapping (toggle Inspect Mode) and dragging vertically/horizontally
        final float touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY, startTx, startTy;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startTx = v.getTranslationX();
                        startTy = v.getTranslationY();
                        dragging = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && (dx * dx + dy * dy) > touchSlop * touchSlop) {
                            dragging = true;
                        }
                        if (dragging) {
                            v.setTranslationX(startTx + dx);
                            v.setTranslationY(startTy + dy);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (!dragging) {
                            togglePickingMode(activity, content, fab);
                        }
                        return true;
                    }
                }
                return false;
            }
        });

        content.addView(fab, lp);
        fabRef = new WeakReference<>(fab);
        return fab;
    }

    private static void togglePickingMode(Activity activity, ViewGroup content, FrameLayout fab) {
        pickingActive = !pickingActive;
        updateFabVisual(fab);
        if (pickingActive) {
            ensureOverlay(activity, content, fab);
            boolean isRu = Locale.getDefault().getLanguage().equalsIgnoreCase("ru");
            Utils.showToastShort(isRu
                    ? "Режим инспектора: нажмите на любой элемент"
                    : "Inspector active: tap any element");
        } else {
            removeOverlay(content);
        }
    }

    private static void updateFabVisual(FrameLayout fab) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (pickingActive) {
            bg.setColor(0xE6065FD4);
            bg.setStroke(Dim.dp(2), 0xFFFFFFFF);
        } else {
            bg.setColor(0xCC181818);
            bg.setStroke(Math.max(1, Dim.dp(1)), 0x4DFFFFFF);
        }
        fab.setBackground(bg);
        if (fab.getChildCount() > 0 && fab.getChildAt(0) instanceof ImageView iv) {
            iv.setImageDrawable(new VectorPencilDrawable(0xFFFFFFFF));
        }
    }

    private static void ensureOverlay(Activity activity, ViewGroup content, FrameLayout fab) {
        View existing = content.findViewWithTag(OVERLAY_TAG);
        if (existing instanceof InspectorOverlayView ov) {
            overlayRef = new WeakReference<>(ov);
            ov.bringToFront();
            fab.bringToFront();
            return;
        }
        InspectorOverlayView overlay = new InspectorOverlayView(activity, content);
        overlay.setTag(OVERLAY_TAG);
        content.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayRef = new WeakReference<>(overlay);
        fab.bringToFront();
    }

    private static void removeOverlay(ViewGroup content) {
        View existing = content.findViewWithTag(OVERLAY_TAG);
        if (existing != null) {
            content.removeView(existing);
        }
        overlayRef = new WeakReference<>(null);
    }

    private static void removeFab(ViewGroup content) {
        View existing = content.findViewWithTag(FAB_TAG);
        if (existing != null) {
            content.removeView(existing);
        }
        fabRef = new WeakReference<>(null);
    }

    /**
     * Full-screen transparent intercept layer that outlines the touched view with a crisp contour
     * and opens the detail sheet on release.
     */
    @SuppressLint("ViewConstructor")
    private static final class InspectorOverlayView extends View {
        private final ViewGroup rootContent;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF highlightRect = new RectF();
        private final int[] locBuf = new int[2];
        private final int[] rootLoc = new int[2];
        @Nullable
        private View selectedView;

        InspectorOverlayView(Context context, ViewGroup rootContent) {
            super(context);
            this.rootContent = rootContent;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setColor(0xFF3EA6FF);
            strokePaint.setStrokeWidth(Dim.dp(2));
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(0x223EA6FF);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            if (!highlightRect.isEmpty()) {
                float r = Dim.dp(4);
                canvas.drawRoundRect(highlightRect, r, r, fillPaint);
                canvas.drawRoundRect(highlightRect, r, r, strokePaint);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float rawX = event.getRawX();
            float rawY = event.getRawY();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    selectedView = findDeepestViewAt(rootContent, rawX, rawY);
                    updateHighlightRect(selectedView);
                    return true;
                }
                case MotionEvent.ACTION_UP -> {
                    View target = findDeepestViewAt(rootContent, rawX, rawY);
                    if (target == null) target = selectedView;
                    updateHighlightRect(target);
                    if (target != null) {
                        showInspectorDetailsSheet(getContext(), target);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL -> {
                    highlightRect.setEmpty();
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private void updateHighlightRect(@Nullable View target) {
            if (target == null) {
                highlightRect.setEmpty();
            } else {
                rootContent.getLocationOnScreen(rootLoc);
                target.getLocationOnScreen(locBuf);
                float left = locBuf[0] - rootLoc[0];
                float top = locBuf[1] - rootLoc[1];
                highlightRect.set(left, top, left + target.getWidth(), top + target.getHeight());
            }
            invalidate();
        }

        @Nullable
        private View findDeepestViewAt(View node, float rawX, float rawY) {
            List<View> hits = new ArrayList<>();
            collectHitViews(node, rawX, rawY, hits);
            if (hits.isEmpty()) return null;

            // Pick the smallest (most specific) visible view at (rawX, rawY),
            // preferring views that actually contain text, contentDescription, or a resource ID.
            View best = null;
            long bestScore = Long.MAX_VALUE;
            for (int i = 0, size = hits.size(); i < size; i++) {
                View v = hits.get(i);
                long area = (long) Math.max(1, v.getWidth()) * Math.max(1, v.getHeight());
                boolean hasUsefulInfo = hasInspectableInfo(v);
                long score = hasUsefulInfo ? area : (area + 10_000_000L);
                if (best == null || score < bestScore) {
                    best = v;
                    bestScore = score;
                }
            }
            return best;
        }

        private void collectHitViews(View node, float rawX, float rawY, List<View> out) {
            if (node == this || node.getVisibility() != View.VISIBLE) {
                return;
            }
            if (node.getWidth() <= 0 || node.getHeight() <= 0) {
                return;
            }
            Object tag = node.getTag();
            if (FAB_TAG.equals(tag) || OVERLAY_TAG.equals(tag)) {
                return;
            }
            node.getLocationOnScreen(locBuf);
            int l = locBuf[0];
            int t = locBuf[1];
            int r = l + node.getWidth();
            int b = t + node.getHeight();
            if (rawX < l || rawX > r || rawY < t || rawY > b) {
                return;
            }
            if (node instanceof ViewGroup vg) {
                for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                    collectHitViews(vg.getChildAt(i), rawX, rawY, out);
                }
            }
            out.add(node);
        }

        private boolean hasInspectableInfo(View v) {
            if (v.getContentDescription() != null && v.getContentDescription().length() > 0) {
                return true;
            }
            if (v instanceof TextView tv && tv.getText() != null && tv.getText().length() > 0) {
                return true;
            }
            if (v instanceof ComponentHost host) {
                TextContent tc = host.getTextContent();
                if (tc != null && !tc.getTextItems().isEmpty()) {
                    return true;
                }
            }
            return v.getId() != View.NO_ID && v.getId() != 0;
        }
    }

    private static void showInspectorDetailsSheet(Context context, View target) {
        try {
            boolean isRu = Locale.getDefault().getLanguage().equalsIgnoreCase("ru");
            int bgColor = ThemeUtils.getDialogBackgroundColor();
            int fgColor = ThemeUtils.getAppForegroundColor();
            boolean isDark = (android.graphics.Color.red(bgColor)
                    + android.graphics.Color.green(bgColor)
                    + android.graphics.Color.blue(bgColor)) < 384;
            int secondaryColor = isDark ? 0xFFAAAAAA : 0xFF606060;
            int cardBg = isDark ? 0xFF1F1F1F : 0xFFF2F2F2;
            int chipBg = isDark ? 0xFF2B2B2B : 0xFFE5E5E5;
            int okBg = isDark ? 0xFFF1F1F1 : 0xFF0F0F0F;
            int okText = isDark ? 0xFF0F0F0F : 0xFFFFFFFF;
            int dividerColor = isDark ? 0x1FFFFFFF : 0x14000000;

            String infoReport = buildInspectorReport(target);

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, bgColor);

            TextView titleTv = new TextView(context);
            titleTv.setText(isRu ? "Инспектор элемента" : "Element Inspector");
            titleTv.setTextColor(fgColor);
            titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            titleTv.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp10);
            mainLayout.addView(titleTv);

            View topDiv = new View(context);
            topDiv.setBackgroundColor(dividerColor);
            mainLayout.addView(topDiv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1)));

            ScrollView scroll = new ScrollView(context);
            int maxH = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.55f);
            LinearLayout bodyCol = new LinearLayout(context);
            bodyCol.setOrientation(LinearLayout.VERTICAL);
            bodyCol.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);

            TextView reportTv = new TextView(context);
            reportTv.setText(infoReport);
            reportTv.setTextColor(fgColor);
            reportTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            reportTv.setTypeface(Typeface.MONOSPACE);
            reportTv.setTextIsSelectable(true);
            reportTv.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            reportTv.setBackground(roundedRect(cardBg, Dim.dp10));
            bodyCol.addView(reportTv);

            scroll.addView(bodyCol);
            mainLayout.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, maxH));

            View botDiv = new View(context);
            botDiv.setBackgroundColor(dividerColor);
            mainLayout.addView(botDiv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1)));

            final SheetBottomDialog.SlideDialog[] holder = new SheetBottomDialog.SlideDialog[1];

            LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp(14));

            Button copyBtn = buildButton(context, isRu ? "Скопировать всё" : "Copy all",
                    chipBg, fgColor, () -> {
                        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("MorpheInspector", infoReport));
                            Utils.showToastShort(str("morphe_hide_keyword_log_copied"));
                        }
                    });
            Button closeBtn = buildButton(context, isRu ? "Закрыть" : "Close",
                    okBg, okText, () -> {
                        if (holder[0] != null) holder[0].dismiss();
                    });

            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, Dim.dp40, 1.2f);
            lp1.setMarginEnd(Dim.dp8);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, Dim.dp40, 0.9f);
            footer.addView(copyBtn, lp1);
            footer.addView(closeBtn, lp2);
            mainLayout.addView(footer);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(context, mainLayout, 180);
            holder[0] = dialog;
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showInspectorDetailsSheet failure", ex);
        }
    }

    private static String buildInspectorReport(View target) {
        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(target.getClass().getName()).append('\n');

        int id = target.getId();
        if (id != View.NO_ID && id != 0) {
            sb.append("ID: 0x").append(Integer.toHexString(id));
            try {
                String resName = target.getResources().getResourceName(id);
                sb.append(" (").append(resName).append(')');
            } catch (Exception ignored) {
            }
            sb.append('\n');
        } else {
            sb.append("ID: NO_ID\n");
        }

        int[] loc = new int[2];
        target.getLocationOnScreen(loc);
        sb.append("Bounds: [").append(loc[0]).append(", ").append(loc[1])
                .append("] ").append(target.getWidth()).append("x").append(target.getHeight()).append("px\n");

        if (target.getTag() != null) {
            sb.append("Tag: ").append(target.getTag()).append('\n');
        }
        CharSequence desc = target.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append("ContentDescription: ").append(desc).append('\n');
        }

        List<String> collectedTexts = new ArrayList<>();
        collectTextsFromViewTree(target, collectedTexts);
        if (!collectedTexts.isEmpty()) {
            sb.append("\nTexts:\n");
            for (int i = 0; i < Math.min(collectedTexts.size(), 15); i++) {
                sb.append("- ").append(collectedTexts.get(i)).append('\n');
            }
        }

        // Also collect texts from immediate parent if target itself had no texts
        if (collectedTexts.isEmpty() && target.getParent() instanceof View parentView) {
            collectTextsFromViewTree(parentView, collectedTexts);
            if (!collectedTexts.isEmpty()) {
                sb.append("\nParent Texts:\n");
                for (int i = 0; i < Math.min(collectedTexts.size(), 10); i++) {
                    sb.append("- ").append(collectedTexts.get(i)).append('\n');
                }
            }
        }

        // Match against recent Litho components (captured from all Litho text elements & buffers)
        List<LithoRecord> matchedLitho = findMatchingLithoRecords(collectedTexts, desc);
        if (!matchedLitho.isEmpty()) {
            sb.append("\nMatched Litho Components:\n");
            for (int i = 0; i < Math.min(matchedLitho.size(), 6); i++) {
                LithoRecord rec = matchedLitho.get(i);
                if (!rec.identifier().isEmpty()) {
                    sb.append("Identifier: ").append(rec.identifier()).append('\n');
                }
                if (!rec.path().isEmpty()) {
                    sb.append("Path: ").append(rec.path()).append('\n');
                }
                if (!rec.texts().isEmpty()) {
                    sb.append("Text: ").append(rec.texts().get(0)).append('\n');
                }
                sb.append('\n');
            }
        } else {
            // Fallback: list the most recent unique Litho elements rendered on screen
            List<LithoRecord> recent = getRecentUniqueLithoRecords(8);
            if (!recent.isEmpty()) {
                sb.append("\nRecent Screen Litho Elements:\n");
                for (LithoRecord rec : recent) {
                    String txt = rec.texts().isEmpty() ? "" : (" -> " + rec.texts().get(0));
                    sb.append("- ").append(rec.path().isEmpty() ? rec.identifier() : rec.path())
                            .append(txt).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append("View Ancestors:\n");
        View curr = target;
        for (int depth = 0; depth < 6 && curr != null; depth++) {
            String res = "";
            if (curr.getId() != View.NO_ID && curr.getId() != 0) {
                try {
                    res = " #" + curr.getResources().getResourceEntryName(curr.getId());
                } catch (Exception ignored) {
                }
            }
            sb.append("  ".repeat(depth)).append(curr.getClass().getSimpleName()).append(res).append('\n');
            curr = curr.getParent() instanceof View pv ? pv : null;
        }
        return sb.toString().trim();
    }

    private static void collectTextsFromViewTree(View view, List<String> out) {
        if (view instanceof ComponentHost host) {
            TextContent tc = host.getTextContent();
            if (tc != null) {
                for (CharSequence cs : tc.getTextItems()) {
                    if (cs != null) {
                        String s = cs.toString().trim();
                        if (!s.isEmpty() && !out.contains(s)) {
                            out.add(s);
                        }
                    }
                }
            }
        } else if (view instanceof TextView tv) {
            CharSequence cs = tv.getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (!s.isEmpty() && !out.contains(s)) {
                    out.add(s);
                }
            }
        }
        if (view instanceof ViewGroup vg) {
            for (int i = 0, count = vg.getChildCount(); i < count; i++) {
                collectTextsFromViewTree(vg.getChildAt(i), out);
            }
        }
    }

    private static List<LithoRecord> getRecentUniqueLithoRecords(int limit) {
        List<LithoRecord> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        synchronized (recentLithoRecords) {
            for (LithoRecord rec : recentLithoRecords) {
                String txt = rec.texts().isEmpty() ? "" : rec.texts().get(0);
                String key = rec.path() + "|" + txt;
                if (seen.add(key)) {
                    out.add(rec);
                    if (out.size() >= limit) break;
                }
            }
        }
        return out;
    }

    private static List<LithoRecord> findMatchingLithoRecords(List<String> texts, @Nullable CharSequence desc) {
        List<LithoRecord> matches = new ArrayList<>();
        String descStr = desc != null ? desc.toString().trim() : "";
        synchronized (recentLithoRecords) {
            for (LithoRecord rec : recentLithoRecords) {
                boolean matched = false;
                if (!descStr.isEmpty() && rec.accessibility().contains(descStr)) {
                    matched = true;
                }
                if (!matched) {
                    for (String t : texts) {
                        for (String rt : rec.texts()) {
                            if (rt.equals(t) || (t.length() >= 4 && rt.contains(t))) {
                                matched = true;
                                break;
                            }
                        }
                        if (matched) break;
                    }
                }
                if (matched) {
                    matches.add(rec);
                }
            }
        }
        return matches;
    }

    private static Button buildButton(Context context, CharSequence text,
                                      int bgColor, int textColor, Runnable onClick) {
        Button btn = new Button(context, null, 0);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(Dim.dp10, 0, Dim.dp10, 0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setBackground(roundedRect(bgColor, Dim.dp(20)));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private static GradientDrawable roundedRect(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /**
     * Crisp vector pencil icon drawn with Canvas Path (zero emojis).
     */
    private static final class VectorPencilDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        VectorPencilDrawable(int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float w = b.width();
            float h = b.height();
            paint.setStrokeWidth(Math.max(2f, w * 0.09f));

            path.reset();
            // Pencil body diagonal from top-right to bottom-left tip
            path.moveTo(b.left + w * 0.68f, b.top + h * 0.16f);
            path.lineTo(b.left + w * 0.84f, b.top + h * 0.32f);
            path.lineTo(b.left + w * 0.34f, b.top + h * 0.82f);
            path.lineTo(b.left + w * 0.14f, b.top + h * 0.86f);
            path.lineTo(b.left + w * 0.18f, b.top + h * 0.66f);
            path.close();
            // Collar line near eraser
            path.moveTo(b.left + w * 0.58f, b.top + h * 0.26f);
            path.lineTo(b.left + w * 0.74f, b.top + h * 0.42f);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private UiElementInspector() {
    }
}
