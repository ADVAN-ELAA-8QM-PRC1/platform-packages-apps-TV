/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tv.dvr.ui;

import android.app.Activity;
import android.os.Bundle;
import android.support.v17.leanback.app.GuidedStepFragment;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.ui.sidepanel.SettingsFragment;

/**
 * Activity to show details view in DVR.
 */
public class DvrSeriesSettingsActivity extends Activity {
    /**
     * Name of series id added to the Intent.
     */
    public static final String SERIES_RECORDING_ID = "series_recording_id";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dvr_series_settings);
        long seriesRecordingId = getIntent().getLongExtra(SERIES_RECORDING_ID, -1);
        SoftPreconditions.checkArgument(seriesRecordingId != -1);

        if (savedInstanceState == null) {
            Bundle args = new Bundle();
            args.putLong(SeriesSettingsFragment.SERIES_RECORDING_ID, seriesRecordingId);
            SeriesSettingsFragment settingFragment = new SeriesSettingsFragment();
            settingFragment.setArguments(args);
            GuidedStepFragment.addAsRoot(this, settingFragment, R.id.dvr_settings_view_frame);
        }
    }
}
