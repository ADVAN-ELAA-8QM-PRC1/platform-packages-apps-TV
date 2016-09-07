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
import android.content.ContentUris;
import android.content.Context;
import android.database.ContentObserver;
import android.media.tv.TvContract.Programs;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.MainThread;
import android.support.annotation.VisibleForTesting;

import com.android.tv.data.Program;
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener;
import com.android.tv.util.AsyncDbTask.AsyncQueryProgramTask;
import com.android.tv.util.TvProviderUriMatcher;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

/**
 * A class to synchronizes DVR DB with TvProvider.
 */
@MainThread
@TargetApi(Build.VERSION_CODES.N)
class DvrDbSync {
    private final Context mContext;
    private final DvrDataManagerImpl mDataManager;
    private UpdateProgramTask mUpdateProgramTask;
    private final Queue<Long> mProgramIdQueue = new LinkedList<>();
    private final ContentObserver mProgramsContentObserver = new ContentObserver(new Handler(
            Looper.getMainLooper())) {
        @SuppressLint("SwitchIntDef")
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            switch (TvProviderUriMatcher.match(uri)) {
                case TvProviderUriMatcher.MATCH_PROGRAM:
                    onProgramsUpdated();
                    break;
                case TvProviderUriMatcher.MATCH_PROGRAM_ID:
                    addProgramIdToCheckIfNeeded(mDataManager.getScheduledRecordingForProgramId(
                            ContentUris.parseId(uri)));
                    break;
            }
        }
    };
    private final ScheduledRecordingListener mScheduleListener = new ScheduledRecordingListener() {
        @Override
        public void onScheduledRecordingAdded(ScheduledRecording... schedules) {
            for (ScheduledRecording schedule : schedules) {
                addProgramIdToCheckIfNeeded(schedule);
            }
        }

        @Override
        public void onScheduledRecordingRemoved(ScheduledRecording... schedules) { }

        @Override
        public void onScheduledRecordingStatusChanged(ScheduledRecording... schedules) {
            for (ScheduledRecording schedule : schedules) {
                mProgramIdQueue.remove(schedule.getProgramId());
            }
        }
    };

    public DvrDbSync(Context context, DvrDataManagerImpl dataManager) {
        mContext = context;
        mDataManager = dataManager;
    }

    /**
     * Starts the DB sync.
     */
    public void start() {
        mContext.getContentResolver().registerContentObserver(Programs.CONTENT_URI, true,
                mProgramsContentObserver);
        mDataManager.addScheduledRecordingListener(mScheduleListener);
        onProgramsUpdated();
    }

    /**
     * Stops the DB sync.
     */
    public void stop() {
        mProgramIdQueue.clear();
        if (mUpdateProgramTask != null) {
            mUpdateProgramTask.cancel(true);
        }
        mDataManager.removeScheduledRecordingListener(mScheduleListener);
        mContext.getContentResolver().unregisterContentObserver(mProgramsContentObserver);
    }

    private void onProgramsUpdated() {
        for (ScheduledRecording schedule : mDataManager.getAllScheduledRecordings()) {
            addProgramIdToCheckIfNeeded(schedule);
        }
    }

    private void addProgramIdToCheckIfNeeded(ScheduledRecording schedule) {
        if (schedule == null) {
            return;
        }
        long programId = schedule.getProgramId();
        if (programId != ScheduledRecording.ID_NOT_SET
                && !mProgramIdQueue.contains(programId)
                && (schedule.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED
                || schedule.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS)) {
            mProgramIdQueue.offer(programId);
            startNextUpdateIfNeeded();
        }
    }

    private void startNextUpdateIfNeeded() {
        if (mProgramIdQueue.isEmpty()) {
            return;
        }
        if (mUpdateProgramTask == null || mUpdateProgramTask.isCancelled()) {
            mUpdateProgramTask = new UpdateProgramTask(mProgramIdQueue.poll());
            mUpdateProgramTask.executeOnDbThread();
        }
    }

    @VisibleForTesting
    void handleUpdateProgram(Program program, long programId) {
        ScheduledRecording schedule = mDataManager.getScheduledRecordingForProgramId(programId);
        if (schedule != null
                && (schedule.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED
                || schedule.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS)) {
            if (program == null) {
                mDataManager.removeScheduledRecording(schedule);
            } else {
                long currentTimeMs = System.currentTimeMillis();
                // Change start time only when the recording start time has not passed.
                boolean needToChangeStartTime = schedule.getStartTimeMs() > currentTimeMs
                        && program.getStartTimeUtcMillis() != schedule.getStartTimeMs();
                ScheduledRecording.Builder builder = ScheduledRecording.buildFrom(schedule)
                        .setEndTimeMs(program.getEndTimeUtcMillis())
                        .setSeasonNumber(program.getSeasonNumber())
                        .setEpisodeNumber(program.getEpisodeNumber())
                        .setEpisodeTitle(program.getEpisodeTitle())
                        .setProgramDescription(program.getDescription())
                        .setProgramLongDescription(program.getLongDescription())
                        .setProgramPosterArtUri(program.getPosterArtUri())
                        .setProgramThumbnailUri(program.getThumbnailUri());
                if (needToChangeStartTime) {
                    mDataManager.updateScheduledRecording(
                            builder.setStartTimeMs(program.getStartTimeUtcMillis()).build());
                } else if (schedule.getEndTimeMs() != program.getEndTimeUtcMillis()
                        || !Objects.equals(schedule.getSeasonNumber(), program.getSeasonNumber())
                        || !Objects.equals(schedule.getEpisodeNumber(), program.getEpisodeNumber())
                        || !Objects.equals(schedule.getEpisodeTitle(), program.getEpisodeTitle())
                        || !Objects.equals(schedule.getProgramDescription(),
                        program.getDescription())
                        || !Objects.equals(schedule.getProgramLongDescription(),
                        program.getLongDescription())
                        || !Objects.equals(schedule.getProgramPosterArtUri(),
                        program.getPosterArtUri())
                        || !Objects.equals(schedule.getProgramThumbnailUri(),
                        program.getThumbnailUri())) {
                    mDataManager.updateScheduledRecording(builder.build());
                }
            }
        }
    }

    private class UpdateProgramTask extends AsyncQueryProgramTask {
        private final long mProgramId;

        public UpdateProgramTask(long programId) {
            super(mContext.getContentResolver(), programId);
            mProgramId = programId;
        }

        @Override
        protected void onCancelled(Program program) {
            mUpdateProgramTask = null;
            startNextUpdateIfNeeded();
        }

        @Override
        protected void onPostExecute(Program program) {
            mUpdateProgramTask = null;
            handleUpdateProgram(program, mProgramId);
            startNextUpdateIfNeeded();
        }
    }
}
