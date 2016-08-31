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

package com.android.tv.dvr.ui;

import android.content.Intent;
import android.content.res.Resources;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v17.leanback.widget.Action;
import android.support.v17.leanback.widget.OnActionClickedListener;
import android.support.v17.leanback.widget.SparseArrayObjectAdapter;
import android.text.TextUtils;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.data.Channel;
import com.android.tv.dialog.PinDialogFragment;
import com.android.tv.dvr.DvrManager;
import com.android.tv.dvr.DvrPlaybackActivity;
import com.android.tv.dvr.DvrUiHelper;
import com.android.tv.dvr.DvrWatchedPositionManager;
import com.android.tv.dvr.RecordedProgram;
import com.android.tv.parental.ParentalControlSettings;
import com.android.tv.util.TvInputManagerHelper;
import com.android.tv.util.Utils;

import java.io.File;

/**
 * {@link DetailsFragment} for recorded program in DVR.
 */
public class RecordedProgramDetailsFragment extends DvrDetailsFragment {
    private static final int ACTION_RESUME_PLAYING = 1;
    private static final int ACTION_PLAY_FROM_BEGINNING = 2;
    private static final int ACTION_DELETE_RECORDING = 3;

    private DvrWatchedPositionManager mDvrWatchedPositionManager;
    private TvInputManagerHelper mTvInputManagerHelper;

