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

import com.android.tv.dvr.ScheduledRecording;

/**
 * A class for schedule recording row.
 */
public class ScheduleRow {
    private ScheduledRecording mRecording;
    private boolean mRemoveScheduleChecked;
    private SchedulesHeaderRow mHeaderRow;

    public ScheduleRow(ScheduledRecording recording, SchedulesHeaderRow headerRow) {
        mRecording = recording;
        mRemoveScheduleChecked = false;
        mHeaderRow = headerRow;
    }

    /**
     * Sets scheduled recording.
     */
    public void setRecording(ScheduledRecording recording) {
        mRecording = recording;
    }

    /**
     * Sets remove schedule checked status.
     */
    public void setRemoveScheduleChecked(boolean checked) {
        mRemoveScheduleChecked = checked;
    }

    /**
     * Gets scheduled recording.
     */
    public ScheduledRecording getRecording() {
        return mRecording;
    }

    /**
     * Gets remove schedule checked status.
     */
    public boolean isRemoveScheduleChecked() {
        return mRemoveScheduleChecked;
    }

    /**
     * Gets which {@link SchedulesHeaderRow} this schedule row belongs to.
     */
    public SchedulesHeaderRow getHeaderRow() {
        return mHeaderRow;
    }
}
