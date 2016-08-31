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

package com.android.tv.dvr.ui;

import android.app.FragmentManager;
import android.content.Context;
import android.os.Bundle;
import android.support.v17.leanback.app.GuidedStepFragment;
import android.support.v17.leanback.widget.GuidanceStylist.Guidance;
import android.support.v17.leanback.widget.GuidedAction;
import android.support.v17.leanback.widget.GuidedActionsStylist;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.data.Channel;
import com.android.tv.dvr.DvrDataManager;
import com.android.tv.dvr.SeriesRecording;
import com.android.tv.dvr.SeriesRecording.ChannelOption;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment for DVR series recording settings.
 */
public class SeriesSettingsFragment extends GuidedStepFragment
        implements DvrDataManager.SeriesRecordingListener {
    /**
     * Name of series recording id added to the bundle.
     * Type: Long
     */
    public static final String SERIES_RECORDING_ID = "series_recording_id";

    private static final long ACTION_ID_PRIORITY = 10;
    private static final long ACTION_ID_CHANNEL = 11;

    private static final long SUB_ACTION_ID_CHANNEL_ONE = 101;
    private static final long SUB_ACTION_ID_CHANNEL_ALL = 102;

    private DvrDataManager mDvrDataManager;
    private SeriesRecording mSeriesRecording;
    private Channel mChannel;
    private long mSeriesRecordingId;
    @ChannelOption int mChannelOption;

    private String mFragmentTitle;
    private String mProrityActionTitle;
    private String mProrityActionHighestText;
    private String mProrityActionLowestText;
    private String mChannelsActionTitle;
    private String mChannelsActionAllText;

    private GuidedAction mPriorityGuidedAction;
    private GuidedAction mChannelsGuidedAction;

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        mDvrDataManager = TvApplication.getSingletons(context).getDvrDataManager();
        mSeriesRecordingId = getArguments().getLong(SERIES_RECORDING_ID);
        mSeriesRecording = mDvrDataManager.getSeriesRecording(mSeriesRecordingId);
        mDvrDataManager.addSeriesRecordingListener(this);
        mChannelOption = mSeriesRecording.getChannelOption();
        mChannel = TvApplication.getSingletons(context).getChannelDataManager()
                .getChannel(mSeriesRecording.getChannelId());
        // TODO: Handle when channel is null.
        mFragmentTitle = getString(R.string.dvr_series_settings_title);
        mProrityActionTitle = getString(R.string.dvr_series_settings_priority);
        mProrityActionHighestText = getString(R.string.dvr_series_settings_priority_highest);
        mProrityActionLowestText = getString(R.string.dvr_series_settings_priority_lowest);
        mChannelsActionTitle = getString(R.string.dvr_series_settings_channels);
        mChannelsActionAllText = getString(R.string.dvr_series_settings_channels_all);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mDvrDataManager.removeSeriesRecordingListener(this);
    }

    @Override
    public Guidance onCreateGuidance(Bundle savedInstanceState) {
        String breadcrumb = mSeriesRecording.getTitle();
        String title = mFragmentTitle;
        return new Guidance(title, null, breadcrumb, null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        mPriorityGuidedAction = new GuidedAction.Builder(getActivity())
                .id(ACTION_ID_PRIORITY)
                .title(mProrityActionTitle)
                .build();
        updatePriorityGuidedAction(false);
        actions.add(mPriorityGuidedAction);

        List<GuidedAction> channelSubActions = new ArrayList<GuidedAction>();
        channelSubActions.add(new GuidedAction.Builder(getActivity())
                .id(SUB_ACTION_ID_CHANNEL_ONE)
                .title(mChannel.getDisplayText())
                .build());
        channelSubActions.add(new GuidedAction.Builder(getActivity())
                .id(SUB_ACTION_ID_CHANNEL_ALL)
                .title(mChannelsActionAllText)
                .build());
        mChannelsGuidedAction = new GuidedAction.Builder(getActivity())
                .id(ACTION_ID_CHANNEL)
                .title(mChannelsActionTitle)
                .subActions(channelSubActions)
                .build();
        actions.add(mChannelsGuidedAction);
        updateChannelsGuidedAction(false);
    }

    @Override
    public void onCreateButtonActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(getActivity())
                .clickAction(GuidedAction.ACTION_ID_OK)
                .build());
        actions.add(new GuidedAction.Builder(getActivity())
                .clickAction(GuidedAction.ACTION_ID_CANCEL)
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        long actionId = action.getId();
        if (actionId == GuidedAction.ACTION_ID_OK) {
            if (mChannelOption != mSeriesRecording.getChannelOption()) {
                TvApplication.getSingletons(getContext()).getDvrManager()
                        .updateSeriesRecording(SeriesRecording.buildFrom(mSeriesRecording)
                                .setChannelOption(mChannelOption)
                                .build());
            }
            finishGuidedStepFragments();
        } else if (actionId == GuidedAction.ACTION_ID_CANCEL) {
            finishGuidedStepFragments();
        } else if (actionId == ACTION_ID_PRIORITY) {
            FragmentManager fragmentManager = getFragmentManager();
            PrioritySettingsFragment fragment = new PrioritySettingsFragment();
            Bundle args = new Bundle();
            args.putLong(PrioritySettingsFragment.COME_FROM_SERIES_RECORDING_ID,
                    mSeriesRecording.getId());
            fragment.setArguments(args);
            GuidedStepFragment.add(fragmentManager, fragment, R.id.dvr_settings_view_frame);
        }
    }

    @Override
    public boolean onSubGuidedActionClicked(GuidedAction action) {
        long actionId = action.getId();
        if (actionId == SUB_ACTION_ID_CHANNEL_ALL) {
            mChannelOption = SeriesRecording.OPTION_CHANNEL_ALL;
            updateChannelsGuidedAction(true);
            return true;
        } else if (actionId == SUB_ACTION_ID_CHANNEL_ONE) {
            mChannelOption = SeriesRecording.OPTION_CHANNEL_ONE;
            updateChannelsGuidedAction(true);
            return true;
        }
        return false;
    }

    @Override
    public GuidedActionsStylist onCreateButtonActionsStylist() {
        return new DvrGuidedActionsStylist(true);
    }

    private void updateChannelsGuidedAction(boolean notifyActionChanged) {
        if (mChannelOption == SeriesRecording.OPTION_CHANNEL_ALL) {
            mChannelsGuidedAction.setDescription(mChannelsActionAllText);
        } else {
            mChannelsGuidedAction.setDescription(mChannel.getDisplayText());
        }
        if (notifyActionChanged) {
            notifyActionChanged(findActionPositionById(ACTION_ID_CHANNEL));
        }
    }

    private void updatePriorityGuidedAction(boolean notifyActionChanged) {
        int totalSeriesCount = 0;
        int priorityOrder = 0;
        for (SeriesRecording seriesRecording : mDvrDataManager.getSeriesRecordings()) {
            if (seriesRecording.getState() == SeriesRecording.STATE_SERIES_NORMAL
                    || seriesRecording.getId() == mSeriesRecording.getId()) {
                ++totalSeriesCount;
            }
            if (seriesRecording.getState() == SeriesRecording.STATE_SERIES_NORMAL
                    && seriesRecording.getId() != mSeriesRecording.getId()
                    && seriesRecording.getPriority() > mSeriesRecording.getPriority()) {
                ++priorityOrder;
            }
        }
        if (priorityOrder == 0) {
            mPriorityGuidedAction.setDescription(mProrityActionHighestText);
        } else if (priorityOrder >= totalSeriesCount - 1) {
            mPriorityGuidedAction.setDescription(mProrityActionLowestText);
        } else {
            mPriorityGuidedAction.setDescription(Integer.toString(priorityOrder + 1));
        }
        if (notifyActionChanged) {
            notifyActionChanged(findActionPositionById(ACTION_ID_PRIORITY));
        }
    }

    @Override
    public void onSeriesRecordingAdded(SeriesRecording... seriesRecordings) { }

    @Override
    public void onSeriesRecordingRemoved(SeriesRecording... seriesRecordings) { }

    @Override
    public void onSeriesRecordingChanged(SeriesRecording... seriesRecordings) {
        for (SeriesRecording seriesRecording : seriesRecordings) {
            if (seriesRecording.getId() == mSeriesRecordingId) {
                mSeriesRecording = seriesRecording;
                updatePriorityGuidedAction(true);
                return;
            }
        }
    }
}
