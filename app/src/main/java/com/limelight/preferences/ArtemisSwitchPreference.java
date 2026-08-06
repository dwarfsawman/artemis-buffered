package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

@Keep
public class ArtemisSwitchPreference extends SwitchPreferenceCompat {
    public ArtemisSwitchPreference(@NonNull Context context) {
        super(context);
    }

    public ArtemisSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ArtemisPreferenceStyle.bind(getContext(), holder, true, true);
    }
}
