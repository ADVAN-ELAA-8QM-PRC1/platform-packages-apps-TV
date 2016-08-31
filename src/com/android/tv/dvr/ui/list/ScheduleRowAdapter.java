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
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.ClassPresenterSelector;
import android.text.format.DateUtils;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.dvr.ui.list.SchedulesHeaderRow.DateHeaderRow;
import com.android.tv.util.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * An adapter for {@link ScheduleRow}.
 */
public class ScheduleRowAdapter extends ArrayObjectAdapter {
    private final static long ONE_DAY_MS = TimeUnit.DAYS.toMillis(1);

    private Context mContext;
    private final List<String> mTitles = new ArrayList<>();

    public ScheduleRowAdapter(Context context, ClassPresenterSelector classPresenterSelector) {
        super(classPresenterSelector);
        mContext = context;
        mTitles.add(mContext.getString(R.string.dvr_date_today));
        mTitles.add(mContext.getString(R.string.dvr_date_tomorrow));
    }

    /**
     * Returns context.
     */
    protected Context getContext() {
        return mContext;
    }

    /**
     * Starts schedule row adapter.
     */
    public void start() {
        clear();
        List<ScheduledRecording> recordingList = TvApplication.getSingletons(mContext)
                .getDvrDataManager().getNonStartedScheduledRecordings();
        recordingList.addAll(TvApplication.getSingletons(mContext).getDvrDataManager()
                .getStartedRecordings());
        Collections.sort(recordingList, ScheduledRecording.START_TIME_THEN_PRIORITY_COMPARATOR);
        long deadLine = Utils.getLastMillisecondOfDay(System.currentTimeMillis());
        for (int i = 0; i < recordingList.size();) {
            ArrayList<ScheduledRecording> section = new ArrayList<>();
            while (i < recordingList.size() && recordingList.get(i).getStartTimeMs() < deadLine) {
                section.add(recordingList.get(i++));
            }
            if (!section.isEmpty()) {
                SchedulesHeaderRow headerRow = new DateHeaderRow(calculateHeaderDate(deadLine),
                        mContext.getResources().getQuantityString(
                        R.plurals.dvr_schedules_section_subtitle, section.size(), section.size()),
                        section.size(), deadLine);
                add(headerRow);
                for(ScheduledRecording recording : section){
                    add(new ScheduleRow(recording, headerRow));
                }
            }
            deadLine += ONE_DAY_MS;
        }
    }

