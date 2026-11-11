package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UMessageModernList;

/** Frequently asked questions: grouped cards, each question expands smoothly in place to show its answer. */
public class UMessageFaqActivity extends BaseFragment {

    private static final long DURATION = 320;

    private static final int[] GROUPS = {R.string.UMessageFaqGroup1, R.string.UMessageFaqGroup2, R.string.UMessageFaqGroup3};
    // general questions tied to one feature: hidden while that feature is off (admin switch or Safe Mode)
    private static final String[] ITEM_FLAGS = {null, null, null, null, null, null, null,
            org.telegram.messenger.UMessageRemoteFlags.SAVE_DELETED, org.telegram.messenger.UMessageRemoteFlags.GHOST_MODE,
            org.telegram.messenger.UMessageRemoteFlags.HIDDEN_CHATS, org.telegram.messenger.UMessageRemoteFlags.UM_PREMIUM,
            org.telegram.messenger.UMessageRemoteFlags.U_USERNAMES, null};
    private static final int[][] ITEMS = {
            {R.string.UMessageFaqQ1, R.string.UMessageFaqA1, 0},
            {R.string.UMessageFaqQ2, R.string.UMessageFaqA2, 0},
            {R.string.UMessageFaqQ3, R.string.UMessageFaqA3, 0},
            {R.string.UMessageFaqQ4, R.string.UMessageFaqA4, 1},
            {R.string.UMessageFaqQ5, R.string.UMessageFaqA5, 1},
            {R.string.UMessageFaqQ6, R.string.UMessageFaqA6, 1},
            {R.string.UMessageFaqQ7, R.string.UMessageFaqA7, 1},
            {R.string.UMessageFaqQ8, R.string.UMessageFaqA8, 2},
            {R.string.UMessageFaqQ9, R.string.UMessageFaqA9, 2},
            {R.string.UMessageFaqQ10, R.string.UMessageFaqA10, 2},
            {R.string.UMessageFaqQ11, R.string.UMessageFaqA11, 2},
            {R.string.UMessageFaqQ12, R.string.UMessageFaqA12, 2},
            {R.string.UMessageFaqQ13, R.string.UMessageFaqA13, 2},
    };

