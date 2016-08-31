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
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.util.Log;

import com.android.tv.ApplicationSingletons;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.common.feature.CommonFeatures;
import com.android.tv.data.Channel;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.data.Program;
import com.android.tv.dvr.SeriesRecordingScheduler.ProgramLoadCallback;
import com.android.tv.util.AsyncDbTask;
import com.android.tv.util.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * DVR manager class to add and remove recordings. UI can modify recording list through this class,
 * instead of modifying them directly through {@link DvrDataManager}.
 */
@MainThread
@TargetApi(Build.VERSION_CODES.N)
public class DvrManager {
    private static final String TAG = "DvrManager";
    private static final boolean DEBUG = false;

    private final WritableDvrDataManager mDataManager;
    private final ChannelDataManager mChannelDataManager;
    private final DvrScheduleManager mScheduleManager;
    // @GuardedBy("mListener")
    private final Map<Listener, Handler> mListener = new HashMap<>();
    private final Context mAppContext;

    public DvrManager(Context context) {
        SoftPreconditions.checkFeatureEnabled(context, CommonFeatures.DVR, TAG);
        ApplicationSingletons appSingletons = TvApplication.getSingletons(context);
        mDataManager = (WritableDvrDataManager) appSingletons.getDvrDataManager();
        mAppContext = context.getApplicationContext();
        mChannelDataManager = appSingletons.getChannelDataManager();
        mScheduleManager = appSingletons.getDvrScheduleManager();
    }

