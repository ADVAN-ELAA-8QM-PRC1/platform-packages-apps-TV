/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tv.dvr;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.util.ArraySet;
import android.util.Log;

import com.android.tv.common.SoftPreconditions;
import com.android.tv.common.feature.CommonFeatures;
import com.android.tv.dvr.ScheduledRecording.RecordingState;
import com.android.tv.util.Clock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Base implementation of @{link DataManagerInternal}.
 */
@MainThread
@TargetApi(Build.VERSION_CODES.N)
public abstract class BaseDvrDataManager implements WritableDvrDataManager {
    private final static String TAG = "BaseDvrDataManager";
    private final static boolean DEBUG = false;
    protected final Clock mClock;

    private final Set<OnDvrScheduleLoadFinishedListener> mOnDvrScheduleLoadFinishedListeners =
            new CopyOnWriteArraySet<>();
    private final Set<OnRecordedProgramLoadFinishedListener>
            mOnRecordedProgramLoadFinishedListeners = new CopyOnWriteArraySet<>();
    private final Set<ScheduledRecordingListener> mScheduledRecordingListeners = new ArraySet<>();
    private final Set<SeriesRecordingListener> mSeriesRecordingListeners = new ArraySet<>();
    private final Set<RecordedProgramListener> mRecordedProgramListeners = new ArraySet<>();
    private final HashMap<Long, ScheduledRecording> mDeletedScheduleMap = new HashMap<>();

    BaseDvrDataManager(Context context, Clock clock) {
        SoftPreconditions.checkFeatureEnabled(context, CommonFeatures.DVR, TAG);
        mClock = clock;
    }

    @Override
    public void addDvrScheduleLoadFinishedListener(OnDvrScheduleLoadFinishedListener listener) {
        mOnDvrScheduleLoadFinishedListeners.add(listener);
    }

    @Override
    public void removeDvrScheduleLoadFinishedListener(OnDvrScheduleLoadFinishedListener listener) {
        mOnDvrScheduleLoadFinishedListeners.remove(listener);
    }

    @Override
    public void addRecordedProgramLoadFinishedListener(
            OnRecordedProgramLoadFinishedListener listener) {
        mOnRecordedProgramLoadFinishedListeners.add(listener);
    }

    @Override
    public void removeRecordedProgramLoadFinishedListener(
            OnRecordedProgramLoadFinishedListener listener) {
        mOnRecordedProgramLoadFinishedListeners.remove(listener);
    }

    @Override
    public final void addScheduledRecordingListener(ScheduledRecordingListener listener) {
        mScheduledRecordingListeners.add(listener);
    }

    @Override
    public final void removeScheduledRecordingListener(ScheduledRecordingListener listener) {
        mScheduledRecordingListeners.remove(listener);
    }

    @Override
    public final void addSeriesRecordingListener(SeriesRecordingListener listener) {
        mSeriesRecordingListeners.add(listener);
    }

    @Override
    public final void removeSeriesRecordingListener(SeriesRecordingListener listener) {
        mSeriesRecordingListeners.remove(listener);
    }

    @Override
    public final void addRecordedProgramListener(RecordedProgramListener listener) {
        mRecordedProgramListeners.add(listener);
    }

    @Override
    public final void removeRecordedProgramListener(RecordedProgramListener listener) {
        mRecordedProgramListeners.remove(listener);
    }

    /**
     * Calls {@link OnDvrScheduleLoadFinishedListener#onDvrScheduleLoadFinished} for each listener.
     */
    protected final void notifyDvrScheduleLoadFinished() {
        for (OnDvrScheduleLoadFinishedListener l : mOnDvrScheduleLoadFinishedListeners) {
            if (DEBUG) Log.d(TAG, "notify DVR schedule load finished");
            l.onDvrScheduleLoadFinished();
        }
    }

    /**
     * Calls {@link OnRecordedProgramLoadFinishedListener#onRecordedProgramLoadFinished()}
     * for each listener.
     */
    protected final void notifyRecordedProgramLoadFinished() {
        for (OnRecordedProgramLoadFinishedListener l : mOnRecordedProgramLoadFinishedListeners) {
            if (DEBUG) Log.d(TAG, "notify recorded programs load finished");
            l.onRecordedProgramLoadFinished();
        }
    }

