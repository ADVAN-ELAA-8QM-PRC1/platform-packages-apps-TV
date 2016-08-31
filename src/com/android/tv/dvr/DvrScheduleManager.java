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
import android.content.Context;
import android.media.tv.TvInputInfo;
import android.os.Build;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.util.ArraySet;
import android.util.Range;

import com.android.tv.ApplicationSingletons;
import com.android.tv.TvApplication;
import com.android.tv.common.SoftPreconditions;
import com.android.tv.data.Channel;
import com.android.tv.data.ChannelDataManager;
import com.android.tv.data.Program;
import com.android.tv.dvr.DvrDataManager.OnDvrScheduleLoadFinishedListener;
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener;
import com.android.tv.util.Utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class to manage the schedules.
 */
@TargetApi(Build.VERSION_CODES.N)
@MainThread
public class DvrScheduleManager {
    private static final String TAG = "DvrScheduleManager";

    /**
     * The default priority of scheduled recording.
     */
    public static final long DEFAULT_PRIORITY = Long.MAX_VALUE >> 1;
    /**
     * The default priority of series recording.
     */
    public static final long DEFAULT_SERIES_PRIORITY = DEFAULT_PRIORITY >> 1;
    // The new priority will have the offset from the existing one.
    private static final long PRIORITY_OFFSET = 1024;

    private final Context mContext;
    private final DvrDataManagerImpl mDataManager;
    private final ChannelDataManager mChannelDataManager;

    private final Map<String, List<ScheduledRecording>> mInputScheduleMap = new HashMap<>();
    private final Map<String, List<ScheduledRecording>> mInputConflictMap = new HashMap<>();

    private boolean mInitialized;

    private final Set<ScheduledRecordingListener> mScheduledRecordingListeners = new ArraySet<>();
    private final Set<OnConflictStateChangeListener> mOnConflictStateChangeListeners =
            new ArraySet<>();

