package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Trampoline activity directing the user to the main PC list. */
public class LaunchTrampoline extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent target = new Intent(this, PcView.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(target);
        finish();
    }
}
