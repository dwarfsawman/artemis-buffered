package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

@Keep
public class ArtemisPreference extends Preference {
    public ArtemisPreference(@NonNull Context context) {
        super(context);
    }

    public ArtemisPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ArtemisPreferenceStyle.bind(getContext(), holder, true, false);
    }
}