    // every feature explained; a question is shown only while its feature is enabled from the admin panel
    private static final int[] FEATURE_GROUPS = {
            R.string.UMessageFaqFG_privacy,
            R.string.UMessageFaqFG_security,
            R.string.UMessageFaqFG_messages,
            R.string.UMessageFaqFG_folders,
            R.string.UMessageFaqFG_stories,
            R.string.UMessageFaqFG_media,
            R.string.UMessageFaqFG_tools
    };
    private static final int[][] FEATURE_ITEMS = {
            {R.string.UMessageFaqFQ_stranger_protection, R.string.UMessageFaqFA_stranger_protection, 0},
            {R.string.UMessageFaqFQ_chat_trash, R.string.UMessageFaqFA_chat_trash, 0},
            {R.string.UMessageFaqFQ_privacy_guard, R.string.UMessageFaqFA_privacy_guard, 0},
            {R.string.UMessageFaqFQ_account_switch_pins, R.string.UMessageFaqFA_account_switch_pins, 0},
            {R.string.UMessageFaqFQ_apk_block, R.string.UMessageFaqFA_apk_block, 1},
            {R.string.UMessageFaqFQ_group_spam_guard, R.string.UMessageFaqFA_group_spam_guard, 1},
            {R.string.UMessageFaqFQ_masked_link_guard, R.string.UMessageFaqFA_masked_link_guard, 1},
            {R.string.UMessageFaqFQ_nsfw_filter, R.string.UMessageFaqFA_nsfw_filter, 1},
            {R.string.UMessageFaqFQ_parental_control, R.string.UMessageFaqFA_parental_control, 1},
            {R.string.UMessageFaqFQ_banned_sources, R.string.UMessageFaqFA_banned_sources, 1},
            {R.string.UMessageFaqFQ_block_ads, R.string.UMessageFaqFA_block_ads, 1},
            {R.string.UMessageFaqFQ_save_edited, R.string.UMessageFaqFA_save_edited, 2},
            {R.string.UMessageFaqFQ_view_once_save, R.string.UMessageFaqFA_view_once_save, 2},
            {R.string.UMessageFaqFQ_templates, R.string.UMessageFaqFA_templates, 2},
            {R.string.UMessageFaqFQ_auto_reply, R.string.UMessageFaqFA_auto_reply, 2},
            {R.string.UMessageFaqFQ_confirm_sticker, R.string.UMessageFaqFA_confirm_sticker, 2},
            {R.string.UMessageFaqFQ_uzbek_speech, R.string.UMessageFaqFA_uzbek_speech, 2},
            {R.string.UMessageFaqFQ_offline_translate, R.string.UMessageFaqFA_offline_translate, 2},
            {R.string.UMessageFaqFQ_swipe_actions, R.string.UMessageFaqFA_swipe_actions, 2},
            {R.string.UMessageFaqFQ_bank_cards, R.string.UMessageFaqFA_bank_cards, 2},
            {R.string.UMessageFaqFQ_chat_export, R.string.UMessageFaqFA_chat_export, 2},
            {R.string.UMessageFaqFQ_unread_reminder, R.string.UMessageFaqFA_unread_reminder, 2},
            {R.string.UMessageFaqFQ_auto_approve, R.string.UMessageFaqFA_auto_approve, 2},
            {R.string.UMessageFaqFQ_focus_mode, R.string.UMessageFaqFA_focus_mode, 3},
            {R.string.UMessageFaqFQ_admin_folders, R.string.UMessageFaqFA_admin_folders, 3},
            {R.string.UMessageFaqFQ_mutual_contact, R.string.UMessageFaqFA_mutual_contact, 3},
            {R.string.UMessageFaqFQ_stories_anonymous, R.string.UMessageFaqFA_stories_anonymous, 4},
            {R.string.UMessageFaqFQ_u_stories, R.string.UMessageFaqFA_u_stories, 4},
            {R.string.UMessageFaqFQ_story_privacy_batch, R.string.UMessageFaqFA_story_privacy_batch, 4},
            {R.string.UMessageFaqFQ_story_camera_pro, R.string.UMessageFaqFA_story_camera_pro, 4},
            {R.string.UMessageFaqFQ_round_effects, R.string.UMessageFaqFA_round_effects, 5},
            {R.string.UMessageFaqFQ_ai_eraser, R.string.UMessageFaqFA_ai_eraser, 5},
            {R.string.UMessageFaqFQ_document_reader, R.string.UMessageFaqFA_document_reader, 5},
            {R.string.UMessageFaqFQ_sleep_timer, R.string.UMessageFaqFA_sleep_timer, 5},
            {R.string.UMessageFaqFQ_stop_autodownload, R.string.UMessageFaqFA_stop_autodownload, 5},
            {R.string.UMessageFaqFQ_friend_map, R.string.UMessageFaqFA_friend_map, 6},
            {R.string.UMessageFaqFQ_nearby_share, R.string.UMessageFaqFA_nearby_share, 6},
            {R.string.UMessageFaqFQ_shazam, R.string.UMessageFaqFA_shazam, 6},
            {R.string.UMessageFaqFQ_proxy_fallback, R.string.UMessageFaqFA_proxy_fallback, 6},
            {R.string.UMessageFaqFQ_instagram_business, R.string.UMessageFaqFA_instagram_business, 6}
    };
    private static final String[] FEATURE_FLAGS = {
            "stranger_protection",
            "chat_trash",
            "privacy_guard",
            "account_switch_pins",
            "apk_block",
            "group_spam_guard",
            "masked_link_guard",
            "nsfw_filter",
            "parental_control",
            "banned_sources",
            "block_ads",
            "save_edited",
            "view_once_save",
            "templates",
            "auto_reply",
            "confirm_sticker",
            "uzbek_speech",
            "offline_translate",
            "swipe_actions",
            "bank_cards",
            "chat_export",
            "unread_reminder",
            "auto_approve",
            "focus_mode",
            "admin_folders",
            "mutual_contact",
            "stories_anonymous",
            "u_stories",
            "story_privacy_batch",
            "story_camera_pro",
            "round_effects",
            "ai_eraser",
            "document_reader",
            "sleep_timer",
            "stop_autodownload",
            "friend_map",
            "nearby_share",
            "shazam",
            "proxy_fallback",
            "instagram_business"
    };

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.UMessageFaq));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        final Theme.ResourcesProvider rp = getResourceProvider();
        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        for (int g = 0; g < GROUPS.length; g++) {
            content.addView(UMessageModernList.header(context, LocaleController.getString(GROUPS[g]), rp));
            LinearLayout card = UMessageModernList.card(context, rp);
            boolean first = true;
            for (int k = 0; k < ITEMS.length; k++) {
                final int[] item = ITEMS[k];
                if (item[2] != g || ITEM_FLAGS[k] != null && !org.telegram.messenger.UMessageRemoteFlags.isEnabled(ITEM_FLAGS[k])) continue;
                if (!first) {
                    View divider = new View(context);
                    divider.setBackgroundColor(getThemedColor(Theme.key_divider));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (AndroidUtilities.density * 0.5f)));
                    lp.setMarginStart(AndroidUtilities.dp(16));
                    card.addView(divider, lp);
                }
                card.addView(question(context, LocaleController.getString(item[0]), LocaleController.getString(item[1])));
                first = false;
            }
            content.addView(card, UMessageModernList.cardParams());
        }
        for (int g = 0; g < FEATURE_GROUPS.length; g++) {
            LinearLayout card = null;
            for (int i = 0; i < FEATURE_ITEMS.length; i++) {
                if (FEATURE_ITEMS[i][2] != g || !org.telegram.messenger.UMessageRemoteFlags.isEnabled(FEATURE_FLAGS[i])) continue;
                if (card == null) {
                    content.addView(UMessageModernList.header(context, LocaleController.getString(FEATURE_GROUPS[g]), rp));
                    card = UMessageModernList.card(context, rp);
                } else {
                    View divider = new View(context);
                    divider.setBackgroundColor(getThemedColor(Theme.key_divider));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) (AndroidUtilities.density * 0.5f)));
                    lp.setMarginStart(AndroidUtilities.dp(16));
                    card.addView(divider, lp);
                }
                card.addView(question(context, LocaleController.getString(FEATURE_ITEMS[i][0]), LocaleController.getString(FEATURE_ITEMS[i][1])));
            }
            if (card != null) content.addView(card, UMessageModernList.cardParams());
        }
        content.addView(UMessageModernList.footer(context, LocaleController.getString(R.string.UMessageUnofficialNote), rp));

        fragmentView = scrollView;
        return fragmentView;
    }

    private View question(Context context, CharSequence q, CharSequence a) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.getSelectorDrawable(getThemedColor(Theme.key_listSelector), false));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(AndroidUtilities.dp(52));

        TextView title = new TextView(context);
        title.setText(q);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 16, 12, 8, 12));

        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.arrow_more);
        arrow.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        head.addView(arrow, LayoutHelper.createLinear(24, 24, 0, 0, 14, 0));
        box.addView(head);

        TextView answer = new TextView(context);
        answer.setText(a);
        answer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        answer.setLineSpacing(AndroidUtilities.dp(3), 1f);
        answer.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        answer.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        answer.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        answer.setVisibility(View.GONE);
        answer.setAlpha(0f);
        box.addView(answer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final ValueAnimator[] running = new ValueAnimator[1];
        final boolean[] open = new boolean[1];
        box.setOnClickListener(v -> {
            open[0] = !open[0];
            if (running[0] != null) {
                final ValueAnimator old = running[0];
                running[0] = null;
                old.cancel();
            }
            arrow.animate().rotation(open[0] ? 180 : 0).setDuration(DURATION).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();

            // measure the answer at the card's width, then animate its height together with a fade
            answer.measure(View.MeasureSpec.makeMeasureSpec(box.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int from = answer.getVisibility() == View.VISIBLE ? answer.getHeight() : 0;
            final int to = open[0] ? answer.getMeasuredHeight() : 0;
            final float alphaFrom = answer.getAlpha();
            final float alphaTo = open[0] ? 1f : 0f;
            final ViewGroup.LayoutParams lp = answer.getLayoutParams();
            lp.height = from;
            answer.setLayoutParams(lp);
            answer.setVisibility(View.VISIBLE);

            final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(DURATION);
            anim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            anim.addUpdateListener(va -> {
                final float t = (float) va.getAnimatedValue();
                lp.height = (int) (from + (to - from) * t);
                answer.setLayoutParams(lp);
                answer.setAlpha(alphaFrom + (alphaTo - alphaFrom) * t);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (running[0] != animation) return;
                    running[0] = null;
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    answer.setLayoutParams(lp);
                    if (!open[0]) answer.setVisibility(View.GONE);
                }
            });
            running[0] = anim;
            anim.start();
        });
        return box;
    }
}
