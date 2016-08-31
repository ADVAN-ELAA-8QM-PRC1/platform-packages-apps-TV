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
import android.support.v17.leanback.widget.ClassPresenterSelector;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.dvr.DvrManager;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.dvr.SeriesRecording;
import com.android.tv.dvr.ui.list.SchedulesHeaderRow.SeriesRecordingHeaderRow;
import com.android.tv.util.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An adapter for series schedule row.
 */
public class SeriesScheduleRowAdapter extends ScheduleRowAdapter {
    private static final String TAG = "SeriesScheduleRowAdapter";

    private SeriesRecording mSeriesRecording;

    public SeriesScheduleRowAdapter(Context context,
            ClassPresenterSelector classPresenterSelector, SeriesRecording seriesRecording) {
        super(context, classPresenterSelector);
        mSeriesRecording = seriesRecording;
    }

    @Override
    public void start() {
        List<ScheduledRecording> recordings = TvApplication.getSingletons(getContext())
                .getDvrDataManager().getAvailableAndCanceledScheduledRecordings();
        List<ScheduledRecording> seriesScheduledRecordings = new ArrayList<>();
        if (mSeriesRecording == null) {
            return;
        }
        for (ScheduledRecording recording : recordings) {
            if (recording.getSeriesRecordingId() == mSeriesRecording.getId()) {
                seriesScheduledRecordings.add(recording);
            }
        }
        Collections.sort(seriesScheduledRecordings,
                ScheduledRecording.START_TIME_THEN_PRIORITY_COMPARATOR);
        int dayCountToLastRecording = 0;
        if (!seriesScheduledRecordings.isEmpty()) {
            long lastRecordingStartTimeMs = seriesScheduledRecordings
                    .get(seriesScheduledRecordings.size() - 1).getStartTimeMs();
            dayCountToLastRecording = Utils.computeDateDifference(System.currentTimeMillis(),
                    lastRecordingStartTimeMs) + 1;
        }
        SchedulesHeaderRow headerRow = new SeriesRecordingHeaderRow(mSeriesRecording.getTitle(),
                getContext().getResources().getQuantityString(
                R.plurals.dvr_series_schedules_header_description, dayCountToLastRecording,
                dayCountToLastRecording), seriesScheduledRecordings.size(), mSeriesRecording);
        add(headerRow);
        for (ScheduledRecording recording : seriesScheduledRecordings) {
            add(new ScheduleRow(recording, headerRow));
        }
    }

    @Override
    public void stop() {
        SoftPreconditions.checkState(get(0) instanceof SchedulesHeaderRow, TAG,
                "First row is not SchedulesHeaderRow");
        boolean cancelAll = size() > 0 && ((SeriesRecordingHeaderRow) get(0)).isCancelAllChecked();
        if (!cancelAll) {
            DvrManager dvrManager = TvApplication.getSingletons(getContext()).getDvrManager();
            for (int i = 0; i < size(); i++) {
                if (get(i) instanceof ScheduleRow) {
                    ScheduleRow scheduleRow = (ScheduleRow) get(i);
                    if (scheduleRow.isRemoveScheduleChecked()) {
                        dvrManager.removeScheduledRecording(scheduleRow.getRecording());
                    }
                }
            }
        }
    }

    @Override
    protected void addScheduleRow(ScheduledRecording recording) {
        if (recording != null && recording.getSeriesRecordingId() == mSeriesRecording.getId()) {
            int index = 0;
            for (; index < size(); index++) {
                if (get(index) instanceof ScheduleRow) {
                    ScheduleRow scheduleRow = (ScheduleRow) get(index);
                    if (ScheduledRecording.START_TIME_THEN_PRIORITY_COMPARATOR.compare(
                            scheduleRow.getRecording(), recording) > 0) {
                        break;
                    }
                }
            }
            SoftPreconditions.checkState(get(0) instanceof SchedulesHeaderRow, TAG,
                    "First row is not SchedulesHeaderRow");
            if (index == 0) {
                index++;
            }
            SchedulesHeaderRow headerRow = (SchedulesHeaderRow) get(0);
            headerRow.setItemCount(headerRow.getItemCount() + 1);
            ScheduleRow addedRow = new ScheduleRow(recording, headerRow);
            add(index, addedRow);
            updateHeaderRowDescription(headerRow);
        }
    }

    @Override
    protected void removeScheduleRow(ScheduleRow scheduleRow) {
        if (scheduleRow != null) {
            remove(scheduleRow);
            SchedulesHeaderRow headerRow = scheduleRow.getHeaderRow();
            // Changes the count information of header which the removed row belongs to.
            if (headerRow != null) {
                headerRow.setItemCount(headerRow.getItemCount() - 1);
                if (headerRow.getItemCount() == 0) {
                   // TODO: Add a emtpy view.
                } else if (get(size() - 1) instanceof ScheduleRow) {
                    updateHeaderRowDescription(headerRow);
                }
            }
        }
    }

    @Override
    protected boolean willBeKept(ScheduledRecording recording) {
        return super.willBeKept(recording)
                || recording.getState() == ScheduledRecording.STATE_RECORDING_CANCELED;
    }

    private void updateHeaderRowDescription(SchedulesHeaderRow headerRow) {
        int nextDays = Utils.computeDateDifference(System.currentTimeMillis(),
                ((ScheduleRow) get(size() - 1)).getRecording().getStartTimeMs()) + 1;
        headerRow.setDescription(getContext().getResources()
                .getQuantityString(R.plurals.dvr_series_schedules_header_description,
                nextDays, nextDays));
        replace(indexOf(headerRow), headerRow);
    }
}
