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
 * limitations under the License.
 */

package com.android.tv.dvr;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.media.tv.TvContract.RecordedPrograms;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Range;

import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.dvr.ScheduledRecording.RecordingState;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncAddScheduleTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncAddSeriesRecordingTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncDeleteScheduleTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncDeleteSeriesRecordingTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncDvrQueryScheduleTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncDvrQuerySeriesRecordingTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncUpdateScheduleTask;
import com.android.tv.dvr.provider.AsyncDvrDbTask.AsyncUpdateSeriesRecordingTask;
import com.android.tv.util.AsyncDbTask.AsyncRecordedProgramQueryTask;
import com.android.tv.util.Clock;
import com.android.tv.util.TvProviderUriMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * DVR Data manager to handle recordings and schedules.
 */
@MainThread
@TargetApi(Build.VERSION_CODES.N)
public class DvrDataManagerImpl extends BaseDvrDataManager {
    private static final String TAG = "DvrDataManagerImpl";
    private static final boolean DEBUG = false;

    private final HashMap<Long, ScheduledRecording> mScheduledRecordings = new HashMap<>();
    private final HashMap<Long, RecordedProgram> mRecordedPrograms = new HashMap<>();
    private final HashMap<Long, SeriesRecording> mSeriesRecordings = new HashMap<>();
    private final HashMap<Long, ScheduledRecording> mProgramId2ScheduledRecordings =
            new HashMap<>();
    private final HashMap<String, SeriesRecording> mSeriesId2SeriesRecordings = new HashMap<>();

