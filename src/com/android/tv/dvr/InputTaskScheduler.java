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

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.tv.InputSessionManager;
import com.android.tv.data.Channel;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.util.Clock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The scheduler for a TV input.
 */
@MainThread
public class InputTaskScheduler {
    private static final String TAG = "InputTaskScheduler";
    private static final boolean DEBUG = false;

    private static final int MSG_ADD_SCHEDULED_RECORDING = 1;
    private static final int MSG_REMOVE_SCHEDULED_RECORDING = 2;
    private static final int MSG_UPDATE_SCHEDULED_RECORDING = 3;
    private static final int MSG_BUILD_SCHEDULE = 4;

    /**
     * Wraps a {@link RecordingTask} removing it from {@link #mPendingRecordings} when it is done.
     */
    public final class HandlerWrapper extends Handler {
        public static final int MESSAGE_REMOVE = 999;
        private final long mId;
        private final RecordingTask mTask;

        HandlerWrapper(Looper looper, ScheduledRecording scheduledRecording,
                RecordingTask recordingTask) {
            super(looper, recordingTask);
            mId = scheduledRecording.getId();
            mTask = recordingTask;
            mTask.setHandler(this);
        }

        @Override
        public void handleMessage(Message msg) {
            // The RecordingTask gets a chance first.
            // It must return false to pass this message to here.
            if (msg.what == MESSAGE_REMOVE) {
                if (DEBUG)  Log.d(TAG, "done " + mId);
                mPendingRecordings.remove(mId);
            }
            removeCallbacksAndMessages(null);
            mHandler.removeMessages(MSG_BUILD_SCHEDULE);
            mHandler.sendEmptyMessage(MSG_BUILD_SCHEDULE);
            super.handleMessage(msg);
        }
    }

    private TvInputInfo mInput;
    private final Looper mLooper;
    private final ChannelDataManager mChannelDataManager;
    private final DvrManager mDvrManager;
    private final WritableDvrDataManager mDataManager;
    private final InputSessionManager mSessionManager;
    private final Clock mClock;
    private final Context mContext;

    private final LongSparseArray<HandlerWrapper> mPendingRecordings = new LongSparseArray<>();
    private final Map<Long, ScheduledRecording> mWaitingSchedules = new ArrayMap<>();
    private final Handler mMainThreadHandler;
    private final Handler mHandler;
    private final Object mInputLock = new Object();
    private final RecordingTaskFactory mRecordingTaskFactory;

    public InputTaskScheduler(Context context, TvInputInfo input, Looper looper,
            ChannelDataManager channelDataManager, DvrManager dvrManager,
            DvrDataManager dataManager, InputSessionManager sessionManager, Clock clock) {
        this(context, input, looper, channelDataManager, dvrManager, dataManager, sessionManager,
                clock, new Handler(Looper.getMainLooper()), null, null);
    }

    @VisibleForTesting
    InputTaskScheduler(Context context, TvInputInfo input, Looper looper,
            ChannelDataManager channelDataManager, DvrManager dvrManager,
            DvrDataManager dataManager, InputSessionManager sessionManager, Clock clock,
            Handler mainThreadHandler, @Nullable Handler workerThreadHandler,
            RecordingTaskFactory recordingTaskFactory) {
        if (DEBUG) Log.d(TAG, "Creating scheduler for " + input);
        mContext = context;
        mInput = input;
        mLooper = looper;
        mChannelDataManager = channelDataManager;
        mDvrManager = dvrManager;
        mDataManager = (WritableDvrDataManager) dataManager;
        mSessionManager = sessionManager;
        mClock = clock;
        mMainThreadHandler = mainThreadHandler;
        mRecordingTaskFactory = recordingTaskFactory != null ? recordingTaskFactory
                : new RecordingTaskFactory() {
            @Override
            public RecordingTask createRecordingTask(ScheduledRecording schedule, Channel channel,
                    DvrManager dvrManager, InputSessionManager sessionManager,
                    WritableDvrDataManager dataManager, Clock clock) {
                return new RecordingTask(mContext, schedule, channel, mDvrManager, mSessionManager,
                        mDataManager, mClock);
            }
        };
        if (workerThreadHandler == null) {
            mHandler = new WorkerThreadHandler(looper);
        } else {
            mHandler = workerThreadHandler;
        }
    }