    private RecordedProgram mRecordedProgram;
    private DetailsContent mDetailsContent;
    private boolean mPaused;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDvrWatchedPositionManager = TvApplication.getSingletons(getActivity())
                .getDvrWatchedPositionManager();
        mTvInputManagerHelper = TvApplication.getSingletons(getActivity())
                .getTvInputManagerHelper();
        setDetailsOverviewRow(mDetailsContent);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPaused) {
            updateActions();
            mPaused = false;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mPaused = true;
    }

    @Override
    protected boolean onLoadRecordingDetails(Bundle args) {
        long recordedProgramId = args.getLong(DvrDetailsActivity.RECORDING_ID);
        mRecordedProgram = TvApplication.getSingletons(getActivity()).getDvrDataManager()
                .getRecordedProgram(recordedProgramId);
        if (mRecordedProgram == null) {
            // notify super class to end activity before initializing anything
            return false;
        }
        mDetailsContent = createDetailsContent();
        return true;
    }

    private DetailsContent createDetailsContent() {
        Channel channel = TvApplication.getSingletons(getContext()).getChannelDataManager()
                .getChannel(mRecordedProgram.getChannelId());
        String description = TextUtils.isEmpty(mRecordedProgram.getLongDescription())
                ? mRecordedProgram.getDescription() : mRecordedProgram.getLongDescription();
        return new DetailsContent.Builder()
                .setTitle(getTitleFromProgram(mRecordedProgram, channel))
                .setStartTimeUtcMillis(mRecordedProgram.getStartTimeUtcMillis())
                .setEndTimeUtcMillis(mRecordedProgram.getEndTimeUtcMillis())
                .setDescription(description)
                .setImageUris(mRecordedProgram, channel)
                .build();
    }

    @Override
    protected SparseArrayObjectAdapter onCreateActionsAdapter() {
        SparseArrayObjectAdapter adapter =
                new SparseArrayObjectAdapter(new ActionPresenterSelector());
        Resources res = getResources();
        if (mDvrWatchedPositionManager.getWatchedPosition(mRecordedProgram.getId())
                != TvInputManager.TIME_SHIFT_INVALID_TIME) {
            adapter.set(ACTION_RESUME_PLAYING, new Action(ACTION_RESUME_PLAYING,
                    res.getString(R.string.dvr_detail_resume_play), null,
                    res.getDrawable(R.drawable.lb_ic_play)));
            adapter.set(ACTION_PLAY_FROM_BEGINNING, new Action(ACTION_PLAY_FROM_BEGINNING,
                    res.getString(R.string.dvr_detail_play_from_beginning), null,
                    res.getDrawable(R.drawable.lb_ic_replay)));
        } else {
            adapter.set(ACTION_PLAY_FROM_BEGINNING, new Action(ACTION_PLAY_FROM_BEGINNING,
                    res.getString(R.string.dvr_detail_watch), null,
                    res.getDrawable(R.drawable.lb_ic_play)));
        }
        adapter.set(ACTION_DELETE_RECORDING, new Action(ACTION_DELETE_RECORDING,
                res.getString(R.string.dvr_detail_delete), null,
                res.getDrawable(R.drawable.ic_delete_32dp)));
        return adapter;
    }

    @Override
    protected OnActionClickedListener onCreateOnActionClickedListener() {
        return new OnActionClickedListener() {
            @Override
            public void onActionClicked(Action action) {
                if (action.getId() == ACTION_PLAY_FROM_BEGINNING) {
                    startPlayback(TvInputManager.TIME_SHIFT_INVALID_TIME);
                } else if (action.getId() == ACTION_RESUME_PLAYING) {
                    startPlayback(mDvrWatchedPositionManager
                            .getWatchedPosition(mRecordedProgram.getId()));
                } else if (action.getId() == ACTION_DELETE_RECORDING) {
                    DvrManager dvrManager = TvApplication
                            .getSingletons(getActivity()).getDvrManager();
                    dvrManager.removeRecordedProgram(mRecordedProgram);
                    getActivity().finish();
                }
            }
        };
    }

    private boolean isDataUriAccessible(Uri dataUri) {
        if (dataUri == null || dataUri.getPath() == null) {
            return false;
        }
        try {
            File recordedProgramPath = new File(dataUri.getPath());
            if (recordedProgramPath.exists()) {
                return true;
            }
        } catch (SecurityException e) {
        }
        return false;
    }

    private void startPlayback(long seekTimeMs) {
        if (Utils.isInBundledPackageSet(mRecordedProgram.getPackageName())
                && !isDataUriAccessible(mRecordedProgram.getDataUri())) {
            // Currently missing storage is handled only for TunerTvInput.
            DvrUiHelper.showDvrMissingStorageErrorDialog(getActivity(),
                    mRecordedProgram.getInputId());
            return;
        }
        ParentalControlSettings parental = mTvInputManagerHelper.getParentalControlSettings();
        if (!parental.isParentalControlsEnabled()) {
            launchPlaybackActivity(seekTimeMs, false);
            return;
        }
        String ratingString = mRecordedProgram.getContentRating();
        if (TextUtils.isEmpty(ratingString)) {
            launchPlaybackActivity(seekTimeMs, false);
            return;
        }
        String[] ratingList = ratingString.split(",");
        TvContentRating[] programRatings = new TvContentRating[ratingList.length];
        for (int i = 0; i < ratingList.length; i++) {
            programRatings[i] = TvContentRating.unflattenFromString(ratingList[i]);
        }
        TvContentRating blockRatings = parental.getBlockedRating(programRatings);
        if (blockRatings != null) {
            new PinDialogFragment(PinDialogFragment.PIN_DIALOG_TYPE_UNLOCK_PROGRAM,
                    new PinDialogFragment.ResultListener() {
                        @Override
                        public void done(boolean success) {
                            if (success) {
                                launchPlaybackActivity(seekTimeMs, true);
                            }
                        }
                    }).show(getActivity().getFragmentManager(), PinDialogFragment.DIALOG_TAG);
        } else {
            launchPlaybackActivity(seekTimeMs, false);
        }
    }

    private void launchPlaybackActivity(long seekTimeMs, boolean pinChecked) {
        Intent intent = new Intent(getActivity(), DvrPlaybackActivity.class);
        intent.putExtra(Utils.EXTRA_KEY_RECORDED_PROGRAM_ID, mRecordedProgram.getId());
        if (seekTimeMs != TvInputManager.TIME_SHIFT_INVALID_TIME) {
            intent.putExtra(Utils.EXTRA_KEY_RECORDED_PROGRAM_SEEK_TIME, seekTimeMs);
        }
        intent.putExtra(Utils.EXTRA_KEY_RECORDED_PROGRAM_PIN_CHECKED, pinChecked);
        getActivity().startActivity(intent);
    }
}
