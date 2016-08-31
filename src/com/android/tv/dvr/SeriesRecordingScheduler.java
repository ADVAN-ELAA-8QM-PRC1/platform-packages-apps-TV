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

package com.android.tv.dvr;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Programs;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.annotation.WorkerThread;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.tv.ApplicationSingletons;
import com.android.tv.TvApplication;
import com.android.tv.common.CollectionUtils;
import com.android.tv.common.SharedPreferencesUtils;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.data.Program;
import com.android.tv.data.epg.EpgFetcher;
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener;
import com.android.tv.dvr.DvrDataManager.SeriesRecordingListener;
import com.android.tv.experiments.Experiments;
import com.android.tv.util.AsyncDbTask.AsyncProgramQueryTask;
import com.android.tv.util.AsyncDbTask.CursorFilter;
import com.android.tv.util.PermissionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Creates the {@link ScheduledRecording}s for the {@link SeriesRecording}.
 * <p>
 * The current implementation assumes that the series recordings are scheduled only for one channel.
 */
@TargetApi(Build.VERSION_CODES.N)
public class SeriesRecordingScheduler {
    private static final String TAG = "SeriesRecordingSchd";

    private static final int PROGRAM_ID_INDEX = Program.getColumnIndex(Programs._ID);
    private static final int RECORDING_PROHIBITED_INDEX =
            Program.getColumnIndex(Programs.COLUMN_RECORDING_PROHIBITED);

    private static final String PARAM_START_TIME = "start_time";
    private static final String PARAM_END_TIME = "end_time";

    private static final String PROGRAM_SELECTION =
            Programs.COLUMN_START_TIME_UTC_MILLIS + ">? AND (" +
            Programs.COLUMN_SEASON_DISPLAY_NUMBER + " IS NOT NULL OR " +
            Programs.COLUMN_EPISODE_DISPLAY_NUMBER + " IS NOT NULL) AND " +
            Programs.COLUMN_RECORDING_PROHIBITED + "=0";
    private static final String CHANNEL_ID_PREDICATE = Programs.COLUMN_CHANNEL_ID + "=?";

    private static final String KEY_FETCHED_SERIES_IDS =
            "SeriesRecordingScheduler.fetched_series_ids";

    @SuppressLint("StaticFieldLeak")
    private static SeriesRecordingScheduler sInstance;