    /**
     * Adds a {@link ScheduledRecording}.
     */
    public void addSchedule(ScheduledRecording schedule) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_ADD_SCHEDULED_RECORDING, schedule));
    }

    @VisibleForTesting
    void handleAddSchedule(ScheduledRecording schedule) {
        if (mPendingRecordings.get(schedule.getId()) != null
                || mWaitingSchedules.containsKey(schedule.getId())) {
            return;
        }
        mWaitingSchedules.put(schedule.getId(), schedule);
        mHandler.removeMessages(MSG_BUILD_SCHEDULE);
        mHandler.sendEmptyMessage(MSG_BUILD_SCHEDULE);
    }

    /**
     * Removes the {@link ScheduledRecording}.
     */
    public void removeSchedule(ScheduledRecording schedule) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_REMOVE_SCHEDULED_RECORDING, schedule));
    }

    @VisibleForTesting
    void handleRemoveSchedule(ScheduledRecording schedule) {
        HandlerWrapper wrapper = mPendingRecordings.get(schedule.getId());
        if (wrapper != null) {
            wrapper.mTask.cancel();
            return;
        }
        if (mWaitingSchedules.containsKey(schedule.getId())) {
            mWaitingSchedules.remove(schedule.getId());
            mHandler.removeMessages(MSG_BUILD_SCHEDULE);
            mHandler.sendEmptyMessage(MSG_BUILD_SCHEDULE);
        }
    }

    /**
     * Updates the {@link ScheduledRecording}.
     */
    public void updateSchedule(ScheduledRecording schedule) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_SCHEDULED_RECORDING, schedule));
    }

    @VisibleForTesting
    void handleUpdateSchedule(ScheduledRecording schedule) {
        HandlerWrapper wrapper = mPendingRecordings.get(schedule.getId());
        if (wrapper != null) {
            if (schedule.getStartTimeMs() > mClock.currentTimeMillis()
                    && schedule.getStartTimeMs() > wrapper.mTask.getStartTimeMs()) {
                // It shouldn't have started. Cancel and put to the waiting list.
                // The schedules will be rebuilt when the task is removed.
                // The reschedule is called in Scheduler.
                wrapper.mTask.cancel();
                mWaitingSchedules.put(schedule.getId(), schedule);
                return;
            }
            wrapper.sendMessage(wrapper.obtainMessage(RecordingTask.MSG_UDPATE_SCHEDULE, schedule));
            return;
        }
        if (mWaitingSchedules.containsKey(schedule.getId())) {
            mWaitingSchedules.put(schedule.getId(), schedule);
            mHandler.removeMessages(MSG_BUILD_SCHEDULE);
            mHandler.sendEmptyMessage(MSG_BUILD_SCHEDULE);
        }
    }

    /**
     * Updates the TV input.
     */
    public void updateTvInputInfo(TvInputInfo input) {
        synchronized (mInputLock) {
            mInput = input;
        }
    }

    @VisibleForTesting
    void handleBuildSchedule() {
        if (mWaitingSchedules.isEmpty()) {
            return;
        }
        long currentTimeMs = mClock.currentTimeMillis();
        // Remove past schedules.
        for (Iterator<ScheduledRecording> iter = mWaitingSchedules.values().iterator();
                iter.hasNext(); ) {
            ScheduledRecording schedule = iter.next();
            if (schedule.getEndTimeMs() <= currentTimeMs) {
                fail(schedule);
                iter.remove();
            }
        }
        if (mWaitingSchedules.isEmpty()) {
            return;
        }
        // Record the schedules which should start now.
        List<ScheduledRecording> schedulesToStart = new ArrayList<>();
        for (ScheduledRecording schedule : mWaitingSchedules.values()) {
            if (schedule.getState() != ScheduledRecording.STATE_RECORDING_CANCELED
                    && schedule.getStartTimeMs() - RecordingTask.RECORDING_EARLY_START_OFFSET_MS
                    <= currentTimeMs && schedule.getEndTimeMs() > currentTimeMs) {
                schedulesToStart.add(schedule);
            }
        }
        Collections.sort(schedulesToStart, ScheduledRecording.START_TIME_THEN_PRIORITY_COMPARATOR);
        int tunerCount;
        synchronized (mInputLock) {
            tunerCount = mInput.canRecord() ? mInput.getTunerCount() : 0;
        }
        for (ScheduledRecording schedule : schedulesToStart) {
            if (hasTaskWhichFinishEarlier(schedule)) {
                // If there is a schedule which finishes earlier than the new schedule, rebuild the
                // schedules after it finishes.
                return;
            }
            if (mPendingRecordings.size() < tunerCount) {
                // Tuners available.
                createRecordingTask(schedule).start();
                mWaitingSchedules.remove(schedule.getId());
            } else {
                // No available tuners.
                RecordingTask task = getReplacableTask(schedule);
                if (task != null) {
                    task.stop();
                    // Just return. The schedules will be rebuilt after the task is stopped.
                    return;
                } else {
                    // TODO: Do not fail immediately. Start the recording later when available.
                    // There are no replaceable task. Remove it.
                    fail(schedule);
                    mWaitingSchedules.remove(schedule.getId());
                }
            }
        }
        if (mWaitingSchedules.isEmpty()) {
            return;
        }
        // Set next scheduling.
        long earliest = Long.MAX_VALUE;
        for (ScheduledRecording schedule : mWaitingSchedules.values()) {
            if (earliest > schedule.getStartTimeMs()) {
                earliest = schedule.getStartTimeMs();
            }
        }
        mHandler.sendEmptyMessageDelayed(MSG_BUILD_SCHEDULE, earliest
                - RecordingTask.RECORDING_EARLY_START_OFFSET_MS - currentTimeMs);
    }

    private RecordingTask createRecordingTask(ScheduledRecording schedule) {
        Channel channel = mChannelDataManager.getChannel(schedule.getChannelId());
        RecordingTask recordingTask = mRecordingTaskFactory.createRecordingTask(schedule, channel,
                mDvrManager, mSessionManager, mDataManager, mClock);
        HandlerWrapper handlerWrapper = new HandlerWrapper(mLooper, schedule, recordingTask);
        mPendingRecordings.put(schedule.getId(), handlerWrapper);
        return recordingTask;
    }

    private boolean hasTaskWhichFinishEarlier(ScheduledRecording schedule) {
        int size = mPendingRecordings.size();
        for (int i = 0; i < size; ++i) {
            RecordingTask task = mPendingRecordings.get(mPendingRecordings.keyAt(i)).mTask;
            if (task.getEndTimeMs() <= schedule.getStartTimeMs()) {
                return true;
            }
        }
        return false;
    }

    private RecordingTask getReplacableTask(ScheduledRecording schedule) {
        int size = mPendingRecordings.size();
        RecordingTask candidate = null;
        for (int i = 0; i < size; ++i) {
            RecordingTask task = mPendingRecordings.get(mPendingRecordings.keyAt(i)).mTask;
            if (schedule.getPriority() > task.getPriority()
                    && (candidate == null || candidate.getPriority() > task.getPriority())) {
                candidate = task;
            }
        }
        return candidate;
    }

    private void fail(ScheduledRecording schedule) {
        // It's called when the scheduling has been failed without creating RecordingTask.
        runOnMainHandler(new Runnable() {
            @Override
            public void run() {
                ScheduledRecording scheduleInManager =
                        mDataManager.getScheduledRecording(schedule.getId());
                if (scheduleInManager != null) {
                    // The schedule should be updated based on the object from DataManager in case
                    // when it has been updated.
                    mDataManager.changeState(scheduleInManager,
                            ScheduledRecording.STATE_RECORDING_FAILED);
                }
            }
        });
    }

    private void runOnMainHandler(Runnable runnable) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            runnable.run();
        } else {
            mMainThreadHandler.post(runnable);
        }
    }

    @VisibleForTesting
    interface RecordingTaskFactory {
        RecordingTask createRecordingTask(ScheduledRecording scheduledRecording, Channel channel,
                DvrManager dvrManager, InputSessionManager sessionManager,
                WritableDvrDataManager dataManager, Clock clock);
    }

    private class WorkerThreadHandler extends Handler {
        public WorkerThreadHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ADD_SCHEDULED_RECORDING:
                    handleAddSchedule((ScheduledRecording) msg.obj);
                    break;
                case MSG_REMOVE_SCHEDULED_RECORDING:
                    handleRemoveSchedule((ScheduledRecording) msg.obj);
                    break;
                case MSG_UPDATE_SCHEDULED_RECORDING:
                    handleUpdateSchedule((ScheduledRecording) msg.obj);
                case MSG_BUILD_SCHEDULE:
                    handleBuildSchedule();
                    break;
            }
        }
    }
}