    private String calculateHeaderDate(long deadLine) {
        int titleIndex = (int) ((deadLine -
                Utils.getLastMillisecondOfDay(System.currentTimeMillis())) / ONE_DAY_MS);
        String headerDate;
        if (titleIndex < mTitles.size()) {
            headerDate = mTitles.get(titleIndex);
        } else {
            headerDate = DateUtils.formatDateTime(getContext(), deadLine,
                     DateUtils.FORMAT_SHOW_WEEKDAY| DateUtils.FORMAT_ABBREV_WEEKDAY
                     | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
        }
        return headerDate;
    }

    /**
     * Stops schedules row adapter.
     */
    public void stop() {
        // TODO: Deal with other type of operation.
        for (int i = 0; i < size(); i++) {
            if (get(i) instanceof ScheduleRow) {
                ScheduleRow scheduleRow = (ScheduleRow) get(i);
                if (scheduleRow.isRemoveScheduleChecked()) {
                    TvApplication.getSingletons(mContext).getDvrManager()
                            .removeScheduledRecording(scheduleRow.getRecording());
                }
            }
        }
    }

    /**
     * Gets which {@link ScheduleRow} the {@link ScheduledRecording} belongs to.
     */
    public ScheduleRow findRowByScheduledRecording(ScheduledRecording recording) {
        if (recording == null) {
            return null;
        }
        for (int i = 0; i < size(); i++) {
            Object item = get(i);
            if (item instanceof ScheduleRow) {
                if (((ScheduleRow) item).getRecording().getId() == recording.getId()) {
                    return (ScheduleRow) item;
                }
            }
        }
        return null;
    }

    /**
     * Adds a {@link ScheduleRow} by {@link ScheduledRecording} and update
     * {@link SchedulesHeaderRow} information.
     */
    protected void addScheduleRow(ScheduledRecording recording) {
        if (recording != null) {
            int pre = -1;
            int index = 0;
            for (; index < size(); index++) {
                if (get(index) instanceof ScheduleRow) {
                    ScheduleRow scheduleRow = (ScheduleRow) get(index);
                    if (ScheduledRecording.START_TIME_THEN_PRIORITY_COMPARATOR.compare(
                            scheduleRow.getRecording(), recording) > 0) {
                        break;
                    }
                    pre = index;
                }
            }
            long deadLine = Utils.getLastMillisecondOfDay(recording.getStartTimeMs());
            if (pre >= 0 && getHeaderRow(pre).getDeadLineMs() == deadLine) {
                SchedulesHeaderRow headerRow = ((ScheduleRow) get(pre)).getHeaderRow();
                headerRow.setItemCount(headerRow.getItemCount() + 1);
                ScheduleRow addedRow = new ScheduleRow(recording, headerRow);
                add(++pre, addedRow);
            } else if (index < size() && getHeaderRow(index).getDeadLineMs() == deadLine) {
                SchedulesHeaderRow headerRow = ((ScheduleRow) get(index)).getHeaderRow();
                headerRow.setItemCount(headerRow.getItemCount() + 1);
                ScheduleRow addedRow = new ScheduleRow(recording, headerRow);
                add(index, addedRow);
            } else {
                SchedulesHeaderRow headerRow = new DateHeaderRow(calculateHeaderDate(deadLine),
                        mContext.getResources().getQuantityString(
                        R.plurals.dvr_schedules_section_subtitle, 1, 1), 1, deadLine);
                add(++pre, headerRow);
                ScheduleRow addedRow = new ScheduleRow(recording, headerRow);
                add(pre, addedRow);
            }
        }
    }

    private DateHeaderRow getHeaderRow(int index) {
        return ((DateHeaderRow) ((ScheduleRow) get(index)).getHeaderRow());
    }

    /**
     * Removes {@link ScheduleRow} and update {@link SchedulesHeaderRow} information.
     */
    protected void removeScheduleRow(ScheduleRow scheduleRow) {
        if (scheduleRow != null) {
            SchedulesHeaderRow headerRow = scheduleRow.getHeaderRow();
            remove(scheduleRow);
            // Changes the count information of header which the removed row belongs to.
            if (headerRow != null) {
                int currentCount = headerRow.getItemCount();
                headerRow.setItemCount(--currentCount);
                if (headerRow.getItemCount() == 0) {
                    remove(headerRow);
                } else {
                    headerRow.setDescription(mContext.getResources().getQuantityString(
                            R.plurals.dvr_schedules_section_subtitle,
                            headerRow.getItemCount(), headerRow.getItemCount()));
                    replace(indexOf(headerRow), headerRow);
                }
            }
        }
    }

    /**
     * Called when a schedule recording is added to dvr date manager.
     */
    public void onScheduledRecordingAdded(ScheduledRecording recording) {
        if (recording.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED
                || recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
            addScheduleRow(recording);
            notifyArrayItemRangeChanged(0, size());
        }
    }

    /**
     * Called when a schedule recording is removed from dvr date manager.
     */
    public void onScheduledRecordingRemoved(ScheduledRecording recording) {
        ScheduleRow scheduleRow = findRowByScheduledRecording(recording);
        if (scheduleRow != null) {
            removeScheduleRow(scheduleRow);
        }
        notifyArrayItemRangeChanged(0, size());
    }

    /**
     * Called when a schedule recording is updated in dvr date manager.
     */
    public void onScheduledRecordingUpdated(ScheduledRecording recording) {
        ScheduleRow scheduleRow = findRowByScheduledRecording(recording);
        if (scheduleRow != null) {
            scheduleRow.setRecording(recording);
            if (!willBeKept(recording)) {
                removeScheduleRow(scheduleRow);
            }
        } else if (willBeKept(recording)) {
            addScheduleRow(recording);
        }
        notifyArrayItemRangeChanged(0, size());
    }

    /**
     * To check whether the recording should be kept or not.
     */
    protected boolean willBeKept(ScheduledRecording recording) {
        return recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS
                || recording.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED;
    }
}
