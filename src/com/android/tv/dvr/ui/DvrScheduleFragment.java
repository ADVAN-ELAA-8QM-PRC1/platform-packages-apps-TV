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
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v17.leanback.app.GuidedStepFragment;
import android.support.v17.leanback.widget.GuidanceStylist.Guidance;
import android.support.v17.leanback.widget.GuidedAction;
import android.text.format.DateUtils;
import android.widget.Toast;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.data.Program;
import com.android.tv.dvr.DvrManager;
import com.android.tv.dvr.DvrUiHelper;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.dvr.SeriesRecording;
import com.android.tv.dvr.SeriesRecordingScheduler.ProgramLoadCallback;
import com.android.tv.dvr.ui.DvrConflictFragment.DvrProgramConflictFragment;
import com.android.tv.util.Utils;

import java.util.List;

/**
 * A fragment which asks the user the type of the recording.
 * <p>
 * The program should be episodic and the series recording should not had been created yet.
 */
@TargetApi(Build.VERSION_CODES.N)
public class DvrScheduleFragment extends DvrGuidedStepFragment {
    private static final int ACTION_RECORD_EPISODE = 1;
    private static final int ACTION_RECORD_SERIES = 2;

    private Program mProgram;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Bundle args = getArguments();
        if (args != null) {
            mProgram = args.getParcelable(DvrHalfSizedDialogFragment.KEY_PROGRAM);
        }
        DvrManager dvrManager = TvApplication.getSingletons(getContext()).getDvrManager();
        SoftPreconditions.checkArgument(mProgram != null && mProgram.isEpisodic()
                && dvrManager.getSeriesRecording(mProgram) == null);
        super.onCreate(savedInstanceState);
    }

    @Override
    public int onProvideTheme() {
        return R.style.Theme_TV_Dvr_GuidedStep_Twoline_Action;
    }

    @NonNull
    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String title = getString(R.string.dvr_schedule_dialog_title);
        Drawable icon = getResources().getDrawable(R.drawable.ic_dvr, null);
        return new Guidance(title, null, null, icon);
    }

    @Override
    public void onCreateActions(@NonNull List<GuidedAction> actions, Bundle savedInstanceState) {
        Context context = getContext();
        String description;
        if (mProgram.getStartTimeUtcMillis() <= System.currentTimeMillis()) {
            description = getString(R.string.dvr_action_record_episode_from_now_description,
                    DateUtils.formatDateTime(context, mProgram.getEndTimeUtcMillis(),
                            DateUtils.FORMAT_SHOW_TIME));
        } else {
            description = Utils.getDurationString(context, mProgram.getStartTimeUtcMillis(),
                    mProgram.getEndTimeUtcMillis(), true);
        }
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_RECORD_EPISODE)
                .title(R.string.dvr_action_record_episode)
                .description(description)
                .build());
        actions.add(new GuidedAction.Builder(context)
                .id(ACTION_RECORD_SERIES)
                .title(R.string.dvr_action_record_series)
                .description(mProgram.getTitle())
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (action.getId() == ACTION_RECORD_EPISODE) {
            getDvrManager().addSchedule(mProgram);
            List<ScheduledRecording> conflicts = getDvrManager().getConflictingSchedules(mProgram);
            if (conflicts.isEmpty()) {
                DvrUiHelper.showAddScheduleToast(getContext(), mProgram.getTitle(),
                        mProgram.getStartTimeUtcMillis(), mProgram.getEndTimeUtcMillis());
                dismissDialog();
            } else {
                GuidedStepFragment fragment = new DvrProgramConflictFragment();
                Bundle args = new Bundle();
                args.putParcelable(DvrHalfSizedDialogFragment.KEY_PROGRAM, mProgram);
                fragment.setArguments(args);
                GuidedStepFragment.add(getFragmentManager(), fragment,
                        R.id.halfsized_dialog_host);
            }
        } else if (action.getId() == ACTION_RECORD_SERIES) {
            ProgressDialog dialog = ProgressDialog.show(getContext(), null,
                    getString(R.string.dvr_schedule_progress_message_reading_programs));
            getDvrManager().queryProgramsForSeries(mProgram, new ProgramLoadCallback() {
                @Override
                public void onProgramLoadFinished(@NonNull List<Program> programs) {
                    dialog.dismiss();
                    // TODO: Create series recording in series settings fragment.
                    SeriesRecording seriesRecording =
                            getDvrManager().addSeriesRecording(mProgram, programs);
                    DvrUiHelper.startSeriesSettingsActivity(getContext(), seriesRecording.getId());
                    dismissDialog();
                }
            });
        }
    }
}
