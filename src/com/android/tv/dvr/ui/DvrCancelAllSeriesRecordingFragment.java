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
import android.app.DialogFragment;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v17.leanback.widget.GuidanceStylist;
import android.support.v17.leanback.widget.GuidedAction;

import com.android.tv.R;

import java.util.List;

/**
 * A fragment which asks the user to cancel all series schedules recordings.
 */
public class DvrCancelAllSeriesRecordingFragment extends DvrGuidedStepFragment {
    private static final int ACTION_CANCEL_ALL = 1;
    private static final int ACTION_BACK = 2;

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getResources().getString(R.string.dvr_series_schedules_dialog_cancel_all);
        Drawable icon = getContext().getDrawable(R.drawable.ic_dvr_delete);
        return new GuidanceStylist.Guidance(title, null, null, icon);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        Activity activity = getActivity();
        actions.add(new GuidedAction.Builder(activity)
                .id(ACTION_CANCEL_ALL)
                .title(getResources().getString(R.string.dvr_series_schedules_cancel_all))
                .build());
        actions.add(new GuidedAction.Builder(activity)
                .id(ACTION_BACK)
                .title(getResources().getString(R.string.dvr_series_schedules_dialog_back))
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        DvrSchedulesActivity activity = (DvrSchedulesActivity) getActivity();
        if (action.getId() == ACTION_CANCEL_ALL) {
            activity.onCancelAllClicked();
        }
        dismissDialog();
    }
}
