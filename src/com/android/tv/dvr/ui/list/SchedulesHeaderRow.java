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

import android.support.annotation.Nullable;

import com.android.tv.dvr.SeriesRecording;

/**
 * A base class for the rows for schedules' header.
 */
public abstract class SchedulesHeaderRow {
    private String mTitle;
    private String mDescription;
    private int mItemCount;

    public SchedulesHeaderRow(String title, String description, int itemCount) {
        mTitle = title;
        mItemCount = itemCount;
        mDescription = description;
    }

    /**
     * Sets title.
     */
    public void setTitle(String title) {
        mTitle = title;
    }

    /**
     * Sets description.
     */
    public void setDescription(String description) {
        mDescription = description;
    }

    /**
     * Sets count of items.
     */
    public void setItemCount(int itemCount) {
        mItemCount = itemCount;
    }

    /**
     * Returns title.
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * Returns description.
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Returns count of items.
     */
    public int getItemCount() {
        return mItemCount;
    }

    /**
     * The header row which represent the date.
     */
    public static class DateHeaderRow extends SchedulesHeaderRow {
        private long mDeadLineMs;

        public DateHeaderRow(String title, String description, int itemCount, long deadLineMs) {
            super(title, description, itemCount);
            mDeadLineMs = deadLineMs;
        }

        /**
         * Sets the latest time of the list which belongs to the header row.
         */
        public void setDeadLineMs(long deadLineMs) {
            mDeadLineMs = deadLineMs;
        }

        /**
         * Returns the latest time of the list which belongs to the header row.
         */
        public long getDeadLineMs() {
            return mDeadLineMs;
        }
    }

    /**
     * The header row which represent the series recording.
     */
    public static class SeriesRecordingHeaderRow extends SchedulesHeaderRow {
        private SeriesRecording mSeries;
        private boolean mCancelAllChecked;

        public SeriesRecordingHeaderRow(String title, String description, int itemCount,
                SeriesRecording series) {
            super(title, description, itemCount);
            mSeries = series;
            mCancelAllChecked = series.getState() == SeriesRecording.STATE_SERIES_CANCELED;
        }

        /**
         * Sets cancel all checked status.
         */
        public void setCancelAllChecked(boolean checked) {
            mCancelAllChecked = checked;
        }

        /**
         * Returns cancel all checked status.
         */
        public boolean isCancelAllChecked() {
            return mCancelAllChecked;
        }

        /**
         * Returns the series recording, it is for series schedules list.
         */
        public SeriesRecording getSeriesRecording() {
            return mSeries;
        }
    }
}
