package com.limelight.preferences;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsPresetController;
import com.limelight.profiles.SettingsProfile;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Horizontal preset carousel embedded in the Artemis Buffered settings category. */
@Keep
public final class SettingsPresetPreference extends Preference
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        ProfilesManager.ProfileChangeListener {
    public static final String KEY = "artemis_settings_preset_carousel";

    private SharedPreferences settings;
    private Set<String> managedKeys;
    private SettingsPresetController controller;
    private Runnable onPresetApplied;
    private PresetAdapter adapter;
    private RecyclerView recyclerView;
    private boolean listenersRegistered;
    private boolean refreshPosted;

    public SettingsPresetPreference(@NonNull Context context) {
        this(context, null);
    }

    public SettingsPresetPreference(@NonNull Context context,
                                    @Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_settings_preset_carousel);
        setSelectable(false);
        setPersistent(false);
    }

    public void configure(@NonNull SharedPreferences settings,
                          @NonNull Set<String> managedKeys,
                          @NonNull Runnable onPresetApplied) {
        unregisterListeners();
        this.settings = settings;
        this.managedKeys = managedKeys;
        this.onPresetApplied = onPresetApplied;
        this.controller = new SettingsPresetController(getContext(), settings, managedKeys);
        this.adapter = new PresetAdapter();
        registerListeners();
        notifyChanged();
    }

    @Override
    public void onAttached() {
        super.onAttached();
        registerListeners();
    }

    @Override
    public void onDetached() {
        unregisterListeners();
        recyclerView = null;
        refreshPosted = false;
        super.onDetached();
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ArtemisPreferenceStyle.bind(getContext(), holder, true, false);

        recyclerView = (RecyclerView) holder.findViewById(R.id.settingsPresetCarousel);
        if (recyclerView == null || controller == null) {
            return;
        }

        if (!(recyclerView.getLayoutManager() instanceof LinearLayoutManager)) {
            recyclerView.setLayoutManager(new LinearLayoutManager(
                    getContext(), RecyclerView.HORIZONTAL, false));
        }
        recyclerView.setAdapter(adapter);
        adapter.refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (managedKeys != null && managedKeys.contains(key)) {
            refreshCards();
        }
    }

    @Override
    public void onProfilesChanged() {
        refreshCards();
    }

    private void registerListeners() {
        if (listenersRegistered || settings == null) {
            return;
        }
        settings.registerOnSharedPreferenceChangeListener(this);
        ProfilesManager.getInstance().addListener(this);
        listenersRegistered = true;
    }

    private void unregisterListeners() {
        if (!listenersRegistered) {
            return;
        }
        if (settings != null) {
            settings.unregisterOnSharedPreferenceChangeListener(this);
        }
        ProfilesManager.getInstance().removeListener(this);
        listenersRegistered = false;
    }

    private void refreshCards() {
        if (adapter != null && recyclerView != null) {
            if (!refreshPosted) {
                refreshPosted = true;
                RecyclerView boundRecyclerView = recyclerView;
                boundRecyclerView.post(() -> {
                    refreshPosted = false;
                    if (adapter != null) {
                        adapter.refresh();
                        boundRecyclerView.requestLayout();
                    }
                });
            }
        }
        else if (adapter != null) {
            adapter.refresh();
        }
        else {
            notifyChanged();
        }
    }

    private void reloadSettingsAfterApply() {
        if (onPresetApplied != null) {
            onPresetApplied.run();
        }
    }

    private final class PresetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_PRESET = 0;
        private static final int TYPE_ADD = 1;
        private final List<SettingsProfile> presets = new ArrayList<>();

        void refresh() {
            if (controller == null) {
                return;
            }
            presets.clear();
            presets.addAll(controller.getPresets());
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return position == presets.size() ? TYPE_ADD : TYPE_PRESET;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_ADD ?
                    R.layout.item_settings_preset_add : R.layout.item_settings_preset;
            View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return viewType == TYPE_ADD ? new AddViewHolder(view) : new PresetViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof PresetViewHolder) {
                ((PresetViewHolder) holder).bind(presets.get(position));
            }
            else {
                ((AddViewHolder) holder).bind();
            }
        }

        @Override
        public int getItemCount() {
            // The add card is deliberately the last item in the horizontal list.
            return presets.size() + 1;
        }

        private final class PresetViewHolder extends RecyclerView.ViewHolder {
            private final View card;
            private final EditText name;
            private final TextView resolution;
            private final TextView fps;
            private final TextView bitrate;
            private final View actions;
            private final AppCompatButton save;
            private final AppCompatButton reset;

            PresetViewHolder(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.settingsPresetCard);
                name = itemView.findViewById(R.id.settingsPresetName);
                resolution = itemView.findViewById(R.id.settingsPresetResolution);
                fps = itemView.findViewById(R.id.settingsPresetFps);
                bitrate = itemView.findViewById(R.id.settingsPresetBitrate);
                actions = itemView.findViewById(R.id.settingsPresetActions);
                save = itemView.findViewById(R.id.settingsPresetSave);
                reset = itemView.findViewById(R.id.settingsPresetReset);
                name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
            }

            void bind(@NonNull SettingsProfile profile) {
                boolean selected = controller.isActive(profile);
                boolean dirty = controller.isDirty(profile);
                String resolutionValue = stringValue(controller.getEffectiveValue(
                        profile, PreferenceConfiguration.RESOLUTION_PREF_STRING),
                        PreferenceConfiguration.DEFAULT_RESOLUTION);
                String fpsValue = stringValue(controller.getEffectiveValue(
                        profile, PreferenceConfiguration.FPS_PREF_STRING),
                        PreferenceConfiguration.DEFAULT_FPS);
                int bitrateKbps = intValue(controller.getEffectiveValue(
                                profile, PreferenceConfiguration.BITRATE_PREF_STRING),
                        PreferenceConfiguration.getDefaultBitrate(resolutionValue, fpsValue));
                String bitrateMbps = formatBitrateMbps(bitrateKbps);

                name.setText(profile.getName());
                name.setSelection(name.getText().length());
                name.setOnEditorActionListener((view, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        commitName(profile);
                        view.clearFocus();
                        return true;
                    }
                    return false;
                });
                name.setOnFocusChangeListener((view, hasFocus) -> {
                    if (hasFocus) {
                        name.setSelection(name.getText().length());
                    }
                    else {
                        commitName(profile);
                    }
                });

                resolution.setText(getContext().getString(
                        R.string.settings_preset_resolution, resolutionValue));
                fps.setText(getContext().getString(R.string.settings_preset_fps, fpsValue));
                bitrate.setText(getContext().getString(
                        R.string.settings_preset_bitrate, bitrateMbps));
                // Reserve the action row's height so RecyclerView's horizontal layout cannot
                // clip buttons that become visible after a setting changes.
                actions.setVisibility(selected && dirty ? View.VISIBLE : View.INVISIBLE);

                applyCardBackground(card, selected);
                card.setActivated(selected);
                card.setContentDescription(getContext().getString(
                        R.string.settings_preset_card_description,
                        profile.getName(), resolutionValue, fpsValue, bitrateMbps,
                        selected ? getContext().getString(R.string.settings_preset_selected) : ""));
                card.setOnClickListener(view -> {
                    if (!controller.isActive(profile)) {
                        controller.selectPreset(profile);
                        Toast.makeText(getContext(),
                                getContext().getString(R.string.settings_preset_switched,
                                        profile.getName()),
                                Toast.LENGTH_SHORT).show();
                        reloadSettingsAfterApply();
                    }
                });
                card.setOnLongClickListener(view -> {
                    showDeleteConfirmation(profile);
                    return true;
                });

                save.setOnClickListener(view -> {
                    controller.savePreset(profile);
                    Toast.makeText(getContext(), R.string.settings_preset_saved,
                            Toast.LENGTH_SHORT).show();
                });
                reset.setOnClickListener(view -> {
                    controller.resetPreset(profile);
                    Toast.makeText(getContext(), R.string.settings_preset_reset_done,
                            Toast.LENGTH_SHORT).show();
                    reloadSettingsAfterApply();
                });
            }

            private void showDeleteConfirmation(@NonNull SettingsProfile profile) {
                new AlertDialog.Builder(getContext())
                        .setTitle(R.string.settings_preset_delete_title)
                        .setMessage(getContext().getString(
                                R.string.settings_preset_delete_confirmation,
                                profile.getName()))
                        .setPositiveButton(R.string.settings_preset_delete,
                                (dialog, which) -> {
                                    controller.deletePreset(profile);
                                    Toast.makeText(getContext(),
                                            getContext().getString(
                                                    R.string.settings_preset_deleted,
                                                    profile.getName()),
                                            Toast.LENGTH_SHORT).show();
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }

            private void commitName(SettingsProfile profile) {
                String editedName = name.getText().toString().trim();
                if (editedName.isEmpty()) {
                    name.setText(profile.getName());
                    name.setSelection(name.getText().length());
                    Toast.makeText(getContext(), R.string.settings_preset_name_required,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                controller.renamePreset(profile, editedName);
            }
        }

        private final class AddViewHolder extends RecyclerView.ViewHolder {
            private final View card;

            AddViewHolder(@NonNull View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.settingsPresetAddCard);
            }

            void bind() {
                applyCardBackground(card, false);
                card.setOnClickListener(view -> {
                    int number = presets.size() + 1;
                    SettingsProfile profile = controller.addPreset(getContext().getString(
                            R.string.settings_preset_default_name, number));
                    Toast.makeText(getContext(),
                            getContext().getString(R.string.settings_preset_added,
                                    profile.getName()),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                    if (recyclerView != null) {
                        RecyclerView carousel = recyclerView;
                        carousel.post(() -> carousel.smoothScrollToPosition(
                                Math.max(0, presets.size() - 1)));
                    }
                });
            }
        }
    }

    private void applyCardBackground(View view, boolean selected) {
        int background = ContextCompat.getColor(getContext(),
                selected ? R.color.settingsPresetCardSelected : R.color.settingsPresetCard);
        int stroke = ContextCompat.getColor(getContext(),
                selected ? R.color.artemisBufferedPreferenceText :
                        R.color.artemisBufferedPreferenceSummary);
        float density = getContext().getResources().getDisplayMetrics().density;
        GradientDrawable card = new GradientDrawable();
        card.setColor(background);
        card.setCornerRadius(12 * density);
        card.setStroke(Math.max(1, Math.round((selected ? 2 : 1) * density)), stroke);
        ColorStateList ripple = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(stroke, 0x35));
        ViewCompat.setBackground(view, new RippleDrawable(ripple, card, null));
    }

    private String formatBitrateMbps(int bitrateKbps) {
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(3);
        format.setGroupingUsed(false);
        return format.format(bitrateKbps / 1000.0);
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
}
