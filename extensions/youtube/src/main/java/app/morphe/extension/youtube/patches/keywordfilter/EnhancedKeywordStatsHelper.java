package app.morphe.extension.youtube.patches.keywordfilter;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter.Source;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.keywordfilter.EnhancedKeywordContentFilter.MatchLogEntry;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds the interactive "Filter match log" row to {@code KeywordContentStatsPreferenceCategory}
 * with a native YouTube-style bottom sheet showing video thumbnails, clean titles, rule badges,
 * filter chips, and full-width action buttons.
 */
@SuppressWarnings({"deprecation", "unused"})
public final class EnhancedKeywordStatsHelper {

    private static final Pattern COMPONENT_PREFIX_PATTERN =
            Pattern.compile("^\\[[^\\]]+\\]\\s*");

    private static final LruCache<String, Bitmap> thumbnailCache = new LruCache<>(48);

    private enum FilterTab {
        ALL, VIDEOS, COMMENTS
    }

    public static void addMatchLogRow(PreferenceCategory category) {
        if (category == null) return;
        Context context = category.getContext();
        NumberFormat nf = NumberFormat.getInstance(Settings.MORPHE_LANGUAGE.get().getLocale());

        List<MatchLogEntry> logs = EnhancedKeywordContentFilter.getRecentMatchLogs();
        Preference matchLogPref = new Preference(context);
        matchLogPref.setTitle(str("morphe_hide_keyword_log_title"));
        if (logs.isEmpty()) {
            matchLogPref.setSummary(str("morphe_hide_keyword_log_summary_empty"));
        } else {
            matchLogPref.setSummary(str("morphe_hide_keyword_log_summary", nf.format(logs.size())));
        }
        matchLogPref.setOnPreferenceClickListener(pref -> {
            showMatchLogSheet(category, matchLogPref, nf);
            return true;
        });
        category.addPreference(matchLogPref);
    }

    @SuppressLint("SetTextI18n")
    private static void showMatchLogSheet(PreferenceCategory category, Preference matchLogPref, NumberFormat nf) {
        try {
            Context context = category.getContext();
            List<MatchLogEntry> logs = EnhancedKeywordContentFilter.getRecentMatchLogs();

            final int bgColor = ThemeUtils.getDialogBackgroundColor();
            final int fgColor = ThemeUtils.getAppForegroundColor();
            final boolean isDark = isDarkColor(bgColor);
            final int secondaryTextColor = isDark ? 0xFFAAAAAA : 0xFF606060;
            final int cardBgColor = isDark ? 0xFF1F1F1F : 0xFFF2F2F2;
            final int chipBgColor = isDark ? 0xFF2B2B2B : 0xFFE5E5E5;
            final int chipSelectedBg = isDark ? 0xFFF1F1F1 : 0xFF0F0F0F;
            final int chipSelectedText = isDark ? 0xFF0F0F0F : 0xFFFFFFFF;
            final int dividerColor = isDark ? 0x1FFFFFFF : 0x14000000;

            SheetBottomDialog.DraggableLinearLayout mainLayout =
                    SheetBottomDialog.createMainLayout(context, bgColor);

            // --- Header row: Title + count pill ---
            LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            headerRow.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp8);

            TextView titleView = new TextView(context);
            titleView.setText(str("morphe_hide_keyword_log_title"));
            titleView.setTextColor(fgColor);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            headerRow.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView countBadge = new TextView(context);
            countBadge.setText(nf.format(logs.size()));
            countBadge.setTextColor(secondaryTextColor);
            countBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            countBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            countBadge.setPadding(Dim.dp10, Dim.dp4, Dim.dp10, Dim.dp4);
            countBadge.setBackground(roundedRect(chipBgColor, Dim.dp(999)));
            headerRow.addView(countBadge);
            mainLayout.addView(headerRow);

            // --- Sheet reference holder ---
            final SheetBottomDialog.SlideDialog[] dialogHolder = new SheetBottomDialog.SlideDialog[1];

            // --- Empty state ---
            if (logs.isEmpty()) {
                TextView emptyView = new TextView(context);
                emptyView.setText(str("morphe_hide_keyword_log_empty"));
                emptyView.setTextColor(secondaryTextColor);
                emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                emptyView.setGravity(Gravity.CENTER);
                emptyView.setPadding(Dim.dp24, Dim.dp24, Dim.dp24, Dim.dp24);
                mainLayout.addView(emptyView);

                LinearLayout footer = new LinearLayout(context);
                footer.setOrientation(LinearLayout.HORIZONTAL);
                footer.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp16);
                Button okBtn = buildActionButton(context, "OK", chipSelectedBg, chipSelectedText, () -> {
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                });
                footer.addView(okBtn, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp40));
                mainLayout.addView(footer);

                SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(context, mainLayout, 200);
                dialogHolder[0] = dialog;
                dialog.show();
                return;
            }

            // --- Filter chips row (All / Videos / Comments) ---
            int videoCount = 0;
            int commentCount = 0;
            for (MatchLogEntry e : logs) {
                if (e.source() == Source.COMMENTS) commentCount++;
                else videoCount++;
            }

            boolean isRu = Locale.getDefault().getLanguage().equalsIgnoreCase("ru");
            String labelAll = (isRu ? "Все" : "All") + " · " + logs.size();
            String labelVideos = (isRu ? "Видео" : "Videos") + " · " + videoCount;
            String labelComments = (isRu ? "Комментарии" : "Comments") + " · " + commentCount;

            LinearLayout chipsRow = new LinearLayout(context);
            chipsRow.setOrientation(LinearLayout.HORIZONTAL);
            chipsRow.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp10);

            TextView chipAll = createFilterChip(context, labelAll);
            TextView chipVideos = createFilterChip(context, labelVideos);
            TextView chipComments = createFilterChip(context, labelComments);

            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.setMarginEnd(Dim.dp8);
            chipsRow.addView(chipAll, chipLp);
            chipsRow.addView(chipVideos, new LinearLayout.LayoutParams(chipLp));
            chipsRow.addView(chipComments, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mainLayout.addView(chipsRow);

            // Subtle top divider
            View topDivider = new View(context);
            topDivider.setBackgroundColor(dividerColor);
            mainLayout.addView(topDivider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1)));

            // --- Scrollable cards list ---
            ScrollView scrollView = new ScrollView(context);
            scrollView.setClipToPadding(false);
            scrollView.setVerticalScrollBarEnabled(true);
            int maxListHeight = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.56f);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight);

            LinearLayout cardsContainer = new LinearLayout(context);
            cardsContainer.setOrientation(LinearLayout.VERTICAL);
            cardsContainer.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp12);
            scrollView.addView(cardsContainer);
            mainLayout.addView(scrollView, scrollLp);

            final FilterTab[] activeTab = {FilterTab.ALL};
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

            Runnable renderCards = () -> {
                styleFilterChip(chipAll, activeTab[0] == FilterTab.ALL,
                        chipSelectedBg, chipSelectedText, chipBgColor, fgColor);
                styleFilterChip(chipVideos, activeTab[0] == FilterTab.VIDEOS,
                        chipSelectedBg, chipSelectedText, chipBgColor, fgColor);
                styleFilterChip(chipComments, activeTab[0] == FilterTab.COMMENTS,
                        chipSelectedBg, chipSelectedText, chipBgColor, fgColor);

                cardsContainer.removeAllViews();
                int shown = 0;
                for (int i = 0, size = logs.size(); i < size; i++) {
                    MatchLogEntry entry = logs.get(i);
                    if (activeTab[0] == FilterTab.VIDEOS && entry.source() == Source.COMMENTS) continue;
                    if (activeTab[0] == FilterTab.COMMENTS && entry.source() != Source.COMMENTS) continue;

                    View card = createMatchCard(context, entry, timeFormat, isDark,
                            fgColor, secondaryTextColor, cardBgColor, chipBgColor, isRu, dialogHolder);
                    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cardLp.bottomMargin = Dim.dp8;
                    cardsContainer.addView(card, cardLp);
                    shown++;
                }

                if (shown == 0) {
                    TextView none = new TextView(context);
                    none.setText(isRu ? "В этой категории записей нет" : "No entries in this category");
                    none.setTextColor(secondaryTextColor);
                    none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
                    none.setGravity(Gravity.CENTER);
                    none.setPadding(Dim.dp16, Dim.dp24, Dim.dp16, Dim.dp24);
                    cardsContainer.addView(none);
                }
            };

            chipAll.setOnClickListener(v -> { activeTab[0] = FilterTab.ALL; renderCards.run(); });
            chipVideos.setOnClickListener(v -> { activeTab[0] = FilterTab.VIDEOS; renderCards.run(); });
            chipComments.setOnClickListener(v -> { activeTab[0] = FilterTab.COMMENTS; renderCards.run(); });
            renderCards.run();

            // --- Bottom divider + pinned action bar (Copy | Clear | OK) ---
            View bottomDivider = new View(context);
            bottomDivider.setBackgroundColor(dividerColor);
            mainLayout.addView(bottomDivider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Dim.dp1)));

            LinearLayout footer = new LinearLayout(context);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp14);

            Button copyBtn = buildActionButton(context, str("morphe_hide_keyword_log_copy"),
                    chipBgColor, fgColor, () -> copyAllLogs(context, logs, timeFormat));
            Button clearBtn = buildActionButton(context, str("morphe_hide_keyword_log_clear"),
                    chipBgColor, fgColor, () -> {
                        EnhancedKeywordContentFilter.clearRecentMatchLogs();
                        matchLogPref.setSummary(str("morphe_hide_keyword_log_summary_empty"));
                        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                    });
            Button okBtn = buildActionButton(context, "OK", chipSelectedBg, chipSelectedText, () -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            });

            LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, Dim.dp40, 1.15f);
            btnLp1.setMarginEnd(Dim.dp8);
            LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, Dim.dp40, 1.0f);
            btnLp2.setMarginEnd(Dim.dp8);
            LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, Dim.dp40, 0.75f);

            footer.addView(copyBtn, btnLp1);
            footer.addView(clearBtn, btnLp2);
            footer.addView(okBtn, btnLp3);
            mainLayout.addView(footer);

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(context, mainLayout, 200);
            dialogHolder[0] = dialog;
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showMatchLogSheet failure", ex);
        }
    }

    @SuppressLint("SetTextI18n")
    private static View createMatchCard(Context context, MatchLogEntry entry, SimpleDateFormat timeFormat,
                                        boolean isDark, int fgColor, int secondaryTextColor,
                                        int cardBgColor, int pillBgColor, boolean isRu,
                                        SheetBottomDialog.SlideDialog[] dialogHolder) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setPadding(Dim.dp10, Dim.dp10, Dim.dp10, Dim.dp10);
        card.setBackground(roundedRect(cardBgColor, Dim.dp12));

        final boolean hasVideo = entry.source() != Source.COMMENTS
                && entry.videoId() != null && entry.videoId().length() == 11;

        if (hasVideo) {
            // 16:9 rounded YouTube thumbnail with status badge overlay
            FrameLayout thumbFrame = new FrameLayout(context);
            int thumbW = Dim.dp(124);
            int thumbH = Dim.dp(70);
            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbW, thumbH);
            thumbLp.setMarginEnd(Dim.dp10);
            thumbFrame.setBackground(roundedRect(isDark ? 0xFF2C2C2C : 0xFFDCDCDC, Dim.dp8));
            thumbFrame.setClipToOutline(true);
            thumbFrame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Dim.dp8);
                }
            });

            ImageView thumbImage = new ImageView(context);
            thumbImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbFrame.addView(thumbImage, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            loadVideoThumbnailAsync(entry.videoId(), thumbImage);

            // Status badge in bottom-right of thumbnail (Hidden / Debug)
            TextView statusBadge = new TextView(context);
            statusBadge.setText(entry.debugMode()
                    ? (isRu ? "Отладка" : "Debug")
                    : (isRu ? "Скрыто" : "Hidden"));
            statusBadge.setTextColor(0xFFFFFFFF);
            statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            statusBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            statusBadge.setPadding(Dim.dp(5), Dim.dp(2), Dim.dp(5), Dim.dp(2));
            statusBadge.setBackground(roundedRect(
                    entry.debugMode() ? 0xCCF57C00 : 0xCCD32F2F, Dim.dp4));
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            badgeLp.setMargins(0, 0, Dim.dp4, Dim.dp4);
            thumbFrame.addView(statusBadge, badgeLp);

            card.addView(thumbFrame, thumbLp);
        } else {
            // Comment / text-only rounded icon badge
            TextView iconBadge = new TextView(context);
            iconBadge.setText(entry.source() == Source.COMMENTS ? "💬" : "▶");
            iconBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            iconBadge.setGravity(Gravity.CENTER);
            iconBadge.setBackground(roundedRect(pillBgColor, Dim.dp8));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Dim.dp36, Dim.dp36);
            iconLp.setMarginEnd(Dim.dp10);
            card.addView(iconBadge, iconLp);
        }

        // Right column: clean highlighted title + metadata + rule pill
        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        String cleanText = stripComponentPrefix(entry.matchedText());
        SpannableStringBuilder titleSb = new SpannableStringBuilder();
        appendHighlightedText(titleSb, cleanText, entry.matchedWord());

        TextView titleTv = new TextView(context);
        titleTv.setText(titleSb);
        titleTv.setTextColor(fgColor);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        titleTv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleTv.setMaxLines(hasVideo ? 2 : 4);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setLineSpacing(Dim.dp(2), 1.0f);
        infoCol.addView(titleTv);

        // Source + time row
        String timeStr = timeFormat.format(new Date(entry.timestampMs()));
        String sourceLabel = formatSourceLabel(entry.source(), isRu);
        TextView metaTv = new TextView(context);
        metaTv.setText(sourceLabel + " · " + timeStr);
        metaTv.setTextColor(secondaryTextColor);
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = Dim.dp4;
        infoCol.addView(metaTv, metaLp);

        // Rule pill
        String ruleText = (entry.ruleDisplay() != null && !entry.ruleDisplay().isEmpty())
                ? entry.ruleDisplay()
                : entry.matchedWord();
        if (ruleText != null && !ruleText.isEmpty()) {
            TextView rulePill = new TextView(context);
            rulePill.setText((isRu ? "Правило: " : "Rule: ") + ruleText);
            rulePill.setTextColor(isDark ? 0xFFFF8A80 : 0xFFC62828);
            rulePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            rulePill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            rulePill.setSingleLine(true);
            rulePill.setEllipsize(TextUtils.TruncateAt.END);
            rulePill.setPadding(Dim.dp(6), Dim.dp(2), Dim.dp(6), Dim.dp(2));
            rulePill.setBackground(roundedRect(isDark ? 0x26EF5350 : 0x1AD32F2F, Dim.dp4));
            LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ruleLp.topMargin = Dim.dp4;
            infoCol.addView(rulePill, ruleLp);
        }

        card.addView(infoCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Tap video card to open directly in YouTube; long-press any card to copy entry
        if (hasVideo) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.youtube.com/watch?v=" + entry.videoId()));
                    intent.setPackage(context.getPackageName());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Could not open video from match log", ex);
                }
            });
        }
        card.setOnLongClickListener(v -> {
            String copyText = hasVideo
                    ? cleanText + "\nhttps://youtu.be/" + entry.videoId()
                    : cleanText;
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KeywordFilterEntry", copyText));
                Utils.showToastShort(str("morphe_hide_keyword_log_copied"));
            }
            return true;
        });

        return card;
    }

    private static void loadVideoThumbnailAsync(String videoId, ImageView target) {
        target.setTag(videoId);
        Bitmap cached = thumbnailCache.get(videoId);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                URL url = new URL("https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() == 200) {
                    try (InputStream is = conn.getInputStream()) {
                        Bitmap bmp = BitmapFactory.decodeStream(is);
                        if (bmp != null) {
                            thumbnailCache.put(videoId, bmp);
                            Utils.runOnMainThread(() -> {
                                if (videoId.equals(target.getTag())) {
                                    target.setImageBitmap(bmp);
                                }
                            });
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {
            }
        });
    }

    private static void copyAllLogs(Context context, List<MatchLogEntry> logs, SimpleDateFormat timeFormat) {
        StringBuilder plainText = new StringBuilder();
        for (int i = 0, size = logs.size(); i < size; i++) {
            MatchLogEntry entry = logs.get(i);
            if (i > 0) plainText.append("\n\n");
            String timeStr = timeFormat.format(new Date(entry.timestampMs()));
            plainText.append(i + 1).append(". [")
                    .append(entry.source().name()).append(" • ").append(timeStr).append("] ")
                    .append(stripComponentPrefix(entry.matchedText()))
                    .append(" («").append(entry.matchedWord()).append("»)");
            if (entry.videoId() != null && entry.videoId().length() == 11) {
                plainText.append(" https://youtu.be/").append(entry.videoId());
            }
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("KeywordFilterLog", plainText.toString()));
            Utils.showToastShort(str("morphe_hide_keyword_log_copied"));
        }
    }

    private static TextView createFilterChip(Context context, String label) {
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setPadding(Dim.dp12, Dim.dp6, Dim.dp12, Dim.dp6);
        chip.setSingleLine(true);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private static void styleFilterChip(TextView chip, boolean selected,
                                        int selectedBg, int selectedText, int normalBg, int normalText) {
        chip.setBackground(roundedRect(selected ? selectedBg : normalBg, Dim.dp8));
        chip.setTextColor(selected ? selectedText : normalText);
    }

    private static Button buildActionButton(Context context, CharSequence text,
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

    private static boolean isDarkColor(int color) {
        return (Color.red(color) + Color.green(color) + Color.blue(color)) < 384;
    }

    private static String stripComponentPrefix(@Nullable String text) {
        if (text == null) return "";
        Matcher m = COMPONENT_PREFIX_PATTERN.matcher(text);
        return m.find() ? text.substring(m.end()).trim() : text.trim();
    }

    private static String formatSourceLabel(Source source, boolean isRu) {
        return switch (source) {
            case HOME -> isRu ? "Главная" : "Home";
            case SUBSCRIPTIONS -> isRu ? "Подписки" : "Subscriptions";
            case SEARCH -> isRu ? "Поиск" : "Search";
            case COMMENTS -> isRu ? "Комментарии" : "Comments";
        };
    }

    private static void appendHighlightedText(SpannableStringBuilder sb, String text, String word) {
        int base = sb.length();
        sb.append(text);
        if (word == null || word.isEmpty()) return;

        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerWord = word.toLowerCase(Locale.ROOT);
        if (lowerText.length() != text.length() || lowerWord.length() != word.length()) {
            int idx = text.indexOf(word);
            if (idx >= 0) {
                applyWordHighlight(sb, base + idx, base + idx + word.length());
            }
            return;
        }
        int from = 0;
        while (from < lowerText.length()) {
            int idx = lowerText.indexOf(lowerWord, from);
            if (idx < 0) break;
            applyWordHighlight(sb, base + idx, base + idx + lowerWord.length());
            from = idx + lowerWord.length();
        }
    }

    private static void applyWordHighlight(SpannableStringBuilder sb, int start, int end) {
        sb.setSpan(new BackgroundColorSpan(0xFFD32F2F), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private EnhancedKeywordStatsHelper() {
    }
}