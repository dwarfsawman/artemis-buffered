package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

@Keep
public class ArtemisSeekBarPreference extends SeekBarPreference {
    public ArtemisSeekBarPreference(@NonNull Context context) {
        super(context);
    }

    public ArtemisSeekBarPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ArtemisPreferenceStyle.bind(getContext(), holder, true, false);
    }
}
