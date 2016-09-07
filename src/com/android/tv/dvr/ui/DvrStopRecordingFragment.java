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
 * limitations under the License.
 */

package com.android.tv.dvr.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v17.leanback.widget.GuidanceStylist.Guidance;
import android.support.v17.leanback.widget.GuidedAction;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.dvr.DvrDataManager;
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener;
import com.android.tv.dvr.ScheduledRecording;

import java.util.List;

/**
 * A fragment which asks the user to make a recording schedule for the program.
 * <p>
 * If the program belongs to a series and the series recording is not created yet, we will show the
 * option to record all the episodes of the series.
 */
@TargetApi(Build.VERSION_CODES.N)
public class DvrStopRecordingFragment extends DvrGuidedStepFragment {
    private static final int ACTION_STOP = 1;

    private ScheduledRecording mSchedule;
    private DvrDataManager mDvrDataManager;
    private final ScheduledRecordingListener mScheduledRecordingListener =
            new ScheduledRecordingListener() {
                @Override
                public void onScheduledRecordingAdded(ScheduledRecording... schedules) { }

                @Override
                public void onScheduledRecordingRemoved(ScheduledRecording... schedules) {
                    for (ScheduledRecording schedule : schedules) {
                        if (schedule.getId() == mSchedule.getId()) {
                            dismissDialog();
                            return;
                        }
                    }
                }

                @Override
                public void onScheduledRecordingStatusChanged(ScheduledRecording... schedules) {
                    for (ScheduledRecording schedule : schedules) {
                        if (schedule.getId() == mSchedule.getId()
                                && schedule.getState()
                                != ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                            dismissDialog();
                            return;
                        }
                    }
                }
            };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        Bundle args = getArguments();
        long channelId = args.getLong(DvrHalfSizedDialogFragment.KEY_CHANNEL_ID);
        mSchedule = getDvrManager().getCurrentRecording(channelId);
        if (mSchedule == null) {
            dismissDialog();
            return;
        }
        mDvrDataManager = TvApplication.getSingletons(context).getDvrDataManager();
        mDvrDataManager.addScheduledRecordingListener(mScheduledRecordingListener);
    }

    @Override
    public void onDetach() {
        if (mDvrDataManager != null) {
            mDvrDataManager.removeScheduledRecordingListener(mScheduledRecordingListener);
        }
        super.onDetach();
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getString(R.string.dvr_stop_recording_dialog_title);
        String description = getString(R.string.dvr_stop_recording_dialog_description);
        Drawable image = getResources().getDrawable(R.drawable.ic_warning_white_96dp, null);
        return new Guidance(title, description, null, image);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = getContext();
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_STOP)
                .title(R.string.dvr_action_stop)
                .build());
        actions.add(new GuidedAction.Builder(context)
                .clickAction(GuidedAction.ACTION_ID_CANCEL)
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_STOP) {
            getDvrManager().stopRecording(mSchedule);
        }
        dismissDialog();
    }
}
