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

package com.android.tv.dvr.ui.list;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.android.tv.R;
import com.android.tv.dvr.DvrUiHelper;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.dvr.ui.list.SchedulesHeaderRow.SeriesRecordingHeaderRow;
import com.android.tv.util.Utils;

/**
 * A RowPresenter for series schedule row.
 */
public class SeriesScheduleRowPresenter extends ScheduleRowPresenter {
    private boolean mIsCancelAll;
    private boolean mLtr;

    public SeriesScheduleRowPresenter(Context context) {
        super(context);
        mLtr = context.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;
    }

    public static class SeriesScheduleRowViewHolder extends ScheduleRowViewHolder {
        public SeriesScheduleRowViewHolder(View view) {
            super(view);
            ViewGroup.LayoutParams lp = getTimeView().getLayoutParams();
            lp.width = view.getResources().getDimensionPixelSize(
                    R.dimen.dvr_series_schedules_item_time_width);
            getTimeView().setLayoutParams(lp);
        }
    }

    @Override
    protected ScheduleRowViewHolder onGetScheduleRowViewHolder(View view) {
        return new SeriesScheduleRowViewHolder(view);
    }

    @Override
    protected String onGetRecordingTimeText(ScheduledRecording recording) {
        return Utils.getDurationString(getContext(),
                recording.getStartTimeMs(), recording.getEndTimeMs(), false, true, true, 0);
    }

    @Override
    protected String onGetProgramInfoText(ScheduledRecording recording) {
        if (recording != null) {
            return recording.getEpisodeDisplayTitle(getContext());
        }
        return null;
    }

    @Override
    protected void onBindRowViewHolderInternal(ScheduleRowViewHolder viewHolder,
            ScheduleRow scheduleRow) {
        mIsCancelAll = ((SeriesRecordingHeaderRow) scheduleRow.getHeaderRow()).isCancelAllChecked();
        boolean isConflicting = getConflicts().contains(scheduleRow.getRecording());
        if (mIsCancelAll || isConflicting || scheduleRow.isRemoveScheduleChecked()) {
            viewHolder.greyOutInfo();
        } else {
            viewHolder.whiteBackInfo();
        }
        if (!mIsCancelAll && isConflicting) {
            viewHolder.getProgramTitleView().setCompoundDrawablePadding(getContext()
                    .getResources().getDimensionPixelOffset(
                    R.dimen.dvr_schedules_warning_icon_padding));
            if (mLtr) {
                viewHolder.getProgramTitleView().setCompoundDrawablesWithIntrinsicBounds(
                        R.drawable.ic_warning_gray600_36dp, 0, 0, 0);
            } else {
                viewHolder.getProgramTitleView().setCompoundDrawablesWithIntrinsicBounds(
                        0, 0, R.drawable.ic_warning_gray600_36dp, 0);
            }
        } else {
            viewHolder.getProgramTitleView().setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }
        if (mIsCancelAll) {
            viewHolder.getInfoContainer().setClickable(false);
            viewHolder.getDeleteActionContainer().setVisibility(View.GONE);
        }
    }

    @Override
    protected void onRowViewSelectedInternal(ViewHolder vh, boolean selected) {
        ScheduleRowViewHolder viewHolder = (ScheduleRowViewHolder) vh;
        if (!mIsCancelAll) {
            if (selected) {
                viewHolder.getDeleteActionContainer().setVisibility(View.VISIBLE);
            } else {
                viewHolder.getDeleteActionContainer().setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onInfoClicked(ScheduleRow scheduleRow) {
        DvrUiHelper.startSchedulesActivity(getContext(), scheduleRow.getRecording());
    }
}