    /**
     * Schedules a recording for {@code program}.
     */
    public void addSchedule(Program program) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        TvInputInfo input = Utils.getTvInputInfoForProgram(mAppContext, program);
        if (input == null) {
            Log.e(TAG, "Can't find input for program: " + program);
            return;
        }
        ScheduledRecording schedule;
        SeriesRecording seriesRecording = getSeriesRecording(program);
        if (seriesRecording == null) {
            schedule = createScheduledRecordingBuilder(input.getId(), program)
                    .setPriority(mScheduleManager.suggestNewPriority())
                    .build();
        } else {
            schedule = createScheduledRecordingBuilder(input.getId(), program)
                    .setPriority(seriesRecording.getPriority())
                    .setSeriesRecordingId(seriesRecording.getId())
                    .build();
        }
        mDataManager.addScheduledRecording(schedule);
    }

    /**
     * Schedules a recording for {@code program} instead of the list of recording that conflict.
     *
     * @param program the program to record
     * @param recordingsToOverride the possible empty list of recordings that will not be recorded
     */
    public void addSchedule(Program program, List<ScheduledRecording> recordingsToOverride) {
        Log.i(TAG, "Adding scheduled recording of " + program + " instead of " +
                recordingsToOverride);
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        TvInputInfo input = Utils.getTvInputInfoForProgram(mAppContext, program);
        if (input == null) {
            Log.e(TAG, "Can't find input for program: " + program);
            return;
        }
        Collections.sort(recordingsToOverride, ScheduledRecording.PRIORITY_COMPARATOR);
        long priority = recordingsToOverride.isEmpty() ? Long.MAX_VALUE
                : recordingsToOverride.get(0).getPriority() + 1;
        ScheduledRecording r = createScheduledRecordingBuilder(input.getId(), program)
                .setPriority(priority)
                .build();
        mDataManager.addScheduledRecording(r);
    }

    /**
     * Adds a recording schedule with a time range.
     */
    public void addSchedule(Channel channel, long startTime, long endTime) {
        Log.i(TAG, "Adding scheduled recording of channel " + channel + " starting at " +
                Utils.toTimeString(startTime) + " and ending at " + Utils.toTimeString(endTime));
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mAppContext, channel.getId());
        if (input == null) {
            Log.e(TAG, "Can't find input for channel: " + channel);
            return;
        }
        addScheduleInternal(input.getId(), channel.getId(), startTime, endTime);
    }

    private void addScheduleInternal(String inputId, long channelId, long startTime, long endTime) {
        mDataManager.addScheduledRecording(ScheduledRecording
                .builder(inputId, channelId, startTime, endTime)
                .setPriority(mScheduleManager.suggestNewPriority())
                .build());
    }

    /**
     * Adds a new series recording and schedules for the programs.
     */
    public SeriesRecording addSeriesRecording(Program selectedProgram,
            List<Program> programsToSchedule) {
        Log.i(TAG, "Adding series recording for program " + selectedProgram + ", and schedules: "
                + programsToSchedule);
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            return null;
        }
        TvInputInfo input = Utils.getTvInputInfoForProgram(mAppContext, selectedProgram);
        if (input == null) {
            Log.e(TAG, "Can't find input for program: " + selectedProgram);
            return null;
        }
        SeriesRecording seriesRecording = SeriesRecording.builder(input.getId(), selectedProgram)
                .setPriority(mScheduleManager.suggestNewSeriesPriority())
                .build();
        mDataManager.addSeriesRecording(seriesRecording);
        // The schedules for the recorded programs should be added not to create the schedule the
        // duplicate episodes.
        addRecordedProgramToSeriesRecording(seriesRecording);
        addScheduleToSeriesRecording(seriesRecording, programsToSchedule);
        return seriesRecording;
    }

    private void addRecordedProgramToSeriesRecording(SeriesRecording series) {
        List<ScheduledRecording> toAdd = new ArrayList<>();
        for (RecordedProgram recordedProgram : mDataManager.getRecordedPrograms()) {
            if (series.getSeriesId().equals(recordedProgram.getSeriesId())
                    && !recordedProgram.isClipped()) {
                // Duplicate schedules can exist, but they will be deleted in a few days. And it's
                // also guaranteed that the schedules don't belong to any series recordings because
                // there are no more than one series recordings which have the same program title.
                toAdd.add(ScheduledRecording.builder(recordedProgram)
                        .setPriority(series.getPriority())
                        .setSeriesRecordingId(series.getId()).build());
            }
        }
        if (!toAdd.isEmpty()) {
            mDataManager.addScheduledRecording(ScheduledRecording.toArray(toAdd));
        }
    }

    /**
     * Adds {@link ScheduledRecording}s for the series recording.
     * <p>
     * This method doesn't add the series recording.
     */
    public void addScheduleToSeriesRecording(SeriesRecording series,
            List<Program> programsToSchedule) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        TvInputInfo input = Utils.getTvInputInfoForInputId(mAppContext, series.getInputId());
        if (input == null) {
            Log.e(TAG, "Can't find input with ID: " + series.getInputId());
            return;
        }
        List<ScheduledRecording> toAdd = new ArrayList<>();
        List<ScheduledRecording> toUpdate = new ArrayList<>();
        for (Program program : programsToSchedule) {
            ScheduledRecording scheduleWithSameProgram =
                    mDataManager.getScheduledRecordingForProgramId(program.getId());
            if (scheduleWithSameProgram != null) {
                if (scheduleWithSameProgram.getState()
                        == ScheduledRecording.STATE_RECORDING_NOT_STARTED
                        || scheduleWithSameProgram.getState()
                        == ScheduledRecording.STATE_RECORDING_CANCELED) {
                    ScheduledRecording r = ScheduledRecording.buildFrom(scheduleWithSameProgram)
                            .setPriority(series.getPriority())
                            .setSeriesRecordingId(series.getId())
                            .build();
                    if (!r.equals(scheduleWithSameProgram)) {
                        toUpdate.add(r);
                    }
                }
            } else {
                ScheduledRecording.Builder scheduledRecordingBuilder =
                        createScheduledRecordingBuilder(input.getId(), program)
                        .setPriority(series.getPriority())
                        .setSeriesRecordingId(series.getId());
                if (series.getState() == SeriesRecording.STATE_SERIES_CANCELED) {
                    scheduledRecordingBuilder.setState(
                            ScheduledRecording.STATE_RECORDING_CANCELED);
                }
                toAdd.add(scheduledRecordingBuilder.build());
            }
        }
        if (!toAdd.isEmpty()) {
            mDataManager.addScheduledRecording(ScheduledRecording.toArray(toAdd));
        }
        if (!toUpdate.isEmpty()) {
            mDataManager.updateScheduledRecording(ScheduledRecording.toArray(toUpdate));
        }
    }

    /**
     * Updates the series recording.
     */
    public void updateSeriesRecording(SeriesRecording series) {
        if (SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            // TODO: revise this method. b/30946239
            boolean isPreviousCanceled = false;
            long oldPriority = 0;
            SeriesRecording previousSeries = mDataManager.getSeriesRecording(series.getId());
            if (previousSeries != null) {
                isPreviousCanceled = previousSeries.getState()
                        == SeriesRecording.STATE_SERIES_CANCELED;
                oldPriority = previousSeries.getPriority();
            }
            mDataManager.updateSeriesRecording(series);
            if (!isPreviousCanceled && series.getState() == SeriesRecording.STATE_SERIES_CANCELED) {
                cancelScheduleToSeriesRecording(series);
            } else if (isPreviousCanceled
                    && series.getState() == SeriesRecording.STATE_SERIES_NORMAL) {
                resumeScheduleToSeriesRecording(series);
            }
            if (oldPriority != series.getPriority()) {
                long priority = series.getPriority();
                List<ScheduledRecording> schedulesToUpdate = new ArrayList<>();
                for (ScheduledRecording schedule
                        : mDataManager.getScheduledRecordings(series.getId())) {
                    if (schedule.getState() != ScheduledRecording.STATE_RECORDING_IN_PROGRESS
                            && schedule.getStartTimeMs() > System.currentTimeMillis()) {
                        schedulesToUpdate.add(ScheduledRecording.buildFrom(schedule)
                                .setPriority(priority).build());
                    }
                }
                if (!schedulesToUpdate.isEmpty()) {
                    mDataManager.updateScheduledRecording(
                            ScheduledRecording.toArray(schedulesToUpdate));
                }
            }
        }
    }

    private void cancelScheduleToSeriesRecording(SeriesRecording series) {
        List<ScheduledRecording> allRecordings = mDataManager.getAvailableScheduledRecordings();
        for (ScheduledRecording recording : allRecordings) {
            if (recording.getSeriesRecordingId() == series.getId()) {
                if (recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                    stopRecording(recording);
                    continue;
                }
                updateScheduledRecording(ScheduledRecording.buildFrom(recording).setState
                        (ScheduledRecording.STATE_RECORDING_CANCELED).build());
            }
        }
    }

    private void resumeScheduleToSeriesRecording(SeriesRecording series) {
        List<ScheduledRecording> allRecording = mDataManager
                .getAvailableAndCanceledScheduledRecordings();
        for (ScheduledRecording recording : allRecording) {
            if (recording.getSeriesRecordingId() == series.getId()) {
                if (recording.getState() == ScheduledRecording.STATE_RECORDING_CANCELED &&
                        recording.getEndTimeMs() > System.currentTimeMillis()) {
                    updateScheduledRecording(ScheduledRecording.buildFrom(recording)
                            .setState(ScheduledRecording.STATE_RECORDING_NOT_STARTED).build());
                }
            }
        }
    }

    /**
     * Queries the programs which belong to the same series as {@code seriesProgram}.
     * <p>
     * It's done in the background because it needs the DB access, and the callback will be called
     * when it finishes.
     */
    public void queryProgramsForSeries(Program seriesProgram, ProgramLoadCallback callback) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            callback.onProgramLoadFinished(Collections.emptyList());
            return;
        }
        TvInputInfo input = Utils.getTvInputInfoForProgram(mAppContext, seriesProgram);
        if (input == null) {
            Log.e(TAG, "Can't find input for program: " + seriesProgram);
            return;
        }
        SeriesRecordingScheduler.getInstance(mAppContext).queryPrograms(
                SeriesRecording.builder(input.getId(), seriesProgram)
                        .setPriority(mScheduleManager.suggestNewPriority())
                        .build(), callback);
    }

    /**
     * Removes the series recording and all the corresponding schedules which are not started yet.
     */
    public void removeSeriesRecording(long seriesRecordingId) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        SeriesRecording series = mDataManager.getSeriesRecording(seriesRecordingId);
        if (series == null) {
            return;
        }
        for (ScheduledRecording schedule : mDataManager.getAllScheduledRecordings()) {
            if (schedule.getSeriesRecordingId() == seriesRecordingId) {
                if (schedule.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                    stopRecording(schedule);
                    break;
                }
            }
        }
        mDataManager.removeSeriesRecording(series);
    }

    /**
     * Stops the currently recorded program
     */
    public void stopRecording(final ScheduledRecording recording) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        synchronized (mListener) {
            for (final Entry<Listener, Handler> entry : mListener.entrySet()) {
                entry.getValue().post(new Runnable() {
                    @Override
                    public void run() {
                        entry.getKey().onStopRecordingRequested(recording);
                    }
                });
            }
        }
    }

    /**
     * Removes scheduled recordings or an existing recordings.
     */
    public void removeScheduledRecording(ScheduledRecording... schedules) {
        Log.i(TAG, "Removing " + Arrays.asList(schedules));
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return;
        }
        for (ScheduledRecording r : schedules) {
            if (r.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                stopRecording(r);
            } else {
                mDataManager.removeScheduledRecording(r);
            }
        }
    }

    /**
     * Removes the recorded program. It deletes the file if possible.
     */
    public void removeRecordedProgram(Uri recordedProgramUri) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            return;
        }
        removeRecordedProgram(ContentUris.parseId(recordedProgramUri));
    }

    /**
     * Removes the recorded program. It deletes the file if possible.
     */
    public void removeRecordedProgram(long recordedProgramId) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            return;
        }
        RecordedProgram recordedProgram = mDataManager.getRecordedProgram(recordedProgramId);
        if (recordedProgram != null) {
            removeRecordedProgram(recordedProgram);
        }
    }

    /**
     * Removes the recorded program. It deletes the file if possible.
     */
    public void removeRecordedProgram(final RecordedProgram recordedProgram) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            return;
        }
        new AsyncDbTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ContentResolver resolver = mAppContext.getContentResolver();
                resolver.delete(recordedProgram.getUri(), null, null);
                try {
                    Uri dataUri = recordedProgram.getDataUri();
                    if (dataUri != null && ContentResolver.SCHEME_FILE.equals(dataUri.getScheme())
                            && dataUri.getPath() != null) {
                        File recordedProgramPath = new File(dataUri.getPath());
                        if (!recordedProgramPath.exists()) {
                            if (DEBUG) Log.d(TAG, "File to delete not exist: "
                                    + recordedProgramPath);
                        } else {
                            Utils.deleteDirOrFile(recordedProgramPath);
                            if (DEBUG) {
                                Log.d(TAG, "Sucessfully deleted files of the recorded program: "
                                        + recordedProgram.getDataUri());
                            }
                        }
                    }
                } catch (SecurityException e) {
                    if (DEBUG) {
                        Log.d(TAG, "To delete " + recordedProgram
                                + "\nyou should manually delete video data at"
                                + "\nadb shell rm -rf " + recordedProgram.getDataUri());
                    }
                }
                return null;
            }
        }.executeOnDbThread();
    }

    /**
     * Remove all recorded programs due to missing storage.
     *
     * @param inputId for the recorded programs to remove
     */
    public void removeRecordedProgramByMissingStorage(final String inputId) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized())) {
            return;
        }
        new AsyncDbTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                ContentResolver resolver = mAppContext.getContentResolver();
                String args[] = { inputId };
                resolver.delete(TvContract.RecordedPrograms.CONTENT_URI,
                        TvContract.RecordedPrograms.COLUMN_INPUT_ID + " = ?", args);
                return null;
            }
        }.executeOnDbThread();
    }

    /**
     * Updates the scheduled recording.
     */
    public void updateScheduledRecording(ScheduledRecording recording) {
        if (SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            mDataManager.updateScheduledRecording(recording);
        }
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * this program is.
     *
     * @see DvrScheduleManager#getConflictingSchedules(Program)
     */
    public List<ScheduledRecording> getConflictingSchedules(Program program) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return Collections.emptyList();
        }
        return mScheduleManager.getConflictingSchedules(program);
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * this channel is.
     *
     * @see DvrScheduleManager#getConflictingSchedules(long, long, long)
     */
    public List<ScheduledRecording> getConflictingSchedules(long channelId, long startTimeMs,
            long endTimeMs) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return Collections.emptyList();
        }
        return mScheduleManager.getConflictingSchedules(channelId, startTimeMs, endTimeMs);
    }

    /**
     * Checks if the schedule is conflicting.
     *
     * <p>Note that the {@code schedule} should be the existing one. If not, this returns
     * {@code false}.
     */
    public boolean isConflicting(ScheduledRecording schedule) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return false;
        }
        return mScheduleManager.isConflicting(schedule);
    }

    /**
     * Returns priority ordered list of all scheduled recording that will not be recorded if
     * this channel is tuned to.
     *
     * @see DvrScheduleManager#getConflictingSchedulesForTune
     */
    public List<ScheduledRecording> getConflictingSchedulesForTune(long channelId) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return Collections.emptyList();
        }
        return mScheduleManager.getConflictingSchedulesForTune(channelId);
    }

    /**
     * Returns the earliest end time of the current recording for the TV input. If there are no
     * recordings, Long.MAX_VALUE is returned.
     */
    public long getEarliestRecordingEndTime(String inputId) {
        long result = Long.MAX_VALUE;
        for (ScheduledRecording schedule : mDataManager.getStartedRecordings()) {
            TvInputInfo input = Utils.getTvInputInfoForChannelId(mAppContext,
                    schedule.getChannelId());
            if (input != null && input.getId().equals(inputId)
                    && schedule.getEndTimeMs() < result) {
                result = schedule.getEndTimeMs();
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if the channel can be recorded.
     * <p>
     * Note that this method doesn't check the conflict of the schedule or available tuners.
     * This can be called from the UI before the schedules are loaded.
     */
    public boolean isChannelRecordable(Channel channel) {
        if (!mDataManager.isDvrScheduleLoadFinished() || channel == null) {
            return false;
        }
        TvInputInfo info = Utils.getTvInputInfoForChannelId(mAppContext, channel.getId());
        if (info == null) {
            Log.w(TAG, "Could not find TvInputInfo for " + channel);
            return false;
        }
        if (!info.canRecord()) {
            return false;
        }
        Program program = TvApplication.getSingletons(mAppContext).getProgramDataManager()
                .getCurrentProgram(channel.getId());
        return program == null || !program.isRecordingProhibited();
    }

    /**
     * Returns {@code true} if the program can be recorded.
     * <p>
     * Note that this method doesn't check the conflict of the schedule or available tuners.
     * This can be called from the UI before the schedules are loaded.
     */
    public boolean isProgramRecordable(Program program) {
        if (!mDataManager.isInitialized()) {
            return false;
        }
        TvInputInfo info = Utils.getTvInputInfoForProgram(mAppContext, program);
        if (info == null) {
            Log.w(TAG, "Could not find TvInputInfo for " + program);
            return false;
        }
        return info.canRecord() && !program.isRecordingProhibited();
    }

    /**
     * Returns the current recording for the channel.
     * <p>
     * This can be called from the UI before the schedules are loaded.
     */
    public ScheduledRecording getCurrentRecording(long channelId) {
        if (!mDataManager.isDvrScheduleLoadFinished()) {
            return null;
        }
        for (ScheduledRecording recording : mDataManager.getStartedRecordings()) {
            if (recording.getChannelId() == channelId) {
                return recording;
            }
        }
        return null;
    }

    /**
     * Returns the series recording related to the program.
     */
    @Nullable
    public SeriesRecording getSeriesRecording(Program program) {
        if (!SoftPreconditions.checkState(mDataManager.isDvrScheduleLoadFinished())) {
            return null;
        }
        return mDataManager.getSeriesRecording(program.getSeriesId());
    }

    @WorkerThread
    @VisibleForTesting
    // Should be public to use mock DvrManager object.
    public void addListener(Listener listener, @NonNull Handler handler) {
        SoftPreconditions.checkNotNull(handler);
        synchronized (mListener) {
            mListener.put(listener, handler);
        }
    }

    @WorkerThread
    @VisibleForTesting
    // Should be public to use mock DvrManager object.
    public void removeListener(Listener listener) {
        synchronized (mListener) {
            mListener.remove(listener);
        }
    }

    /**
     * Returns ScheduledRecording.builder based on {@code program}. If program is already started,
     * recording started time is clipped to the current time.
     */
    private ScheduledRecording.Builder createScheduledRecordingBuilder(String inputId,
            Program program) {
        ScheduledRecording.Builder builder = ScheduledRecording.builder(inputId, program);
        long time = System.currentTimeMillis();
        if (program.getStartTimeUtcMillis() < time && time < program.getEndTimeUtcMillis()) {
            builder.setStartTimeMs(time);
        }
        return builder;
    }

    /**
     * Returns a schedule which matches to the given episode.
     */
    public ScheduledRecording getScheduledRecording(String title, String seasonNumber,
            String episodeNumber) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized()) || title == null
                || seasonNumber == null || episodeNumber == null) {
            return null;
        }
        for (ScheduledRecording r : mDataManager.getAllScheduledRecordings()) {
            if (title.equals(r.getProgramTitle())
                    && seasonNumber.equals(r.getSeasonNumber())
                    && episodeNumber.equals(r.getEpisodeNumber())) {
                return r;
            }
        }
        return null;
    }

    /**
     * Returns a recorded program which is the same episode as the given {@code program}.
     */
    public RecordedProgram getRecordedProgram(String title, String seasonNumber,
            String episodeNumber) {
        if (!SoftPreconditions.checkState(mDataManager.isInitialized()) || title == null
                || seasonNumber == null || episodeNumber == null) {
            return null;
        }
        for (RecordedProgram r : mDataManager.getRecordedPrograms()) {
            if (title.equals(r.getTitle())
                    && seasonNumber.equals(r.getSeasonNumber())
                    && episodeNumber.equals(r.getEpisodeNumber())
                    && !r.isClipped()) {
                return r;
            }
        }
        return null;
    }

    /**
     * Listener internally used inside dvr package.
     */
    interface Listener {
        void onStopRecordingRequested(ScheduledRecording scheduledRecording);
    }
}