    /**
     * Creates and returns the {@link SeriesRecordingScheduler}.
     */
    public static synchronized SeriesRecordingScheduler getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new SeriesRecordingScheduler(context);
        }
        return sInstance;
    }

    private final Context mContext;
    private final DvrManager mDvrManager;
    private final WritableDvrDataManager mDataManager;
    private final List<SeriesRecordingUpdateTask> mScheduleTasks = new ArrayList<>();
    private final List<FetchSeriesInfoTask> mFetchSeriesInfoTasks = new ArrayList<>();
    private final Set<String> mFetchedSeriesIds = new ArraySet<>();
    private final SharedPreferences mSharedPreferences;
    private boolean mStarted;

    private final SeriesRecordingListener mSeriesRecordingListener = new SeriesRecordingListener() {
        @Override
        public void onSeriesRecordingAdded(SeriesRecording... seriesRecordings) {
            for (SeriesRecording seriesRecording : seriesRecordings) {
                executeFetchSeriesInfoTask(seriesRecording);
            }
        }

        @Override
        public void onSeriesRecordingRemoved(SeriesRecording... seriesRecordings) {
            // Cancel the update.
            for (Iterator<SeriesRecordingUpdateTask> iter = mScheduleTasks.iterator();
                 iter.hasNext(); ) {
                SeriesRecordingUpdateTask task = iter.next();
                if (CollectionUtils.subtract(task.mSeriesRecordings, seriesRecordings,
                        SeriesRecording.ID_COMPARATOR).isEmpty()) {
                    task.cancel(true);
                    iter.remove();
                }
            }
        }

        @Override
        public void onSeriesRecordingChanged(SeriesRecording... seriesRecordings) {
            updateSchedules(Arrays.asList(seriesRecordings));
        }
    };

    private final ScheduledRecordingListener mScheduledRecordingListener =
            new ScheduledRecordingListener() {
                @Override
                public void onScheduledRecordingAdded(ScheduledRecording... schedules) {
                    // No need to update series recordings when the new schedule is added.
                }

                @Override
                public void onScheduledRecordingRemoved(ScheduledRecording... schedules) {
                    handleScheduledRecordingChange(Arrays.asList(schedules));
                }

                @Override
                public void onScheduledRecordingStatusChanged(ScheduledRecording... schedules) {
                    List<ScheduledRecording> schedulesForUpdate = new ArrayList<>();
                    for (ScheduledRecording r : schedules) {
                        if ((r.getState() == ScheduledRecording.STATE_RECORDING_FAILED
                                || r.getState() == ScheduledRecording.STATE_RECORDING_CLIPPED)
                                && r.getSeriesRecordingId() != SeriesRecording.ID_NOT_SET
                                && !TextUtils.isEmpty(r.getSeasonNumber())
                                && !TextUtils.isEmpty(r.getEpisodeNumber())) {
                            schedulesForUpdate.add(r);
                        }
                    }
                    if (!schedulesForUpdate.isEmpty()) {
                        handleScheduledRecordingChange(schedulesForUpdate);
                    }
                }

                private void handleScheduledRecordingChange(List<ScheduledRecording> schedules) {
                    if (schedules.isEmpty()) {
                        return;
                    }
                    Set<Long> seriesRecordingIds = new HashSet<>();
                    for (ScheduledRecording r : schedules) {
                        if (r.getSeriesRecordingId() != SeriesRecording.ID_NOT_SET) {
                            SoftPreconditions.checkState(r.getState()
                                    != ScheduledRecording.STATE_RECORDING_FINISHED);
                            seriesRecordingIds.add(r.getSeriesRecordingId());
                        }
                    }
                    if (!seriesRecordingIds.isEmpty()) {
                        List<SeriesRecording> seriesRecordings = new ArrayList<>();
                        for (Long id : seriesRecordingIds) {
                            SeriesRecording seriesRecording = mDataManager.getSeriesRecording(id);
                            if (seriesRecording != null) {
                                seriesRecordings.add(seriesRecording);
                            }
                        }
                        if (!seriesRecordings.isEmpty()) {
                            updateSchedules(seriesRecordings);
                        }
                    }
                }
            };

    private SeriesRecordingScheduler(Context context) {
        mContext = context.getApplicationContext();
        ApplicationSingletons appSingletons = TvApplication.getSingletons(context);
        mDvrManager = appSingletons.getDvrManager();
        mDataManager = (WritableDvrDataManager) appSingletons.getDvrDataManager();
        mSharedPreferences = context.getSharedPreferences(
                SharedPreferencesUtils.SHARED_PREF_SERIES_RECORDINGS, Context.MODE_PRIVATE);
        mFetchedSeriesIds.addAll(mSharedPreferences.getStringSet(KEY_FETCHED_SERIES_IDS,
                Collections.emptySet()));
    }

    /**
     * Starts the scheduler.
     */
    @MainThread
    public void start() {
        SoftPreconditions.checkState(mDataManager.isInitialized());
        if (mStarted) {
            return;
        }
        mStarted = true;
        mDataManager.addSeriesRecordingListener(mSeriesRecordingListener);
        mDataManager.addScheduledRecordingListener(mScheduledRecordingListener);
        startFetchingSeriesInfo();
        updateSchedules(mDataManager.getSeriesRecordings());
    }

    @MainThread
    public void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        for (FetchSeriesInfoTask task : mFetchSeriesInfoTasks) {
            task.cancel(true);
        }
        for (SeriesRecordingUpdateTask task : mScheduleTasks) {
            task.cancel(true);
        }
        mDataManager.removeScheduledRecordingListener(mScheduledRecordingListener);
        mDataManager.removeSeriesRecordingListener(mSeriesRecordingListener);
    }

    private void startFetchingSeriesInfo() {
        for (SeriesRecording seriesRecording : mDataManager.getSeriesRecordings()) {
            if (!mFetchedSeriesIds.contains(seriesRecording.getSeriesId())) {
                executeFetchSeriesInfoTask(seriesRecording);
            }
        }
    }

    private void executeFetchSeriesInfoTask(SeriesRecording seriesRecording) {
        if (Experiments.CLOUD_EPG.get()) {
            FetchSeriesInfoTask task = new FetchSeriesInfoTask(seriesRecording);
            task.execute();
            mFetchSeriesInfoTasks.add(task);
        }
    }

    /**
     * Creates/Updates the schedules for all the series recordings.
     */
    @MainThread
    public void updateSchedules() {
        if (!mStarted) {
            return;
        }
        updateSchedules(mDataManager.getSeriesRecordings());
    }

    private void updateSchedules(Collection<SeriesRecording> seriesRecordings) {
        Set<SeriesRecording> previousSeriesRecordings = new HashSet<>();
        for (Iterator<SeriesRecordingUpdateTask> iter = mScheduleTasks.iterator();
             iter.hasNext(); ) {
            SeriesRecordingUpdateTask task = iter.next();
            if (CollectionUtils.containsAny(task.mSeriesRecordings, seriesRecordings,
                    SeriesRecording.ID_COMPARATOR)) {
                // The task is affected by the seriesRecordings
                task.cancel(true);
                previousSeriesRecordings.addAll(task.mSeriesRecordings);
                iter.remove();
            }
        }
        List<SeriesRecording> seriesRecordingsToUpdate = CollectionUtils.union(seriesRecordings,
                previousSeriesRecordings, SeriesRecording.ID_COMPARATOR);
        for (Iterator<SeriesRecording> iter = seriesRecordingsToUpdate.iterator();
                iter.hasNext(); ) {
            if (mDataManager.getSeriesRecording(iter.next().getId()) == null) {
                // Series recording has been removed.
                iter.remove();
            }
        }
        if (seriesRecordingsToUpdate.isEmpty()) {
            return;
        }
        List<SeriesRecordingUpdateTask> tasksToRun = new ArrayList<>();
        if (needToReadAllChannels(seriesRecordingsToUpdate)) {
            SeriesRecordingUpdateTask task = new SeriesRecordingUpdateTask(seriesRecordingsToUpdate,
                    createSqlParams(seriesRecordingsToUpdate, null));
            tasksToRun.add(task);
            mScheduleTasks.add(task);
        } else {
            for (SeriesRecording seriesRecording : seriesRecordingsToUpdate) {
                SeriesRecordingUpdateTask task = new SeriesRecordingUpdateTask(
                        Collections.singletonList(seriesRecording),
                        createSqlParams(Collections.singletonList(seriesRecording), null));
                tasksToRun.add(task);
                mScheduleTasks.add(task);
            }
        }
        if (mDataManager.isDvrScheduleLoadFinished()) {
            runTasks(tasksToRun);
        }
    }

    private void runTasks(List<SeriesRecordingUpdateTask> tasks) {
        for (SeriesRecordingUpdateTask task : tasks) {
            task.executeOnDbThread();
        }
    }

    private boolean needToReadAllChannels(List<SeriesRecording> seriesRecordingsToUpdate) {
        for (SeriesRecording seriesRecording : seriesRecordingsToUpdate) {
            if (seriesRecording.getChannelOption() == SeriesRecording.OPTION_CHANNEL_ALL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Queries the programs which are related to the series.
     * <p>
     * This is called from the UI when the series recording is created.
     */
    public void queryPrograms(SeriesRecording series, ProgramLoadCallback callback) {
        SoftPreconditions.checkState(mDataManager.isInitialized());
        Set<ScheduledEpisode> scheduledEpisodes = new HashSet<>();
        for (RecordedProgram recordedProgram : mDataManager.getRecordedPrograms()) {
            if (series.getSeriesId().equals(recordedProgram.getSeriesId())) {
                scheduledEpisodes.add(new ScheduledEpisode(series.getId(),
                        recordedProgram.getSeasonNumber(), recordedProgram.getEpisodeNumber()));
            }
        }
        SqlParams sqlParams = createSqlParams(Collections.singletonList(series), scheduledEpisodes);
        new AsyncProgramQueryTask(mContext.getContentResolver(), sqlParams.uri, sqlParams.selection,
                sqlParams.selectionArgs, null, sqlParams.filter) {
            @Override
            protected void onPostExecute(List<Program> programs) {
                SoftPreconditions.checkNotNull(programs);
                if (programs == null) {
                    Log.e(TAG, "Creating schedules for series recording failed: " + series);
                    callback.onProgramLoadFinished(Collections.emptyList());
                } else {
                    Map<Long, List<Program>> seriesProgramMap = pickOneProgramPerEpisode(
                            Collections.singletonList(series), programs);
                    callback.onProgramLoadFinished(seriesProgramMap.get(series.getId()));
                }
            }
        }.executeOnDbThread();
        // To shorten the response time from UI, cancel and restart the background job.
        restartTasks();
    }

    private void restartTasks() {
        Set<SeriesRecording> seriesRecordings = new HashSet<>();
        for (SeriesRecordingUpdateTask task : mScheduleTasks) {
            seriesRecordings.addAll(task.mSeriesRecordings);
            task.cancel(true);
        }
        mScheduleTasks.clear();
        updateSchedules(seriesRecordings);
    }

    private SqlParams createSqlParams(List<SeriesRecording> seriesRecordings,
            Set<ScheduledEpisode> scheduledEpisodes) {
        SqlParams sqlParams = new SqlParams();
        if (PermissionUtils.hasAccessAllEpg(mContext)) {
            sqlParams.uri = Programs.CONTENT_URI;
            if (needToReadAllChannels(seriesRecordings)) {
                sqlParams.selection = PROGRAM_SELECTION;
                sqlParams.selectionArgs = new String[] {Long.toString(System.currentTimeMillis())};
            } else {
                SoftPreconditions.checkArgument(seriesRecordings.size() == 1);
                sqlParams.selection = PROGRAM_SELECTION + " AND " + CHANNEL_ID_PREDICATE;
                sqlParams.selectionArgs = new String[] {Long.toString(System.currentTimeMillis()),
                        Long.toString(seriesRecordings.get(0).getChannelId())};
            }
            sqlParams.filter = new SeriesRecordingCursorFilter(seriesRecordings, scheduledEpisodes);
        } else {
            if (needToReadAllChannels(seriesRecordings)) {
                sqlParams.uri = Programs.CONTENT_URI.buildUpon()
                        .appendQueryParameter(PARAM_START_TIME,
                                String.valueOf(System.currentTimeMillis()))
                        .appendQueryParameter(PARAM_END_TIME, String.valueOf(Long.MAX_VALUE))
                        .build();
            } else {
                SoftPreconditions.checkArgument(seriesRecordings.size() == 1);
                sqlParams.uri = TvContract.buildProgramsUriForChannel(
                        seriesRecordings.get(0).getChannelId(),
                        System.currentTimeMillis(), Long.MAX_VALUE);
            }
            sqlParams.selection = null;
            sqlParams.selectionArgs = null;
            sqlParams.filter = new SeriesRecordingCursorFilterForNonSystem(seriesRecordings,
                    scheduledEpisodes);
        }
        return sqlParams;
    }

    @VisibleForTesting
    static boolean isEpisodeScheduled(Collection<ScheduledEpisode> scheduledEpisodes,
            ScheduledEpisode episode) {
        // The episode whose season number or episode number is null will always be scheduled.
        return scheduledEpisodes.contains(episode) && !TextUtils.isEmpty(episode.seasonNumber)
                && !TextUtils.isEmpty(episode.episodeNumber);
    }

    /**
     * Pick one program per an episode.
     *
     * <p>Note that the programs which has been already scheduled have the highest priority, and all
     * of them are added even though they are the same episodes. That's because the schedules
     * should be added to the series recording.
     * <p>If there are no existing schedules for an episode, one program which starts earlier is
     * picked.
     */
    private Map<Long, List<Program>> pickOneProgramPerEpisode(
            List<SeriesRecording> seriesRecordings, List<Program> programs) {
        return pickOneProgramPerEpisode(mDataManager, seriesRecordings, programs);
    }

    /**
     * @see #pickOneProgramPerEpisode(List, List)
     */
    @VisibleForTesting
    static Map<Long, List<Program>> pickOneProgramPerEpisode(DvrDataManager dataManager,
            List<SeriesRecording> seriesRecordings, List<Program> programs) {
        // Initialize.
        Map<Long, List<Program>> result = new HashMap<>();
        Map<String, Long> seriesRecordingIds = new HashMap<>();
        for (SeriesRecording seriesRecording : seriesRecordings) {
            result.put(seriesRecording.getId(), new ArrayList<>());
            seriesRecordingIds.put(seriesRecording.getSeriesId(), seriesRecording.getId());
        }
        // Group programs by the episode.
        Map<ScheduledEpisode, List<Program>> programsForEpisodeMap = new HashMap<>();
        for (Program program : programs) {
            long seriesRecordingId = seriesRecordingIds.get(program.getSeriesId());
            if (TextUtils.isEmpty(program.getSeasonNumber())
                    || TextUtils.isEmpty(program.getEpisodeNumber())) {
                // Add all the programs if it doesn't have season number or episode number.
                result.get(seriesRecordingId).add(program);
                continue;
            }
            ScheduledEpisode episode = new ScheduledEpisode(seriesRecordingId,
                    program.getSeasonNumber(), program.getEpisodeNumber());
            List<Program> programsForEpisode = programsForEpisodeMap.get(episode);
            if (programsForEpisode == null) {
                programsForEpisode = new ArrayList<>();
                programsForEpisodeMap.put(episode, programsForEpisode);
            }
            programsForEpisode.add(program);
        }
        // Pick one program.
        for (Entry<ScheduledEpisode, List<Program>> entry : programsForEpisodeMap.entrySet()) {
            List<Program> programsForEpisode = entry.getValue();
            Collections.sort(programsForEpisode, new Comparator<Program>() {
                @Override
                public int compare(Program lhs, Program rhs) {
                    // Place the existing schedule first.
                    boolean lhsScheduled = isProgramScheduled(dataManager, lhs);
                    boolean rhsScheduled = isProgramScheduled(dataManager, rhs);
                    if (lhsScheduled && !rhsScheduled) {
                        return -1;
                    }
                    if (!lhsScheduled && rhsScheduled) {
                        return 1;
                    }
                    // Sort by the start time in ascending order.
                    return lhs.compareTo(rhs);
                }
            });
            boolean added = false;
            // Add all the scheduled programs
            List<Program> programsForSeries = result.get(entry.getKey().seriesRecordingId);
            for (Program program : programsForEpisode) {
                if (isProgramScheduled(dataManager, program)) {
                    programsForSeries.add(program);
                    added = true;
                } else if (!added) {
                    programsForSeries.add(program);
                    break;
                }
            }
        }
        return result;
    }

    private static boolean isProgramScheduled(DvrDataManager dataManager, Program program) {
        ScheduledRecording schedule =
                dataManager.getScheduledRecordingForProgramId(program.getId());
        return schedule != null && schedule.getState()
                == ScheduledRecording.STATE_RECORDING_NOT_STARTED;
    }

    private void updateFetchedSeries() {
        mSharedPreferences.edit().putStringSet(KEY_FETCHED_SERIES_IDS, mFetchedSeriesIds).apply();
    }

    /**
     * This works only for the existing series recordings. Do not use this task for the
     * "adding series recording" UI.
     */
    private class SeriesRecordingUpdateTask extends AsyncProgramQueryTask {
        private final List<SeriesRecording> mSeriesRecordings = new ArrayList<>();

        SeriesRecordingUpdateTask(List<SeriesRecording> seriesRecordings, SqlParams sqlParams) {
            super(mContext.getContentResolver(), sqlParams.uri, sqlParams.selection,
                    sqlParams.selectionArgs, null, sqlParams.filter);
            mSeriesRecordings.addAll(seriesRecordings);
        }

        @Override
        protected void onPostExecute(List<Program> programs) {
            mScheduleTasks.remove(this);
            if (programs == null) {
                Log.e(TAG, "Creating schedules for series recording failed: " + mSeriesRecordings);
                return;
            }
            Map<Long, List<Program>> seriesProgramMap = pickOneProgramPerEpisode(
                    mSeriesRecordings, programs);
            for (SeriesRecording seriesRecording : mSeriesRecordings) {
                // Check the series recording is still valid.
                if (mDataManager.getSeriesRecording(seriesRecording.getId()) == null) {
                    continue;
                }
                List<Program> programsToSchedule = seriesProgramMap.get(seriesRecording.getId());
                if (mDataManager.getSeriesRecording(seriesRecording.getId()) != null
                        && !programsToSchedule.isEmpty()) {
                    mDvrManager.addScheduleToSeriesRecording(seriesRecording, programsToSchedule);
                }
            }
        }

        @Override
        protected void onCancelled(List<Program> programs) {
            mScheduleTasks.remove(this);
        }
    }

    /**
     * Filter the programs which match the series recording. The episodes which the schedules are
     * already created for are filtered out too.
     */
    private class SeriesRecordingCursorFilter implements CursorFilter {
        private final List<SeriesRecording> mSeriesRecording = new ArrayList<>();
        private final Set<Long> mDisallowedProgramIds = new HashSet<>();
        private final Set<ScheduledEpisode> mScheduledEpisodes = new HashSet<>();

        SeriesRecordingCursorFilter(List<SeriesRecording> seriesRecordings,
                Set<ScheduledEpisode> scheduledEpisodes) {
            mSeriesRecording.addAll(seriesRecordings);
            mDisallowedProgramIds.addAll(mDataManager.getDisallowedProgramIds());
            Set<Long> seriesRecordingIds = new HashSet<>();
            for (SeriesRecording r : seriesRecordings) {
                seriesRecordingIds.add(r.getId());
            }
            if (scheduledEpisodes != null) {
                mScheduledEpisodes.addAll(scheduledEpisodes);
            }
            for (ScheduledRecording r : mDataManager.getAllScheduledRecordings()) {
                if (seriesRecordingIds.contains(r.getSeriesRecordingId())
                        && r.getState() != ScheduledRecording.STATE_RECORDING_FAILED
                        && r.getState() != ScheduledRecording.STATE_RECORDING_CLIPPED) {
                    mScheduledEpisodes.add(new ScheduledEpisode(r));
                }
            }
        }

        @Override
        @WorkerThread
        public boolean filter(Cursor c) {
            if (mDisallowedProgramIds.contains(c.getLong(PROGRAM_ID_INDEX))) {
                return false;
            }
            Program program = Program.fromCursor(c);
            for (SeriesRecording seriesRecording : mSeriesRecording) {
                boolean programMatches = seriesRecording.matchProgram(program);
                if (programMatches && !isEpisodeScheduled(mScheduledEpisodes, new ScheduledEpisode(
                        seriesRecording.getId(), program.getSeasonNumber(),
                        program.getEpisodeNumber()))) {
                    return true;
                }
            }
            return false;
        }
    }

    private class SeriesRecordingCursorFilterForNonSystem extends SeriesRecordingCursorFilter {
        SeriesRecordingCursorFilterForNonSystem(List<SeriesRecording> seriesRecordings,
                Set<ScheduledEpisode> scheduledEpisodes) {
            super(seriesRecordings, scheduledEpisodes);
        }

        @Override
        public boolean filter(Cursor c) {
            return c.getInt(RECORDING_PROHIBITED_INDEX) != 0 && super.filter(c);
        }
    }

    private static class SqlParams {
        public Uri uri;
        public String selection;
        public String[] selectionArgs;
        public CursorFilter filter;
    }

    @VisibleForTesting
    static class ScheduledEpisode {
        public final long seriesRecordingId;
        public final String seasonNumber;
        public final String episodeNumber;

        /**
         * Create a new Builder with the values set from an existing {@link ScheduledRecording}.
         */
        ScheduledEpisode(ScheduledRecording r) {
            this(r.getSeriesRecordingId(), r.getSeasonNumber(), r.getEpisodeNumber());
        }

        public ScheduledEpisode(long seriesRecordingId, String seasonNumber, String episodeNumber) {
            this.seriesRecordingId = seriesRecordingId;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ScheduledEpisode)) return false;
            ScheduledEpisode that = (ScheduledEpisode) o;
            return seriesRecordingId == that.seriesRecordingId
                    && Objects.equals(seasonNumber, that.seasonNumber)
                    && Objects.equals(episodeNumber, that.episodeNumber);
        }

        @Override
        public int hashCode() {
            return Objects.hash(seriesRecordingId, seasonNumber, episodeNumber);
        }

        @Override
        public String toString() {
            return "ScheduledEpisode{" +
                    "seriesRecordingId=" + seriesRecordingId +
                    ", seasonNumber='" + seasonNumber +
                    ", episodeNumber=" + episodeNumber +
                    '}';
        }
    }

    private class FetchSeriesInfoTask extends AsyncTask<Void, Void, SeriesInfo> {
        private SeriesRecording mSeriesRecording;

        FetchSeriesInfoTask(SeriesRecording seriesRecording) {
            mSeriesRecording = seriesRecording;
        }

        String getSeriesId() {
            return mSeriesRecording.getSeriesId();
        }

        @Override
        protected SeriesInfo doInBackground(Void... voids) {
            return EpgFetcher.createEpgReader(mContext)
                    .getSeriesInfo(mSeriesRecording.getSeriesId());
        }

        @Override
        protected void onPostExecute(SeriesInfo seriesInfo) {
            if (seriesInfo != null) {
                mDataManager.updateSeriesRecording(SeriesRecording.buildFrom(mSeriesRecording)
                        .setTitle(seriesInfo.getTitle())
                        .setDescription(seriesInfo.getDescription())
                        .setLongDescription(seriesInfo.getLongDescription())
                        .setCanonicalGenreIds(seriesInfo.getCanonicalGenreIds())
                        .setPosterUri(seriesInfo.getPosterUri())
                        .setPhotoUri(seriesInfo.getPhotoUri())
                        .build());
                mFetchedSeriesIds.add(seriesInfo.getId());
                updateFetchedSeries();
            }
            mFetchSeriesInfoTasks.remove(this);
        }

        @Override
        protected void onCancelled(SeriesInfo seriesInfo) {
            mFetchSeriesInfoTasks.remove(this);
        }
    }

    /**
     * Called when the program loading is finished for the series recording.
     */
    public interface ProgramLoadCallback {
        void onProgramLoadFinished(@NonNull List<Program> programs);
    }
}