    public DvrScheduleManager(Context context) {
        mContext = context;
        ApplicationSingletons appSingletons = TvApplication.getSingletons(context);
        mDataManager = (DvrDataManagerImpl) appSingletons.getDvrDataManager();
        mChannelDataManager = appSingletons.getChannelDataManager();
        if (mDataManager.isDvrScheduleLoadFinished() && mChannelDataManager.isDbLoadFinished()) {
            buildData();
        } else {
            mDataManager.addDvrScheduleLoadFinishedListener(
                    new OnDvrScheduleLoadFinishedListener() {
                        @Override
                        public void onDvrScheduleLoadFinished() {
                            mDataManager.removeDvrScheduleLoadFinishedListener(this);
                            if (mChannelDataManager.isDbLoadFinished() && !mInitialized) {
                                buildData();
                            }
                        }
                    });
        }
        ScheduledRecordingListener scheduledRecordingListener = new ScheduledRecordingListener() {
            @Override
            public void onScheduledRecordingAdded(ScheduledRecording... scheduledRecordings) {
                if (!mInitialized) {
                    return;
                }
                for (ScheduledRecording schedule : scheduledRecordings) {
                    if (!schedule.isNotStarted() && !schedule.isInProgress()) {
                        continue;
                    }
                    TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext,
                            schedule.getChannelId());
                    if (input == null) {
                        // Input removed.
                        continue;
                    }
                    String inputId = input.getId();
                    List<ScheduledRecording> schedules = mInputScheduleMap.get(inputId);
                    if (schedules == null) {
                        schedules = new ArrayList<>();
                        mInputScheduleMap.put(inputId, schedules);
                    }
                    schedules.add(schedule);
                }
                onSchedulesChanged();
                notifyScheduledRecordingAdded(scheduledRecordings);
            }

            @Override
            public void onScheduledRecordingRemoved(ScheduledRecording... scheduledRecordings) {
                if (!mInitialized) {
                    return;
                }
                for (ScheduledRecording schedule : scheduledRecordings) {
                    TvInputInfo input = Utils
                            .getTvInputInfoForChannelId(mContext, schedule.getChannelId());
                    if (input == null) {
                        // Input removed.
                        continue;
                    }
                    String inputId = input.getId();
                    List<ScheduledRecording> schedules = mInputScheduleMap.get(inputId);
                    if (schedules != null) {
                        schedules.remove(schedule);
                        if (schedules.isEmpty()) {
                            mInputScheduleMap.remove(inputId);
                        }
                    }
                }
                onSchedulesChanged();
                notifyScheduledRecordingRemoved(scheduledRecordings);
            }

            @Override
            public void onScheduledRecordingStatusChanged(
                    ScheduledRecording... scheduledRecordings) {
                if (!mInitialized) {
                    return;
                }
                for (ScheduledRecording schedule : scheduledRecordings) {
                    TvInputInfo input = Utils
                            .getTvInputInfoForChannelId(mContext, schedule.getChannelId());
                    if (input == null) {
                        // Input removed.
                        continue;
                    }
                    String inputId = input.getId();
                    List<ScheduledRecording> schedules = mInputScheduleMap.get(inputId);
                    if (schedules == null) {
                        schedules = new ArrayList<>();
                        mInputScheduleMap.put(inputId, schedules);
                    }
                    // Compare ID because ScheduledRecording.equals() doesn't work if the state
                    // is changed.
                    Iterator<ScheduledRecording> i = schedules.iterator();
                    while (i.hasNext()) {
                        if (i.next().getId() == schedule.getId()) {
                            i.remove();
                            break;
                        }
                    }
                    if (schedule.isNotStarted() || schedule.isInProgress()) {
                        schedules.add(schedule);
                    }
                    if (schedules.isEmpty()) {
                        mInputScheduleMap.remove(inputId);
                    }
                }
                onSchedulesChanged();
                notifyScheduledRecordingStatusChanged(scheduledRecordings);
            }
        };
        mDataManager.addScheduledRecordingListener(scheduledRecordingListener);
        ChannelDataManager.Listener channelDataManagerListener = new ChannelDataManager.Listener() {
            @Override
            public void onLoadFinished() {
                if (mDataManager.isDvrScheduleLoadFinished() && !mInitialized) {
                    buildData();
                }
            }

            @Override
            public void onChannelListUpdated() {
                if (mDataManager.isDvrScheduleLoadFinished()) {
                    buildData();
                }
            }

            @Override
            public void onChannelBrowsableChanged() {
            }
        };
        mChannelDataManager.addListener(channelDataManagerListener);
    }

    /**
     * Returns the started recordings for the given input.
     */
    private List<ScheduledRecording> getStartedRecordings(String inputId) {
        if (!SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet")) {
            return Collections.emptyList();
        }
        List<ScheduledRecording> result = new ArrayList<>();
        List<ScheduledRecording> schedules = mInputScheduleMap.get(inputId);
        if (schedules != null) {
            for (ScheduledRecording schedule : schedules) {
                if (schedule.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                    result.add(schedule);
                }
            }
        }
        return result;
    }

    private void buildData() {
        mInputScheduleMap.clear();
        for (ScheduledRecording schedule : mDataManager.getAllScheduledRecordings()) {
            if (!schedule.isNotStarted() && !schedule.isInProgress()) {
                continue;
            }
            Channel channel = mChannelDataManager.getChannel(schedule.getChannelId());
            if (channel != null) {
                String inputId = channel.getInputId();
                // Do not check whether the input is valid or not. The input might be temporarily
                // invalid.
                List<ScheduledRecording> schedules = mInputScheduleMap.get(inputId);
                if (schedules == null) {
                    schedules = new ArrayList<>();
                    mInputScheduleMap.put(inputId, schedules);
                }
                schedules.add(schedule);
            }
        }
        mInitialized = true;
        onSchedulesChanged();
    }

    private void onSchedulesChanged() {
        List<ScheduledRecording> addedConflicts = new ArrayList<>();
        List<ScheduledRecording> removedConflicts = new ArrayList<>();
        for (String inputId : mInputScheduleMap.keySet()) {
            List<ScheduledRecording> oldConflicts = mInputConflictMap.get(inputId);
            Map<Long, ScheduledRecording> oldConflictMap = new HashMap<>();
            if (oldConflicts != null) {
                for (ScheduledRecording r : oldConflicts) {
                    oldConflictMap.put(r.getId(), r);
                }
            }
            List<ScheduledRecording> conflicts = getConflictingSchedules(inputId);
            for (ScheduledRecording r : conflicts) {
                if (oldConflictMap.remove(r.getId()) == null) {
                    addedConflicts.add(r);
                }
            }
            removedConflicts.addAll(oldConflictMap.values());
            if (conflicts.isEmpty()) {
                mInputConflictMap.remove(inputId);
            } else {
                mInputConflictMap.put(inputId, conflicts);
            }
        }
        if (!removedConflicts.isEmpty()) {
            notifyConflictStateChange(false, ScheduledRecording.toArray(removedConflicts));
        }
        if (!addedConflicts.isEmpty()) {
            notifyConflictStateChange(true, ScheduledRecording.toArray(addedConflicts));
        }
    }

    /**
     * Returns {@code true} if this class has been initialized.
     */
    public boolean isInitialized() {
        return mInitialized;
    }

    /**
     * Adds a {@link ScheduledRecordingListener}.
     */
    public final void addScheduledRecordingListener(ScheduledRecordingListener listener) {
        mScheduledRecordingListeners.add(listener);
    }

    /**
     * Removes a {@link ScheduledRecordingListener}.
     */
    public final void removeScheduledRecordingListener(ScheduledRecordingListener listener) {
        mScheduledRecordingListeners.remove(listener);
    }

    /**
     * Calls {@link ScheduledRecordingListener#onScheduledRecordingAdded} for each listener.
     */
    private void notifyScheduledRecordingAdded(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            l.onScheduledRecordingAdded(scheduledRecordings);
        }
    }

    /**
     * Calls {@link ScheduledRecordingListener#onScheduledRecordingRemoved} for each listener.
     */
    private void notifyScheduledRecordingRemoved(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            l.onScheduledRecordingRemoved(scheduledRecordings);
        }
    }

    /**
     * Calls {@link ScheduledRecordingListener#onScheduledRecordingStatusChanged} for each listener.
     */
    private void notifyScheduledRecordingStatusChanged(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecordingListener l : mScheduledRecordingListeners) {
            l.onScheduledRecordingStatusChanged(scheduledRecordings);
        }
    }

    /**
     * Adds a {@link OnConflictStateChangeListener}.
     */
    public final void addOnConflictStateChangeListener(OnConflictStateChangeListener listener) {
        mOnConflictStateChangeListeners.add(listener);
    }

    /**
     * Removes a {@link OnConflictStateChangeListener}.
     */
    public final void removeOnConflictStateChangeListener(OnConflictStateChangeListener listener) {
        mOnConflictStateChangeListeners.remove(listener);
    }

    /**
     * Calls {@link OnConflictStateChangeListener#onConflictStateChange} for each listener.
     */
    private void notifyConflictStateChange(boolean conflict,
            ScheduledRecording... scheduledRecordings) {
        for (OnConflictStateChangeListener l : mOnConflictStateChangeListeners) {
            l.onConflictStateChange(conflict, scheduledRecordings);
        }
    }

    /**
     * Returns the priority for the program if it is recorded.
     * <p>
     * The recording will have the higher priority than the existing ones.
     */
    public long suggestNewPriority() {
        if (!SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet")) {
            return DEFAULT_PRIORITY;
        }
        return suggestHighestPriority();
    }

    private long suggestHighestPriority() {
        long highestPriority = DEFAULT_PRIORITY - PRIORITY_OFFSET;
        for (ScheduledRecording schedule : mDataManager.getAllScheduledRecordings()) {
            if (schedule.getPriority() > highestPriority) {
                highestPriority = schedule.getPriority();
            }
        }
        return highestPriority + PRIORITY_OFFSET;
    }

    /**
     * Returns the priority for a series recording.
     * <p>
     * The recording will have the higher priority than the existing series.
     */
    public long suggestNewSeriesPriority() {
        if (!SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet")) {
            return DEFAULT_SERIES_PRIORITY;
        }
        return suggestHighestSeriesPriority();
    }

    /**
     * Returns the priority for a series recording by order of series recording priority.
     *
     * Higher order will have higher priority.
     */
    public static long suggestSeriesPriority(int order) {
        return DEFAULT_SERIES_PRIORITY + order * PRIORITY_OFFSET;
    }

    private long suggestHighestSeriesPriority() {
        long highestPriority = DEFAULT_SERIES_PRIORITY - PRIORITY_OFFSET;
        for (SeriesRecording schedule : mDataManager.getSeriesRecordings()) {
            if (schedule.getPriority() > highestPriority) {
                highestPriority = schedule.getPriority();
            }
        }
        return highestPriority + PRIORITY_OFFSET;
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * this program is.
     * <p>
     * Any empty list means there is no conflicts.  If there is conflict the program must be
     * scheduled to record with a priority higher than the first recording in the list returned.
     */
    public List<ScheduledRecording> getConflictingSchedules(Program program) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        SoftPreconditions.checkState(Program.isValid(program), TAG,
                "Program is invalid: " + program);
        SoftPreconditions.checkState(
                program.getStartTimeUtcMillis() < program.getEndTimeUtcMillis(), TAG,
                "Program duration is empty: " + program);
        if (!mInitialized || !Program.isValid(program)
                || program.getStartTimeUtcMillis() >= program.getEndTimeUtcMillis()) {
            return Collections.emptyList();
        }
        TvInputInfo input = Utils.getTvInputInfoForProgram(mContext, program);
        if (input == null || !input.canRecord() || input.getTunerCount() <= 0) {
            return Collections.emptyList();
        }
        return getConflictingSchedules(input, Collections.singletonList(
                ScheduledRecording.builder(input.getId(), program)
                        .setPriority(suggestHighestPriority())
                        .build()));
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * this channel is.
     * <p>
     * Any empty list means there is no conflicts.  If there is conflict the channel must be
     * scheduled to record with a priority higher than the first recording in the list returned.
     */
    public List<ScheduledRecording> getConflictingSchedules(long channelId, long startTimeMs,
            long endTimeMs) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        SoftPreconditions.checkState(channelId != Channel.INVALID_ID, TAG, "Invalid channel ID");
        SoftPreconditions.checkState(startTimeMs < endTimeMs, TAG, "Recording duration is empty.");
        if (!mInitialized || channelId == Channel.INVALID_ID || startTimeMs >= endTimeMs) {
            return Collections.emptyList();
        }
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext, channelId);
        if (input == null || !input.canRecord() || input.getTunerCount() <= 0) {
            return Collections.emptyList();
        }
        return getConflictingSchedules(input, Collections.singletonList(
                ScheduledRecording.builder(input.getId(), channelId, startTimeMs, endTimeMs)
                        .setPriority(suggestHighestPriority())
                        .build()));
    }

    /**
     * Returns all the scheduled recordings that conflicts and will not be recorded or clipped for
     * the given input.
     */
    @NonNull
    private List<ScheduledRecording> getConflictingSchedules(String inputId) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        TvInputInfo input = Utils.getTvInputInfoForInputId(mContext, inputId);
        SoftPreconditions.checkState(input != null, TAG, "Can't find input for : " + inputId);
        if (!mInitialized || input == null) {
            return Collections.emptyList();
        }
        List<ScheduledRecording> schedules = mInputScheduleMap.get(input.getId());
        if (schedules == null || schedules.isEmpty()) {
            return Collections.emptyList();
        }
        return getConflictingSchedules(schedules, input.getTunerCount());
    }

    /**
     * Checks if the schedule is conflicting.
     *
     * <p>Note that the {@code schedule} should be the existing one. If not, this returns
     * {@code false}.
     */
    public boolean isConflicting(ScheduledRecording schedule) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext, schedule.getChannelId());
        SoftPreconditions.checkState(input != null, TAG, "Can't find input for channel ID : "
                + schedule.getChannelId());
        if (!mInitialized || input == null) {
            return false;
        }
        List<ScheduledRecording> conflicts = mInputConflictMap.get(input.getId());
        return conflicts != null && conflicts.contains(schedule);
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * this channel is tuned to.
     */
    public List<ScheduledRecording> getConflictingSchedulesForTune(long channelId) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        SoftPreconditions.checkState(channelId != Channel.INVALID_ID, TAG, "Invalid channel ID");
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext, channelId);
        SoftPreconditions.checkState(input != null, TAG, "Can't find input for channel ID: "
                + channelId);
        if (!mInitialized || channelId == Channel.INVALID_ID || input == null) {
            return Collections.emptyList();
        }
        return getConflictingSchedulesForTune(input.getId(), channelId, System.currentTimeMillis(),
                suggestHighestPriority(), getStartedRecordings(input.getId()),
                input.getTunerCount());
    }

    @VisibleForTesting
    public static List<ScheduledRecording> getConflictingSchedulesForTune(String inputId,
            long channelId, long currentTimeMs, long newPriority,
            List<ScheduledRecording> startedRecordings, int tunerCount) {
        boolean channelFound = false;
        for (ScheduledRecording schedule : startedRecordings) {
            if (schedule.getChannelId() == channelId) {
                channelFound = true;
                break;
            }
        }
        List<ScheduledRecording> schedules;
        if (!channelFound) {
            // The current channel is not being recorded.
            schedules = new ArrayList<>(startedRecordings);
            schedules.add(ScheduledRecording
                    .builder(inputId, channelId, currentTimeMs, currentTimeMs + 1)
                    .setPriority(newPriority)
                    .build());
        } else {
            schedules = startedRecordings;
        }
        return getConflictingSchedules(schedules, tunerCount);
    }

    /**
     * Returns priority ordered list of all scheduled recordings that will not be recorded if
     * the user keeps watching this channel.
     * <p>
     * Note that if the user keeps watching the channel, the channel can be recorded.
     */
    public List<ScheduledRecording> getConflictingSchedulesForWatching(long channelId) {
        SoftPreconditions.checkState(mInitialized, TAG, "Not initialized yet");
        SoftPreconditions.checkState(channelId != Channel.INVALID_ID, TAG, "Invalid channel ID");
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext, channelId);
        SoftPreconditions.checkState(input != null, TAG, "Can't find input for channel ID: "
                + channelId);
        if (!mInitialized || channelId == Channel.INVALID_ID || input == null) {
            return Collections.emptyList();
        }
        List<ScheduledRecording> schedules = mInputScheduleMap.get(input.getId());
        if (schedules == null || schedules.isEmpty()) {
            return Collections.emptyList();
        }
        return getConflictingSchedulesForWatching(input.getId(), channelId,
                System.currentTimeMillis(), suggestNewPriority(), schedules, input.getTunerCount());
    }

    private List<ScheduledRecording> getConflictingSchedules(TvInputInfo input,
            List<ScheduledRecording> schedulesToAdd) {
        SoftPreconditions.checkNotNull(input);
        if (input == null || !input.canRecord() || input.getTunerCount() <= 0) {
            return Collections.emptyList();
        }
        List<ScheduledRecording> currentSchedules = mInputScheduleMap.get(input.getId());
        if (currentSchedules == null || currentSchedules.isEmpty()) {
            return Collections.emptyList();
        }
        return getConflictingSchedules(schedulesToAdd, currentSchedules, input.getTunerCount());
    }

    @VisibleForTesting
    static List<ScheduledRecording> getConflictingSchedulesForWatching(String inputId,
            long channelId, long currentTimeMs, long newPriority,
            @NonNull List<ScheduledRecording> schedules, int tunerCount) {
        List<ScheduledRecording> schedulesToCheck = new ArrayList<>(schedules);
        List<ScheduledRecording> schedulesSameChannel = new ArrayList<>();
        for (ScheduledRecording schedule : schedules) {
            if (schedule.getChannelId() == channelId) {
                schedulesSameChannel.add(schedule);
                schedulesToCheck.remove(schedule);
            }
        }
        // Assume that the user will watch the current channel forever.
        schedulesToCheck.add(ScheduledRecording
                .builder(inputId, channelId, currentTimeMs, Long.MAX_VALUE)
                .setPriority(newPriority)
                .build());
        List<ScheduledRecording> result = new ArrayList<>();
        result.addAll(getConflictingSchedules(schedulesSameChannel, 1));
        result.addAll(getConflictingSchedules(schedulesToCheck, tunerCount));
        Collections.sort(result, ScheduledRecording.PRIORITY_COMPARATOR);
        return result;
    }

    @VisibleForTesting
    static List<ScheduledRecording> getConflictingSchedules(List<ScheduledRecording> schedulesToAdd,
            List<ScheduledRecording> currentSchedules, int tunerCount) {
        List<ScheduledRecording> schedulesToCheck = new ArrayList<>(currentSchedules);
        // When the duplicate schedule is to be added, remove the current duplicate recording.
        for (Iterator<ScheduledRecording> iter = schedulesToCheck.iterator(); iter.hasNext(); ) {
            ScheduledRecording schedule = iter.next();
            for (ScheduledRecording toAdd : schedulesToAdd) {
                if (schedule.getType() == ScheduledRecording.TYPE_PROGRAM) {
                    if (toAdd.getProgramId() == schedule.getProgramId()) {
                        iter.remove();
                        break;
                    }
                } else {
                    if (toAdd.getChannelId() == schedule.getChannelId()
                            && toAdd.getStartTimeMs() == schedule.getStartTimeMs()
                            && toAdd.getEndTimeMs() == schedule.getEndTimeMs()) {
                        iter.remove();
                        break;
                    }
                }
            }
        }
        schedulesToCheck.addAll(schedulesToAdd);
        List<Range<Long>> ranges = new ArrayList<>();
        for (ScheduledRecording schedule : schedulesToAdd) {
            ranges.add(new Range<>(schedule.getStartTimeMs(), schedule.getEndTimeMs()));
        }
        return getConflictingSchedules(schedulesToCheck, tunerCount, ranges);
    }

    /**
     * Returns all conflicting scheduled recordings for the given schedules and count of tuner.
     */
    public static List<ScheduledRecording> getConflictingSchedules(
            List<ScheduledRecording> schedules, int tunerCount) {
        return getConflictingSchedules(schedules, tunerCount,
                Collections.singletonList(new Range<>(Long.MIN_VALUE, Long.MAX_VALUE)));
    }

    @VisibleForTesting
    static List<ScheduledRecording> getConflictingSchedules(List<ScheduledRecording> schedules,
            int tunerCount, List<Range<Long>> periods) {
        List<ScheduledRecording> schedulesToCheck = new ArrayList<>();
        // Filter out non-overlapping or empty duration of schedules.
        for (ScheduledRecording schedule : schedules) {
            for (Range<Long> period : periods) {
                if (schedule.isOverLapping(period)
                        && schedule.getStartTimeMs() < schedule.getEndTimeMs()) {
                    schedulesToCheck.add(schedule);
                    break;
                }
            }
        }
        // Sort by the end time.
        // If a.end <= b.end <= c.end and a overlaps with b and c, then b overlaps with c.
        // Likewise, if a1.end <= a2.end <= ... , all the schedules which overlap with a1 overlap
        // with each other.
        Collections.sort(schedulesToCheck, ScheduledRecording.END_TIME_COMPARATOR);
        Set<ScheduledRecording> conflicts = new ArraySet<>();
        List<ScheduledRecording> overlaps = new ArrayList<>();
        for (int i = 0; i < schedulesToCheck.size(); ++i) {
            ScheduledRecording r1 = schedulesToCheck.get(i);
            if (conflicts.contains(r1)) {
                // No need to check r1 because it's a conflicting schedule already.
                continue;
            }
            overlaps.clear();
            overlaps.add(r1);
            // Find schedules which overlap with r1.
            for (int j = i + 1; j < schedulesToCheck.size(); ++j) {
                ScheduledRecording r2 = schedulesToCheck.get(j);
                if (!conflicts.contains(r2) && r1.getEndTimeMs() > r2.getStartTimeMs()) {
                    overlaps.add(r2);
                }
            }
            Collections.sort(overlaps, ScheduledRecording.PRIORITY_COMPARATOR);
            // If there are more than one overlapping schedules for the same channel, only one
            // schedule will be recorded.
            HashSet<Long> channelIds = new HashSet<>();
            for (Iterator<ScheduledRecording> iter = overlaps.iterator(); iter.hasNext(); ) {
                ScheduledRecording schedule = iter.next();
                if (channelIds.contains(schedule.getChannelId())) {
                    conflicts.add(schedule);
                    iter.remove();
                } else {
                    channelIds.add(schedule.getChannelId());
                }
            }
            if (overlaps.size() > tunerCount) {
                conflicts.addAll(overlaps.subList(tunerCount, overlaps.size()));
            }
        }
        List<ScheduledRecording> result = new ArrayList<>(conflicts);
        Collections.sort(result, ScheduledRecording.PRIORITY_COMPARATOR);
        return result;
    }

    /**
     * A listener which is notified the conflict state change of the schedules.
     */
    public interface OnConflictStateChangeListener {
        /**
         * Called when the conflicting schedules change.
         *
         * @param conflict {@code true} if the {@code schedules} are the new conflicts, otherwise
         * {@code false}.
         * @param schedules the schedules
         */
        void onConflictStateChange(boolean conflict, ScheduledRecording... schedules);
    }
}