    private final Context mContext;
    private final ContentObserver mContentObserver = new ContentObserver(new Handler(
            Looper.getMainLooper())) {
        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, final @Nullable Uri uri) {
            RecordedProgramsQueryTask task = new RecordedProgramsQueryTask(
                    mContext.getContentResolver(), uri);
            task.executeOnDbThread();
            mPendingTasks.add(task);
        }
    };

    private boolean mDvrLoadFinished;
    private boolean mRecordedProgramLoadFinished;
    private final Set<AsyncTask> mPendingTasks = new ArraySet<>();
    private final DvrDbSync mDbSync;

    public DvrDataManagerImpl(Context context, Clock clock) {
        super(context, clock);
        mContext = context;
        mDbSync = new DvrDbSync(context, this);
    }

    public void start() {
        AsyncDvrQuerySeriesRecordingTask dvrQuerySeriesRecordingTask
                = new AsyncDvrQuerySeriesRecordingTask(mContext) {
            @Override
            protected void onCancelled(List<SeriesRecording> seriesRecordings) {
                mPendingTasks.remove(this);
            }

            @Override
            protected void onPostExecute(List<SeriesRecording> seriesRecordings) {
                mPendingTasks.remove(this);
                long maxId = 0;
                for (SeriesRecording r : seriesRecordings) {
                    mSeriesRecordings.put(r.getId(), r);
                    mSeriesId2SeriesRecordings.put(r.getSeriesId(), r);
                    if (maxId < r.getId()) {
                        maxId = r.getId();
                    }
                }
                IdGenerator.SERIES_RECORDING.setMaxId(maxId);
            }
        };
        dvrQuerySeriesRecordingTask.executeOnDbThread();
        mPendingTasks.add(dvrQuerySeriesRecordingTask);
        AsyncDvrQueryScheduleTask dvrQueryRecordingTask
                = new AsyncDvrQueryScheduleTask(mContext) {
            @Override
            protected void onCancelled(List<ScheduledRecording> scheduledRecordings) {
                mPendingTasks.remove(this);
            }

            @Override
            protected void onPostExecute(List<ScheduledRecording> result) {
                mPendingTasks.remove(this);
                long maxId = 0;
                List<ScheduledRecording> toUpdate = new ArrayList<>();
                for (ScheduledRecording r : result) {
                    if (r.getState() == ScheduledRecording.STATE_RECORDING_DELETED) {
                        getDeletedScheduleMap().put(r.getProgramId(), r);
                    } else {
                        mScheduledRecordings.put(r.getId(), r);
                        if (r.getProgramId() != ScheduledRecording.ID_NOT_SET) {
                            mProgramId2ScheduledRecordings.put(r.getProgramId(), r);
                        }
                        // Adjust the state of the schedules before DB loading is finished.
                        if (r.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                            if (r.getEndTimeMs() <= mClock.currentTimeMillis()) {
                                toUpdate.add(ScheduledRecording.buildFrom(r)
                                        .setState(ScheduledRecording.STATE_RECORDING_FAILED)
                                        .build());
                            } else {
                                toUpdate.add(ScheduledRecording.buildFrom(r)
                                        .setState(ScheduledRecording.STATE_RECORDING_NOT_STARTED)
                                        .build());
                            }
                        } else if (r.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED) {
                            if (r.getEndTimeMs() <= mClock.currentTimeMillis()) {
                                toUpdate.add(ScheduledRecording.buildFrom(r)
                                        .setState(ScheduledRecording.STATE_RECORDING_FAILED)
                                        .build());
                            }
                        }
                    }
                    if (maxId < r.getId()) {
                        maxId = r.getId();
                    }
                }
                if (!toUpdate.isEmpty()) {
                    updateScheduledRecording(true, ScheduledRecording.toArray(toUpdate));
                }
                IdGenerator.SCHEDULED_RECORDING.setMaxId(maxId);
                mDvrLoadFinished = true;
                notifyDvrScheduleLoadFinished();
                mDbSync.start();
                if (isInitialized()) {
                    SeriesRecordingScheduler.getInstance(mContext).start();
                }
            }
        };
        dvrQueryRecordingTask.executeOnDbThread();
        mPendingTasks.add(dvrQueryRecordingTask);
        RecordedProgramsQueryTask mRecordedProgramQueryTask =
                new RecordedProgramsQueryTask(mContext.getContentResolver(), null);
        mRecordedProgramQueryTask.executeOnDbThread();
        ContentResolver cr = mContext.getContentResolver();
        cr.registerContentObserver(RecordedPrograms.CONTENT_URI, true, mContentObserver);
    }

    public void stop() {
        SeriesRecordingScheduler.getInstance(mContext).stop();
        mDbSync.stop();
        ContentResolver cr = mContext.getContentResolver();
        cr.unregisterContentObserver(mContentObserver);
        Iterator<AsyncTask> i = mPendingTasks.iterator();
        while (i.hasNext()) {
            AsyncTask task = i.next();
            i.remove();
            task.cancel(true);
        }
    }

    private void onRecordedProgramsLoadedFinished(Uri uri, List<RecordedProgram> recordedPrograms) {
        if (uri == null) {
            uri = RecordedPrograms.CONTENT_URI;
        }
        int match = TvProviderUriMatcher.match(uri);
        if (match == TvProviderUriMatcher.MATCH_RECORDED_PROGRAM) {
            if (!mRecordedProgramLoadFinished) {
                for (RecordedProgram recorded : recordedPrograms) {
                    mRecordedPrograms.put(recorded.getId(), recorded);
                }
                mRecordedProgramLoadFinished = true;
                notifyRecordedProgramLoadFinished();
            } else if (recordedPrograms == null || recordedPrograms.isEmpty()) {
                for (RecordedProgram recorded : mRecordedPrograms.values()) {
                    notifyRecordedProgramRemoved(recorded);
                }
                mRecordedPrograms.clear();
            } else {
                HashMap<Long, RecordedProgram> oldRecordedPrograms
                        = new HashMap<>(mRecordedPrograms);
                mRecordedPrograms.clear();
                for (RecordedProgram recorded : recordedPrograms) {
                    mRecordedPrograms.put(recorded.getId(), recorded);
                    RecordedProgram old = oldRecordedPrograms.remove(recorded.getId());
                    if (old == null) {
                        notifyRecordedProgramAdded(recorded);
                    } else {
                        notifyRecordedProgramChanged(recorded);
                    }
                }
                for (RecordedProgram recorded : oldRecordedPrograms.values()) {
                    notifyRecordedProgramRemoved(recorded);
                }
            }
            if (isInitialized()) {
                SeriesRecordingScheduler.getInstance(mContext).start();
            }
        } else if (match == TvProviderUriMatcher.MATCH_RECORDED_PROGRAM_ID) {
            long id = ContentUris.parseId(uri);
            if (DEBUG) Log.d(TAG, "changed recorded program #" + id + " to " + recordedPrograms);
            if (recordedPrograms == null || recordedPrograms.isEmpty()) {
                RecordedProgram old = mRecordedPrograms.remove(id);
                if (old != null) {
                    notifyRecordedProgramRemoved(old);
                } else {
                    Log.w(TAG, "Could not find old version of deleted program #" + id);
                }
            } else {
                RecordedProgram newRecorded = recordedPrograms.get(0);
                RecordedProgram old = mRecordedPrograms.put(id, newRecorded);
                if (old == null) {
                    notifyRecordedProgramAdded(newRecorded);
                } else {
                    notifyRecordedProgramChanged(newRecorded);
                }
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return mDvrLoadFinished && mRecordedProgramLoadFinished;
    }

    @Override
    public boolean isDvrScheduleLoadFinished() {
        return mDvrLoadFinished;
    }

    @Override
    public boolean isRecordedProgramLoadFinished() {
        return mRecordedProgramLoadFinished;
    }

    private List<ScheduledRecording> getScheduledRecordingsPrograms() {
        if (!mDvrLoadFinished) {
            return Collections.emptyList();
        }
        ArrayList<ScheduledRecording> list = new ArrayList<>(mScheduledRecordings.size());
        list.addAll(mScheduledRecordings.values());
        Collections.sort(list, ScheduledRecording.START_TIME_COMPARATOR);
        return list;
    }

    @Override
    public List<RecordedProgram> getRecordedPrograms() {
        if (!mRecordedProgramLoadFinished) {
            return Collections.emptyList();
        }
        return new ArrayList<>(mRecordedPrograms.values());
    }

    @Override
    public List<RecordedProgram> getRecordedPrograms(long seriesRecordingId) {
        SeriesRecording seriesRecording = getSeriesRecording(seriesRecordingId);
        if (!mRecordedProgramLoadFinished || seriesRecording == null) {
            return Collections.emptyList();
        }
        return super.getRecordedPrograms(seriesRecordingId);
    }

    @Override
    public List<ScheduledRecording> getAllScheduledRecordings() {
        return new ArrayList<>(mScheduledRecordings.values());
    }

    @Override
    protected List<ScheduledRecording> getRecordingsWithState(@RecordingState int... states) {
        List<ScheduledRecording> result = new ArrayList<>();
        for (ScheduledRecording r : mScheduledRecordings.values()) {
            for (int state : states) {
                if (r.getState() == state) {
                    result.add(r);
                    break;
                }
            }
        }
        return result;
    }

    @Override
    public List<SeriesRecording> getSeriesRecordings() {
        if (!mDvrLoadFinished) {
            return Collections.emptyList();
        }
        return new ArrayList<>(mSeriesRecordings.values());
    }

    @Override
    public List<SeriesRecording> getSeriesRecordings(String inputId) {
        List<SeriesRecording> result = new ArrayList<>();
        for (SeriesRecording r : mSeriesRecordings.values()) {
            if (TextUtils.equals(r.getInputId(), inputId)) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public long getNextScheduledStartTimeAfter(long startTime) {
        return getNextStartTimeAfter(getScheduledRecordingsPrograms(), startTime);
    }

    @VisibleForTesting
    static long getNextStartTimeAfter(List<ScheduledRecording> scheduledRecordings, long startTime) {
        int start = 0;
        int end = scheduledRecordings.size() - 1;
        while (start <= end) {
            int mid = (start + end) / 2;
            if (scheduledRecordings.get(mid).getStartTimeMs() <= startTime) {
                start = mid + 1;
            } else {
                end = mid - 1;
            }
        }
        return start < scheduledRecordings.size() ? scheduledRecordings.get(start).getStartTimeMs()
                : NEXT_START_TIME_NOT_FOUND;
    }

    @Override
    public List<ScheduledRecording> getScheduledRecordings(Range<Long> period,
            @RecordingState int state) {
        List<ScheduledRecording> result = new ArrayList<>();
        for (ScheduledRecording r : mScheduledRecordings.values()) {
            if (r.isOverLapping(period) && r.getState() == state) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public List<ScheduledRecording> getScheduledRecordings(long seriesRecordingId) {
        List<ScheduledRecording> result = new ArrayList<>();
        for (ScheduledRecording r : mScheduledRecordings.values()) {
            if (r.getSeriesRecordingId() == seriesRecordingId) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public List<ScheduledRecording> getScheduledRecordings(String inputId) {
        List<ScheduledRecording> result = new ArrayList<>();
        for (ScheduledRecording r : mScheduledRecordings.values()) {
            if (TextUtils.equals(r.getInputId(), inputId)) {
                result.add(r);
            }
        }
        return result;
    }

    @Nullable
    @Override
    public ScheduledRecording getScheduledRecording(long recordingId) {
        return mScheduledRecordings.get(recordingId);
    }

    @Nullable
    @Override
    public ScheduledRecording getScheduledRecordingForProgramId(long programId) {
        return mProgramId2ScheduledRecordings.get(programId);
    }

    @Nullable
    @Override
    public RecordedProgram getRecordedProgram(long recordingId) {
        return mRecordedPrograms.get(recordingId);
    }

    @Nullable
    @Override
    public SeriesRecording getSeriesRecording(long seriesRecordingId) {
        return mSeriesRecordings.get(seriesRecordingId);
    }

    @Nullable
    @Override
    public SeriesRecording getSeriesRecording(String seriesId) {
        return mSeriesId2SeriesRecordings.get(seriesId);
    }

    @Override
    public void addScheduledRecording(ScheduledRecording... schedules) {
        for (ScheduledRecording r : schedules) {
            r.setId(IdGenerator.SCHEDULED_RECORDING.newId());
            mScheduledRecordings.put(r.getId(), r);
            if (r.getProgramId() != ScheduledRecording.ID_NOT_SET) {
                mProgramId2ScheduledRecordings.put(r.getProgramId(), r);
            }
        }
        if (mDvrLoadFinished) {
            notifyScheduledRecordingAdded(schedules);
        }
        new AsyncAddScheduleTask(mContext).executeOnDbThread(schedules);
        removeDeletedSchedules(schedules);
    }

    @Override
    public void addSeriesRecording(SeriesRecording... seriesRecordings) {
        for (SeriesRecording r : seriesRecordings) {
            r.setId(IdGenerator.SERIES_RECORDING.newId());
            mSeriesRecordings.put(r.getId(), r);
            mSeriesId2SeriesRecordings.put(r.getSeriesId(), r);
        }
        if (mDvrLoadFinished) {
            notifySeriesRecordingAdded(seriesRecordings);
        }
        new AsyncAddSeriesRecordingTask(mContext).executeOnDbThread(seriesRecordings);
    }

    @Override
    public void removeScheduledRecording(ScheduledRecording... schedules) {
        removeScheduledRecording(false, schedules);
    }

    private void removeScheduledRecording(boolean forceDelete, ScheduledRecording... schedules) {
        List<ScheduledRecording> schedulesToDelete = new ArrayList<>();
        List<ScheduledRecording> schedulesNotToDelete = new ArrayList<>();
        for (ScheduledRecording r : schedules) {
            mScheduledRecordings.remove(r.getId());
            if (r.getProgramId() != ScheduledRecording.ID_NOT_SET) {
                mProgramId2ScheduledRecordings.remove(r.getProgramId());
            }
            // If it belongs to the series recording and it's not started yet, do not delete.
            // Instead mark deleted.
            if (!forceDelete && r.getSeriesRecordingId() != SeriesRecording.ID_NOT_SET
                    && r.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED) {
                SoftPreconditions.checkState(r.getProgramId() != ScheduledRecording.ID_NOT_SET);
                ScheduledRecording deleted = ScheduledRecording.buildFrom(r)
                        .setState(ScheduledRecording.STATE_RECORDING_DELETED).build();
                getDeletedScheduleMap().put(deleted.getProgramId(), deleted);
                schedulesNotToDelete.add(deleted);
            } else {
                schedulesToDelete.add(r);
            }
        }
        if (mDvrLoadFinished) {
            notifyScheduledRecordingRemoved(schedules);
        }
        if (!schedulesToDelete.isEmpty()) {
            new AsyncDeleteScheduleTask(mContext).executeOnDbThread(
                    ScheduledRecording.toArray(schedulesToDelete));
        }
        if (!schedulesNotToDelete.isEmpty()) {
            new AsyncUpdateScheduleTask(mContext).executeOnDbThread(
                    ScheduledRecording.toArray(schedulesNotToDelete));
        }
    }

    @Override
    public void removeSeriesRecording(final SeriesRecording... seriesRecordings) {
        HashSet<Long> ids = new HashSet<>();
        for (SeriesRecording r : seriesRecordings) {
            mSeriesRecordings.remove(r.getId());
            mSeriesId2SeriesRecordings.remove(r.getSeriesId());
            ids.add(r.getId());
        }
        // Reset series recording ID of the scheduled recording.
        List<ScheduledRecording> toUpdate = new ArrayList<>();
        List<ScheduledRecording> toDelete = new ArrayList<>();
        for (ScheduledRecording r : mScheduledRecordings.values()) {
            if (ids.contains(r.getSeriesRecordingId())) {
                if (r.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED) {
                    toDelete.add(r);
                } else {
                    toUpdate.add(ScheduledRecording.buildFrom(r)
                            .setSeriesRecordingId(SeriesRecording.ID_NOT_SET).build());
                }
            }
        }
        if (!toUpdate.isEmpty()) {
            // No need to update DB. It's handled in database automatically when the series
            // recording is deleted.
            updateScheduledRecording(false, ScheduledRecording.toArray(toUpdate));
        }
        if (!toDelete.isEmpty()) {
            removeScheduledRecording(true, ScheduledRecording.toArray(toDelete));
        }
        if (mDvrLoadFinished) {
            notifySeriesRecordingRemoved(seriesRecordings);
        }
        new AsyncDeleteSeriesRecordingTask(mContext).executeOnDbThread(seriesRecordings);
        removeDeletedSchedules(seriesRecordings);
    }

    @Override
    public void updateScheduledRecording(final ScheduledRecording... schedules) {
        updateScheduledRecording(true, schedules);
    }

    private void updateScheduledRecording(boolean updateDb,
            final ScheduledRecording... schedules) {
        List<ScheduledRecording> toUpdate = new ArrayList<>();
        for (ScheduledRecording r : schedules) {
            if (!SoftPreconditions.checkState(mScheduledRecordings.containsKey(r.getId()), TAG,
                    "Recording not found for: " + r)) {
                continue;
            }
            toUpdate.add(r);
            ScheduledRecording oldScheduledRecording = mScheduledRecordings.put(r.getId(), r);
            // The channel ID should not be changed.
            SoftPreconditions.checkState(r.getChannelId() == oldScheduledRecording.getChannelId());
            if (DEBUG) Log.d(TAG, "Updating " + oldScheduledRecording + " with " + r);
            long programId = r.getProgramId();
            if (oldScheduledRecording != null && oldScheduledRecording.getProgramId() != programId
                    && oldScheduledRecording.getProgramId() != ScheduledRecording.ID_NOT_SET) {
                ScheduledRecording oldValueForProgramId = mProgramId2ScheduledRecordings
                        .get(oldScheduledRecording.getProgramId());
                if (oldValueForProgramId.getId() == r.getId()) {
                    // Only remove the old ScheduledRecording if it has the same ID as the new one.
                    mProgramId2ScheduledRecordings.remove(oldScheduledRecording.getProgramId());
                }
            }
            if (programId != ScheduledRecording.ID_NOT_SET) {
                mProgramId2ScheduledRecordings.put(programId, r);
            }
        }
        ScheduledRecording[] scheduleArray = ScheduledRecording.toArray(toUpdate);
        if (mDvrLoadFinished) {
            notifyScheduledRecordingStatusChanged(scheduleArray);
        }
        if (updateDb) {
            new AsyncUpdateScheduleTask(mContext).executeOnDbThread(scheduleArray);
        }
        removeDeletedSchedules(scheduleArray);
    }

    @Override
    public void updateSeriesRecording(final SeriesRecording... seriesRecordings) {
        for (SeriesRecording r : seriesRecordings) {
            SeriesRecording old = mSeriesRecordings.put(r.getId(), r);
            if (old != null) {
                mSeriesId2SeriesRecordings.remove(old.getSeriesId());
            }
            mSeriesId2SeriesRecordings.put(r.getSeriesId(), r);
        }
        if (mDvrLoadFinished) {
            notifySeriesRecordingChanged(seriesRecordings);
        }
        new AsyncUpdateSeriesRecordingTask(mContext).executeOnDbThread(seriesRecordings);
    }

    private void removeDeletedSchedules(ScheduledRecording... addedSchedules) {
        List<ScheduledRecording> schedulesToDelete = new ArrayList<>();
        for (ScheduledRecording r : addedSchedules) {
            ScheduledRecording deleted = getDeletedScheduleMap().remove(r.getProgramId());
            if (deleted != null) {
                schedulesToDelete.add(deleted);
            }
        }
        if (!schedulesToDelete.isEmpty()) {
            new AsyncDeleteScheduleTask(mContext).executeOnDbThread(
                    ScheduledRecording.toArray(schedulesToDelete));
        }
    }

    private void removeDeletedSchedules(SeriesRecording... removedSeriesRecordings) {
        Set<Long> seriesRecordingIds = new HashSet<>();
        for (SeriesRecording r : removedSeriesRecordings) {
            seriesRecordingIds.add(r.getId());
        }
        List<ScheduledRecording> schedulesToDelete = new ArrayList<>();
        Iterator<Entry<Long, ScheduledRecording>> iter =
                getDeletedScheduleMap().entrySet().iterator();
        while (iter.hasNext()) {
            Entry<Long, ScheduledRecording> entry = iter.next();
            if (seriesRecordingIds.contains(entry.getValue().getSeriesRecordingId())) {
                schedulesToDelete.add(entry.getValue());
                iter.remove();
            }
        }
        if (!schedulesToDelete.isEmpty()) {
            new AsyncDeleteScheduleTask(mContext).executeOnDbThread(
                    ScheduledRecording.toArray(schedulesToDelete));
        }
    }

    private final class RecordedProgramsQueryTask extends AsyncRecordedProgramQueryTask {
        private final Uri mUri;

        public RecordedProgramsQueryTask(ContentResolver contentResolver, Uri uri) {
            super(contentResolver, uri == null ? RecordedPrograms.CONTENT_URI : uri);
            mUri = uri;
        }

        @Override
        protected void onCancelled(List<RecordedProgram> scheduledRecordings) {
            mPendingTasks.remove(this);
        }

        @Override
        protected void onPostExecute(List<RecordedProgram> result) {
            mPendingTasks.remove(this);
            onRecordedProgramsLoadedFinished(mUri, result);
        }
    }
}