    /**
     * Calls {@link RecordedProgramListener#onRecordedProgramAdded(RecordedProgram)}
     * for each listener.
     */
    protected final void notifyRecordedProgramAdded(RecordedProgram recordedProgram) {
        for (RecordedProgramListener l : mRecordedProgramListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "added " + recordedProgram);
            l.onRecordedProgramAdded(recordedProgram);
        }
    }

    /**
     * Calls {@link RecordedProgramListener#onRecordedProgramChanged(RecordedProgram)}
     * for each listener.
     */
    protected final void notifyRecordedProgramChanged(RecordedProgram recordedProgram) {
        for (RecordedProgramListener l : mRecordedProgramListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "changed " + recordedProgram);
            l.onRecordedProgramChanged(recordedProgram);
        }
    }

    /**
     * Calls {@link RecordedProgramListener#onRecordedProgramRemoved(RecordedProgram)}
     * for each  listener.
     */
    protected final void notifyRecordedProgramRemoved(RecordedProgram recordedProgram) {
        for (RecordedProgramListener l : mRecordedProgramListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "removed " + recordedProgram);
            l.onRecordedProgramRemoved(recordedProgram);
        }
    }

    /**
     * Calls {@link SeriesRecordingListener#onSeriesRecordingAdded}
     * for each listener.
     */
    protected final void notifySeriesRecordingAdded(SeriesRecording... seriesRecordings) {
        for (SeriesRecordingListener l : mSeriesRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "added  " + seriesRecordings);
            l.onSeriesRecordingAdded(seriesRecordings);
        }
    }

    /**
     * Calls {@link SeriesRecordingListener#onSeriesRecordingRemoved}
     * for each listener.
     */
    protected final void notifySeriesRecordingRemoved(SeriesRecording... seriesRecordings) {
        for (SeriesRecordingListener l : mSeriesRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "removed " + seriesRecordings);
            l.onSeriesRecordingRemoved(seriesRecordings);
        }
    }

    /**
     * Calls
     * {@link SeriesRecordingListener#onSeriesRecordingChanged}
     * for each listener.
     */
    protected final void notifySeriesRecordingChanged(SeriesRecording... seriesRecordings) {
        for (SeriesRecordingListener l : mSeriesRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "changed " + seriesRecordings);
            l.onSeriesRecordingChanged(seriesRecordings);
        }
    }

    /**
     * Calls {@link ScheduledRecordingListener#onScheduledRecordingAdded}
     * for each listener.
     */
    protected final void notifyScheduledRecordingAdded(ScheduledRecording... scheduledRecording) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "added  " + scheduledRecording);
            l.onScheduledRecordingAdded(scheduledRecording);
        }
    }

    /**
     * Calls {@link ScheduledRecordingListener#onScheduledRecordingRemoved}
     * for each listener.
     */
    protected final void notifyScheduledRecordingRemoved(ScheduledRecording... scheduledRecording) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "removed " + scheduledRecording);
            l.onScheduledRecordingRemoved(scheduledRecording);
        }
    }

    /**
     * Calls
     * {@link ScheduledRecordingListener#onScheduledRecordingStatusChanged}
     * for each listener.
     */
    protected final void notifyScheduledRecordingStatusChanged(
            ScheduledRecording... scheduledRecording) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            if (DEBUG) Log.d(TAG, "notify " + l + "changed " + scheduledRecording);
            l.onScheduledRecordingStatusChanged(scheduledRecording);
        }
    }

    /**
     * Returns a new list with only {@link ScheduledRecording} with a {@link
     * ScheduledRecording#getEndTimeMs() endTime} after now.
     */
    private List<ScheduledRecording> filterEndTimeIsPast(List<ScheduledRecording> originals) {
        List<ScheduledRecording> results = new ArrayList<>(originals.size());
        for (ScheduledRecording r : originals) {
            if (r.getEndTimeMs() > mClock.currentTimeMillis()) {
                results.add(r);
            }
        }
        return results;
    }

    @Override
    public List<ScheduledRecording> getAvailableScheduledRecordings() {
        return filterEndTimeIsPast(getRecordingsWithState(
                ScheduledRecording.STATE_RECORDING_IN_PROGRESS,
                ScheduledRecording.STATE_RECORDING_NOT_STARTED));
    }

    @Override
    public List<ScheduledRecording> getAvailableAndCanceledScheduledRecordings() {
        return filterEndTimeIsPast(getRecordingsWithState(
                ScheduledRecording.STATE_RECORDING_IN_PROGRESS,
                ScheduledRecording.STATE_RECORDING_NOT_STARTED,
                ScheduledRecording.STATE_RECORDING_CANCELED));
    }

    @Override
    public List<ScheduledRecording> getStartedRecordings() {
        return filterEndTimeIsPast(getRecordingsWithState(
                ScheduledRecording.STATE_RECORDING_IN_PROGRESS));
    }

    @Override
    public List<ScheduledRecording> getNonStartedScheduledRecordings() {
        Set<Integer> states = new HashSet<>();
        states.add(ScheduledRecording.STATE_RECORDING_NOT_STARTED);
        return filterEndTimeIsPast(getRecordingsWithState(
                ScheduledRecording.STATE_RECORDING_NOT_STARTED));
    }

    @Override
    public void changeState(ScheduledRecording scheduledRecording, @RecordingState int newState) {
        if (scheduledRecording.getState() != newState) {
            updateScheduledRecording(ScheduledRecording.buildFrom(scheduledRecording)
                    .setState(newState).build());
        }
    }

    @Override
    public Collection<ScheduledRecording> getDeletedSchedules() {
        return mDeletedScheduleMap.values();
    }

    @NonNull
    @Override
    public Collection<Long> getDisallowedProgramIds() {
        return mDeletedScheduleMap.keySet();
    }

    /**
     * Returns the map which contains the deleted schedules which are mapped from the program ID.
     */
    protected Map<Long, ScheduledRecording> getDeletedScheduleMap() {
        return mDeletedScheduleMap;
    }

    /**
     * Returns the schedules whose state is contained by states.
     */
    protected abstract List<ScheduledRecording> getRecordingsWithState(int... states);

    @Override
    public List<RecordedProgram> getRecordedPrograms(long seriesRecordingId) {
        SeriesRecording seriesRecording = getSeriesRecording(seriesRecordingId);
        List<RecordedProgram> result = new ArrayList<>();
        for (RecordedProgram r : getRecordedPrograms()) {
            if (seriesRecording.getSeriesId().equals(r.getSeriesId())) {
                result.add(r);
            }
        }
        return result;
    }
}
