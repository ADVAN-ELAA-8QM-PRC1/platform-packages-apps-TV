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

package com.android.tv.menu;

import android.content.Context;
import android.support.annotation.Nullable;

import com.android.tv.ChannelTuner;
import com.android.tv.TvApplication;
import com.android.tv.common.feature.CommonFeatures;
import com.android.tv.dvr.RecordedProgram;
import com.android.tv.data.Channel;
import com.android.tv.dvr.DvrDataManager;
import com.android.tv.dvr.DvrDataManager.OnDvrScheduleLoadFinishedListener;
import com.android.tv.dvr.DvrDataManager.OnRecordedProgramLoadFinishedListener;
import com.android.tv.dvr.DvrDataManager.RecordedProgramListener;
import com.android.tv.dvr.DvrDataManager.ScheduledRecordingListener;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.ui.TunableTvView;
import com.android.tv.ui.TunableTvView.OnScreenBlockingChangedListener;

/**
 * Update menu items when needed.
 *
 * <p>As the menu is updated when it shows up, this class handles only the dynamic updates.
 */
public class MenuUpdater {
    // Can be null for testing.
    @Nullable
    private final TunableTvView mTvView;
    private final Menu mMenu;
    @Nullable
    private final DvrDataManager mDvrDataManager;
    private ChannelTuner mChannelTuner;

    private final OnScreenBlockingChangedListener mOnScreenBlockingChangeListener =
            new OnScreenBlockingChangedListener() {
                @Override
                public void onScreenBlockingChanged(boolean blocked) {
                    mMenu.update(PlayControlsRow.ID);
                }
            };
    private final OnRecordedProgramLoadFinishedListener mRecordedProgramLoadedListener =
            new OnRecordedProgramLoadFinishedListener() {
                @Override
                public void onRecordedProgramLoadFinished() {
                    mMenu.update(ChannelsRow.ID);
                }
            };
    private final RecordedProgramListener mRecordedProgramListener =
            new RecordedProgramListener() {
                @Override
                public void onRecordedProgramAdded(RecordedProgram recordedProgram) {
                    mMenu.update(ChannelsRow.ID);
                }

                @Override
                public void onRecordedProgramChanged(RecordedProgram recordedProgram) { }

                @Override
                public void onRecordedProgramRemoved(RecordedProgram recordedProgram) {
                    if (mDvrDataManager != null && mDvrDataManager.getRecordedPrograms().isEmpty()
                            && mDvrDataManager.getStartedRecordings().isEmpty()
                            && mDvrDataManager.getNonStartedScheduledRecordings().isEmpty()
                            && mDvrDataManager.getSeriesRecordings().isEmpty()) {
                        mMenu.update(ChannelsRow.ID);
                    }
                }
            };
    private final OnDvrScheduleLoadFinishedListener mDvrScheduleLoadedListener =
            new OnDvrScheduleLoadFinishedListener() {
                @Override
                public void onDvrScheduleLoadFinished() {
                    mMenu.update(ChannelsRow.ID);
                }
            };
    private final ScheduledRecordingListener mScheduledRecordingListener =
            new ScheduledRecordingListener() {
                @Override
                public void onScheduledRecordingAdded(ScheduledRecording... scheduledRecordings) {
                    mMenu.update(ChannelsRow.ID);
                }

                @Override
                public void onScheduledRecordingRemoved(ScheduledRecording... scheduledRecordings) {
                    if (mDvrDataManager != null && mDvrDataManager.getRecordedPrograms().isEmpty()
                            && mDvrDataManager.getStartedRecordings().isEmpty()
                            && mDvrDataManager.getNonStartedScheduledRecordings().isEmpty()
                            && mDvrDataManager.getSeriesRecordings().isEmpty()) {
                        mMenu.update(ChannelsRow.ID);
                    }
                }

                @Override
                public void onScheduledRecordingStatusChanged(ScheduledRecording... schedules) { }
    };

    private final ChannelTuner.Listener mChannelTunerListener = new ChannelTuner.Listener() {
        @Override
        public void onLoadFinished() {}

        @Override
        public void onBrowsableChannelListChanged() {
            mMenu.update();
        }

        @Override
        public void onCurrentChannelUnavailable(Channel channel) {}

        @Override
        public void onChannelChanged(Channel previousChannel, Channel currentChannel) {
            mMenu.update(ChannelsRow.ID);
        }
    };

    public MenuUpdater(Context context, TunableTvView tvView, Menu menu) {
        mTvView = tvView;
        mMenu = menu;
        if (mTvView != null) {
            mTvView.setOnScreenBlockedListener(mOnScreenBlockingChangeListener);
        }
        if (CommonFeatures.DVR.isEnabled(context)) {
            mDvrDataManager = TvApplication.getSingletons(context).getDvrDataManager();
            mDvrDataManager.addDvrScheduleLoadFinishedListener(mDvrScheduleLoadedListener);
            mDvrDataManager.addScheduledRecordingListener(mScheduledRecordingListener);
            mDvrDataManager.addRecordedProgramLoadFinishedListener(mRecordedProgramLoadedListener);
            mDvrDataManager.addRecordedProgramListener(mRecordedProgramListener);
        } else {
            mDvrDataManager = null;
        }
    }

    /**
     * Sets the instance of {@link ChannelTuner}. Call this method when the channel tuner is ready
     * or not available any more.
     */
    public void setChannelTuner(ChannelTuner channelTuner) {
        if (mChannelTuner != null) {
            mChannelTuner.removeListener(mChannelTunerListener);
        }
        mChannelTuner = channelTuner;
        if (mChannelTuner != null) {
            mChannelTuner.addListener(mChannelTunerListener);
        }
        mMenu.update();
    }

    /**
     * Called at the end of the menu's lifetime.
     */
    public void release() {
        if (mDvrDataManager != null) {
            mDvrDataManager.removeScheduledRecordingListener(mScheduledRecordingListener);
            mDvrDataManager.removeRecordedProgramListener(mRecordedProgramListener);
            mDvrDataManager.removeDvrScheduleLoadFinishedListener(mDvrScheduleLoadedListener);
            mDvrDataManager
                    .removeRecordedProgramLoadFinishedListener(mRecordedProgramLoadedListener);
        }
        if (mChannelTuner != null) {
            mChannelTuner.removeListener(mChannelTunerListener);
        }
        if (mTvView != null) {
            mTvView.setOnScreenBlockedListener(null);
        }
    }
}
