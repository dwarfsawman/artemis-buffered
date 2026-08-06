package com.limelight.preferences;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceViewHolder;

import com.limelight.R;

final class ArtemisPreferenceStyle {
    private ArtemisPreferenceStyle() {
    }

    static void bind(Context context, PreferenceViewHolder holder,
                     boolean decorateRow, boolean tintSwitch) {
        int accent = ContextCompat.getColor(context,
                R.color.artemisBufferedPreferenceAccent);
        int summary = ContextCompat.getColor(context,
                R.color.artemisBufferedPreferenceSummary);
        int text = ContextCompat.getColor(context,
                R.color.artemisBufferedPreferenceText);

        TextView titleView = (TextView) holder.findViewById(android.R.id.title);
        if (titleView != null) {
            titleView.setTextColor(decorateRow ? text : accent);
            titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        }

        TextView summaryView = (TextView) holder.findViewById(android.R.id.summary);
        if (summaryView != null) {
            summaryView.setTextColor(summary);
        }

        if (decorateRow) {
            int background = ContextCompat.getColor(context,
                    R.color.artemisBufferedPreferenceBackground);
            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable card = new GradientDrawable();
            card.setColor(background);
            card.setCornerRadius(10 * density);
            card.setStroke(Math.max(1, Math.round(density)),
                    ColorUtils.blendARGB(background, text, 0.35f));

            ColorStateList rippleColor = ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(text, 0x30));
            ViewCompat.setBackground(holder.itemView,
                    new RippleDrawable(rippleColor, card, null));
        }

        if (tintSwitch) {
            tintSwitches(holder.itemView, text, summary, backgroundColor(context));
        }
    }

    private static int backgroundColor(Context context) {
        return ContextCompat.getColor(context,
                R.color.artemisBufferedPreferenceBackground);
    }

    private static void tintSwitches(View view, int text, int summary, int background) {
        if (view instanceof SwitchCompat) {
            SwitchCompat switchView = (SwitchCompat) view;
            int[][] states = {
                    new int[]{android.R.attr.state_checked},
                    new int[]{},
            };
            switchView.setThumbTintList(new ColorStateList(states, new int[]{
                    text,
                    ColorUtils.blendARGB(summary, background, 0.35f),
            }));
            switchView.setTrackTintList(new ColorStateList(states, new int[]{
                    ColorUtils.setAlphaComponent(text, 0x80),
                    ColorUtils.setAlphaComponent(summary, 0x70),
            }));
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintSwitches(group.getChildAt(i), text, summary, background);
            }
        }
    }
}
