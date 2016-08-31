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
import android.support.annotation.IntDef;
import android.support.v17.leanback.app.DetailsFragment;

import com.android.tv.R;
import com.android.tv.dvr.ui.list.DvrSchedulesFragment;
import com.android.tv.dvr.ui.list.DvrSeriesSchedulesFragment;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Activity to show the list of recording schedules.
 */
public class DvrSchedulesActivity extends Activity {
    /**
     * The key for the type of the schedules which will be listed in the list. The type of the value
     * should be {@link ScheduleListType}.
     */
    public static final String KEY_SCHEDULES_TYPE = "schedules_type";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TYPE_FULL_SCHEDULE, TYPE_SERIES_SCHEDULE})
    public @interface ScheduleListType {}
    /**
     * A type which means the activity will display the full scheduled recordings.
     */
    public static final int TYPE_FULL_SCHEDULE = 0;
    /**
     * A type which means the activity will display a scheduled recording list of a series
     * recording.
     */
    public final static int TYPE_SERIES_SCHEDULE = 1;

    private Runnable mCancelAllClickedRunnable;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dvr_schedules);
        if (savedInstanceState == null) {
            int schedulesType = getIntent().getIntExtra(KEY_SCHEDULES_TYPE, TYPE_FULL_SCHEDULE);
            DetailsFragment schedulesFragment = null;
            if (schedulesType == TYPE_FULL_SCHEDULE) {
                schedulesFragment = new DvrSchedulesFragment();
                schedulesFragment.setArguments(getIntent().getExtras());
            } else if (schedulesType == TYPE_SERIES_SCHEDULE) {
                schedulesFragment = new DvrSeriesSchedulesFragment();
                schedulesFragment.setArguments(getIntent().getExtras());
            }
            if (schedulesFragment != null) {
                getFragmentManager().beginTransaction().add(
                        R.id.fragment_container, schedulesFragment).commit();
            } else {
                finish();
            }
        }
    }

    /**
     * Sets cancel all runnable which will implement operations after clicking cancel all dialog.
     */
    public void setCancelAllClickedRunnable(Runnable cancelAllClickedRunnable) {
        mCancelAllClickedRunnable = cancelAllClickedRunnable;
    }

    /**
     * Operations after clicking the cancel all.
     */
    public void onCancelAllClicked() {
        if (mCancelAllClickedRunnable != null) {
            mCancelAllClickedRunnable.run();
        }
    }
}
