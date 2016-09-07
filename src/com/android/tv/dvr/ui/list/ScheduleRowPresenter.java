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

package com.android.tv.dvr.ui.list;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.tv.TvInputInfo;
import android.support.v17.leanback.widget.RowPresenter;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Range;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.data.Channel;
import com.android.tv.dvr.DvrScheduleManager;
import com.android.tv.dvr.DvrUiHelper;
import com.android.tv.dvr.ScheduledRecording;
import com.android.tv.util.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A RowPresenter for {@link ScheduleRow}.
 */
public class ScheduleRowPresenter extends RowPresenter {
    private Context mContext;
    private Set<ScheduleRowClickListener> mListeners = new ArraySet<>();
    private final Drawable mBeingRecordedDrawable;

    private final Map<String, HashMap<Long, ScheduledRecording>> mInputScheduleMap = new
            HashMap<>();
    private final List<ScheduledRecording> mConflicts = new ArrayList<>();
    // TODO: Handle input schedule map and conflicts info in the adapter.

    private final Drawable mOnAirDrawable;
    private final Drawable mCancelDrawable;
    private final Drawable mScheduleDrawable;

    private final String mTunerConflictWillNotBeRecordedInfo;
    private final String mTunerConflictWillBePartiallyRecordedInfo;
    private final String mInfoSeparator;

    /**
     * A ViewHolder for {@link ScheduleRow}
     */
    public static class ScheduleRowViewHolder extends RowPresenter.ViewHolder {
        private boolean mLtr;
        private LinearLayout mInfoContainer;
        private RelativeLayout mScheduleActionContainer;
        private RelativeLayout mDeleteActionContainer;
        private View mSelectorView;
        private TextView mTimeView;
        private TextView mProgramTitleView;
        private TextView mInfoSeparatorView;
        private TextView mChannelNameView;
        private TextView mConflictInfoView;
        private ImageView mScheduleActionView;
        private ImageView mDeleteActionView;

        private ScheduledRecording mRecording;

        public ScheduleRowViewHolder(View view) {
            super(view);
            mLtr = view.getContext().getResources().getConfiguration().getLayoutDirection()
                    == View.LAYOUT_DIRECTION_LTR;
            mInfoContainer = (LinearLayout) view.findViewById(R.id.info_container);
            mScheduleActionContainer = (RelativeLayout) view.findViewById(
                    R.id.action_schedule_container);
            mScheduleActionView = (ImageView) view.findViewById(R.id.action_schedule);
            mDeleteActionContainer = (RelativeLayout) view.findViewById(
                    R.id.action_delete_container);
            mDeleteActionView = (ImageView) view.findViewById(R.id.action_delete);
            mSelectorView = view.findViewById(R.id.selector);
            mTimeView = (TextView) view.findViewById(R.id.time);
            mProgramTitleView = (TextView) view.findViewById(R.id.program_title);
            mInfoSeparatorView = (TextView) view.findViewById(R.id.info_separator);
            mChannelNameView = (TextView) view.findViewById(R.id.channel_name);
            mConflictInfoView = (TextView) view.findViewById(R.id.conflict_info);

            mInfoContainer.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean focused) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            updateSelector();
                        }
                    });
                }
            });

            mDeleteActionContainer.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean focused) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            updateSelector();
                        }
                    });
                }
            });

            mScheduleActionContainer.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean focused) {
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            updateSelector();
                        }
                    });
                }
            });
        }

        /**
         * Sets scheduled recording.
         */
        public void setRecording(ScheduledRecording recording) {
            mRecording = recording;
        }

        /**
         * Returns Info container.
         */
        public LinearLayout getInfoContainer() {
            return mInfoContainer;
        }

        /**
         * Returns schedule action container.
         */
        public RelativeLayout getScheduleActionContainer() {
            return mScheduleActionContainer;
        }

        /**
         * Returns delete action container.
         */
        public RelativeLayout getDeleteActionContainer() {
            return mDeleteActionContainer;
        }

        /**
         * Returns time view.
         */
        public TextView getTimeView() {
            return mTimeView;
        }

        /**
         * Returns title view.
         */
        public TextView getProgramTitleView() {
            return mProgramTitleView;
        }

        /**
         * Returns subtitle view.
         */
        public TextView getChannelNameView() {
            return mChannelNameView;
        }

        /**
         * Returns conflict information view.
         */
        public TextView getConflictInfoView() {
            return mConflictInfoView;
        }

        /**
         * Returns schedule action view.
         */
        public ImageView getScheduleActionView() {
            return mScheduleActionView;
        }

        /**
         * Returns delete action view.
         */
        public ImageView getDeleteActionView() {
            return mDeleteActionView;
        }

        /**
         * Returns scheduled recording.
         */
        public ScheduledRecording getRecording() {
            return mRecording;
        }

        private void updateSelector() {
            // TODO: Support RTL language
            int animationDuration = mSelectorView.getResources().getInteger(
                    android.R.integer.config_shortAnimTime);
            DecelerateInterpolator interpolator = new DecelerateInterpolator();
            int roundRectRadius = view.getResources().getDimensionPixelSize(
                    R.dimen.dvr_schedules_selector_radius);

            if (mInfoContainer.isFocused() || mScheduleActionContainer.isFocused()
                    || mDeleteActionContainer.isFocused()) {
                final ViewGroup.LayoutParams lp = mSelectorView.getLayoutParams();
                final int targetWidth;
                if (mInfoContainer.isFocused()) {
                    if (mScheduleActionContainer.getVisibility() == View.GONE
                            && mDeleteActionContainer.getVisibility() == View.GONE) {
                        targetWidth = mInfoContainer.getWidth() + 2 * roundRectRadius;
                    } else {
                        targetWidth = mInfoContainer.getWidth() + roundRectRadius;
                    }
                } else if (mScheduleActionContainer.isFocused()) {
                    if (mScheduleActionContainer.getWidth() > 2 * roundRectRadius) {
                        targetWidth = mScheduleActionContainer.getWidth();
                    } else {
                        targetWidth = 2 * roundRectRadius;
                    }
                } else {
                    targetWidth = mDeleteActionContainer.getWidth() + roundRectRadius;
                }

                float targetTranslationX;
                if (mInfoContainer.isFocused()) {
                    targetTranslationX = mLtr ? mInfoContainer.getLeft() - roundRectRadius
                            - mSelectorView.getLeft() :
                            mInfoContainer.getRight() + roundRectRadius - mInfoContainer.getRight();
                } else if (mScheduleActionContainer.isFocused()) {
                    if (mScheduleActionContainer.getWidth() > 2 * roundRectRadius) {
                        targetTranslationX = mLtr ? mScheduleActionContainer.getLeft() -
                                mSelectorView.getLeft()
                                : mScheduleActionContainer.getRight() - mSelectorView.getRight();
                    } else {
                        targetTranslationX = mLtr ? mScheduleActionContainer.getLeft() -
                                (roundRectRadius - mScheduleActionContainer.getWidth() / 2) -
                                mSelectorView.getLeft()
                                : mScheduleActionContainer.getRight() +
                                (roundRectRadius - mScheduleActionContainer.getWidth() / 2) -
                                mSelectorView.getRight();
                    }
                } else {
                    targetTranslationX = mLtr ? mDeleteActionContainer.getLeft()
                            - mSelectorView.getLeft()
                            : mDeleteActionContainer.getRight() - mSelectorView.getRight();
                }

                if (mSelectorView.getAlpha() == 0) {
                    mSelectorView.setTranslationX(targetTranslationX);
                    lp.width = targetWidth;
                    mSelectorView.requestLayout();
                }

                // animate the selector in and to the proper width and translation X.
                final float deltaWidth = lp.width - targetWidth;
                mSelectorView.animate().cancel();
                mSelectorView.animate().translationX(targetTranslationX).alpha(1f)
                        .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                            @Override
                            public void onAnimationUpdate(ValueAnimator animation) {
                                // Set width to the proper width for this animation step.
                                lp.width = targetWidth + Math.round(
                                        deltaWidth * (1f - animation.getAnimatedFraction()));
                                mSelectorView.requestLayout();
                            }
                        }).setDuration(animationDuration).setInterpolator(interpolator).start();
            } else {
                mSelectorView.animate().cancel();
                mSelectorView.animate().alpha(0f).setDuration(animationDuration)
                        .setInterpolator(interpolator).start();
            }
        }

        /**
         * Grey out the information body.
         */
        public void greyOutInfo() {
            mTimeView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info_grey, null));
            mProgramTitleView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info_grey, null));
            mInfoSeparatorView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info_grey, null));
            mChannelNameView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info_grey, null));
            mConflictInfoView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info_grey, null));
        }

        /**
         * Reverse grey out operation.
         */
        public void whiteBackInfo() {
            mTimeView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info, null));
            mProgramTitleView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_main, null));
            mInfoSeparatorView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info, null));
            mChannelNameView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info, null));
            mConflictInfoView.setTextColor(mInfoContainer.getResources().getColor(R.color
                    .dvr_schedules_item_info, null));
        }
    }

    public ScheduleRowPresenter(Context context) {
        setHeaderPresenter(null);
        setSelectEffectEnabled(false);
        mContext = context;
        mBeingRecordedDrawable = mContext.getDrawable(R.drawable.ic_record_stop);
        mOnAirDrawable = mContext.getDrawable(R.drawable.ic_record_start);
        mCancelDrawable = mContext.getDrawable(R.drawable.ic_dvr_cancel);
        mScheduleDrawable = mContext.getDrawable(R.drawable.ic_scheduled_recording);
        mTunerConflictWillNotBeRecordedInfo = mContext.getString(
                R.string.dvr_schedules_tuner_conflict_will_not_be_recorded_info);
        mTunerConflictWillBePartiallyRecordedInfo = mContext.getString(
                R.string.dvr_schedules_tuner_conflict_will_be_partially_recorded);
        mInfoSeparator = mContext.getString(R.string.dvr_schedules_information_separator);
        updateInputScheduleMap();
    }

    @Override
    public ViewHolder createRowViewHolder(ViewGroup parent) {
        View view = LayoutInflater.from(mContext).inflate(R.layout.dvr_schedules_item,
                parent, false);
        return onGetScheduleRowViewHolder(view);
    }

    /**
     * Returns context.
     */
    protected Context getContext() {
        return mContext;
    }

    /**
     * Returns be recorded drawable which is for being recorded scheduled recordings.
     */
    protected Drawable getBeingRecordedDrawable() {
        return mBeingRecordedDrawable;
    }

    /**
     * Returns on air drawable which is for on air but not being recorded scheduled recordings.
     */
    protected Drawable getOnAirDrawable() {
        return mOnAirDrawable;
    }

    /**
     * Returns cancel drawable which is for cancelling scheduled recording.
     */
    protected Drawable getCancelDrawable() {
        return mCancelDrawable;
    }

    /**
     * Returns schedule drawable which is for scheduling.
     */
    protected Drawable getScheduleDrawable() {
        return mScheduleDrawable;
    }

    /**
     * Returns conflicting scheduled recordings.
     */
    protected List<ScheduledRecording> getConflicts() {
        return mConflicts;
    }

    @Override
    protected void onBindRowViewHolder(RowPresenter.ViewHolder vh, Object item) {
        super.onBindRowViewHolder(vh, item);
        ScheduleRowViewHolder viewHolder = (ScheduleRowViewHolder) vh;
        ScheduleRow scheduleRow = (ScheduleRow) item;
        ScheduledRecording recording = scheduleRow.getRecording();
        // TODO: Do not show separator in the first row.
        viewHolder.mInfoContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onInfoClicked(scheduleRow);
            }
        });

        viewHolder.mDeleteActionContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onDeleteClicked(scheduleRow, viewHolder);
            }
        });

        viewHolder.mScheduleActionContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onScheduleClicked(scheduleRow);
            }
        });

        viewHolder.mTimeView.setText(onGetRecordingTimeText(recording));
        Channel channel = TvApplication.getSingletons(mContext).getChannelDataManager()
                .getChannel(recording.getChannelId());
        String programInfoText = onGetProgramInfoText(recording);
        if (TextUtils.isEmpty(programInfoText)) {
            int durationMins =
                    Math.max((int) TimeUnit.MILLISECONDS.toMinutes(recording.getDuration()), 1);
            programInfoText = mContext.getResources().getQuantityString(
                    R.plurals.dvr_schedules_recording_duration, durationMins, durationMins);
        }
        String channelName = channel != null ? channel.getDisplayName() : null;
        viewHolder.mProgramTitleView.setText(programInfoText);
        viewHolder.mInfoSeparatorView.setVisibility((!TextUtils.isEmpty(programInfoText)
                && !TextUtils.isEmpty(channelName)) ? View.VISIBLE : View.GONE);
        viewHolder.mChannelNameView.setText(channelName);
        if (!scheduleRow.isRemoveScheduleChecked()) {
            if (recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                viewHolder.mDeleteActionView.setImageDrawable(mBeingRecordedDrawable);
            } else {
                viewHolder.mDeleteActionView.setImageDrawable(mCancelDrawable);
            }
        } else {
            if (recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                viewHolder.mDeleteActionView.setImageDrawable(mOnAirDrawable);
            } else {
                viewHolder.mDeleteActionView.setImageDrawable(mScheduleDrawable);
            }
            viewHolder.mProgramTitleView.setTextColor(
                    mContext.getResources().getColor(R.color.dvr_schedules_item_info, null));
        }
        viewHolder.mRecording = recording;
        onBindRowViewHolderInternal(viewHolder, scheduleRow);
    }

    /**
     * Returns view holder for schedule row.
     */
    protected ScheduleRowViewHolder onGetScheduleRowViewHolder(View view) {
        return new ScheduleRowViewHolder(view);
    }

    /**
     * Returns time text for time view from scheduled recording.
     */
    protected String onGetRecordingTimeText(ScheduledRecording recording) {
        return Utils.getDurationString(mContext, recording.getStartTimeMs(),
                recording.getEndTimeMs(), true, false, true, 0);
    }

    /**
     * Returns program info text for program title view.
     */
    protected String onGetProgramInfoText(ScheduledRecording recording) {
        if (recording != null) {
            return recording.getProgramTitle();
        }
        return null;
    }

    /**
     * Internal method for onBindRowViewHolder, can be customized by subclass.
     */
    protected void onBindRowViewHolderInternal(ScheduleRowViewHolder viewHolder, ScheduleRow
            scheduleRow) {
        if (mConflicts.contains(scheduleRow.getRecording())) {
            viewHolder.mScheduleActionView.setImageDrawable(mScheduleDrawable);
            String conflictInfo = mTunerConflictWillNotBeRecordedInfo;
            // TODO: It's also possible for the NonStarted schedules to be partially recorded.
            if (viewHolder.mRecording.getState()
                    == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                conflictInfo = mTunerConflictWillBePartiallyRecordedInfo;
            }
            viewHolder.mConflictInfoView.setText(conflictInfo);
            // TODO: Add 12dp warning icon to conflict info.
            viewHolder.mConflictInfoView.setVisibility(View.VISIBLE);
            viewHolder.greyOutInfo();
        } else {
            viewHolder.mScheduleActionContainer.setVisibility(View.GONE);
            viewHolder.mConflictInfoView.setVisibility(View.GONE);
            if (!scheduleRow.isRemoveScheduleChecked()) {
                viewHolder.whiteBackInfo();
            }
        }
    }

    /**
     * Updates input schedule map.
     */
    private void updateInputScheduleMap() {
        mInputScheduleMap.clear();
        List<ScheduledRecording> allRecordings = TvApplication.getSingletons(getContext())
                .getDvrDataManager().getAvailableScheduledRecordings();
        for(ScheduledRecording recording : allRecordings) {
            addScheduledRecordingToMap(recording);
        }
        updateConflicts();
    }

    /**
     * Updates conflicting scheduled recordings.
     */
    private void updateConflicts() {
        mConflicts.clear();
        for (String inputId : mInputScheduleMap.keySet()) {
            TvInputInfo input = Utils.getTvInputInfoForInputId(mContext, inputId);
            if (input == null) {
                continue;
            }
            mConflicts.addAll(DvrScheduleManager.getConflictingSchedules(
                    new ArrayList<>(mInputScheduleMap.get(inputId).values()),
                    input.getTunerCount()));
        }
    }

    /**
     * Adds a scheduled recording to the map, it happens when user undo cancel.
     */
    private void addScheduledRecordingToMap(ScheduledRecording recording) {
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext,
                recording.getChannelId());
        if (input == null) {
            return;
        }
        String inputId = input.getId();
        HashMap<Long, ScheduledRecording> schedulesMap = mInputScheduleMap.get(inputId);
        if (schedulesMap == null) {
            schedulesMap = new HashMap<>();
            mInputScheduleMap.put(inputId, schedulesMap);
        }
        schedulesMap.put(recording.getId(), recording);
    }

    /**
     * Called when a scheduled recording is added into dvr date manager.
     */
    public void onScheduledRecordingAdded(ScheduledRecording recording) {
        if (recording.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED || recording
                .getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
            addScheduledRecordingToMap(recording);
            updateConflicts();
        }
    }

    /**
     * Adds a scheduled recording to the map, it happens when user undo cancel.
     */
    private void updateScheduledRecordingToMap(ScheduledRecording recording) {
        if (recording.getState() == ScheduledRecording.STATE_RECORDING_NOT_STARTED ||
                recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
            TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext,
                    recording.getChannelId());
            if (input == null) {
                return;
            }
            String inputId = input.getId();
            HashMap<Long, ScheduledRecording> schedulesMap = mInputScheduleMap.get(inputId);
            if (schedulesMap == null) {
                addScheduledRecordingToMap(recording);
                return;
            }
            schedulesMap.put(recording.getId(), recording);
        } else {
            removeScheduledRecordingFromMap(recording);
        }
    }

    /**
     * Called when a scheduled recording is updated in dvr date manager.
     */
    public void onScheduledRecordingUpdated(ScheduledRecording recording) {
        updateScheduledRecordingToMap(recording);
        updateConflicts();
    }

    /**
     * Removes a scheduled recording from the map, it happens when user cancel schedule.
     */
    private void removeScheduledRecordingFromMap(ScheduledRecording recording) {
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext, recording.getChannelId());
        if (input == null) {
            return;
        }
        String inputId = input.getId();
        HashMap<Long, ScheduledRecording> schedulesMap = mInputScheduleMap.get(inputId);
        if (schedulesMap == null) {
            return;
        }
        schedulesMap.remove(recording.getId());
        if (schedulesMap.isEmpty()) {
            mInputScheduleMap.remove(inputId);
        }
    }

    /**
     * Called when a scheduled recording is removed from dvr date manager.
     */
    public void onScheduledRecordingRemoved(ScheduledRecording recording) {
        removeScheduledRecordingFromMap(recording);
        updateConflicts();
    }

    /**
     * Called when user click Info in {@link ScheduleRow}.
     */
    protected void onInfoClicked(ScheduleRow scheduleRow) {
        DvrUiHelper.startDetailsActivity((Activity) mContext,
                scheduleRow.getRecording(), null, true);
    }

    /**
     * Called when user click schedule in {@link ScheduleRow}.
     */
    protected void onScheduleClicked(ScheduleRow scheduleRow) {
        ScheduledRecording scheduledRecording = scheduleRow.getRecording();
        TvInputInfo input = Utils.getTvInputInfoForChannelId(mContext,
                scheduledRecording.getChannelId());
        if (input == null) {
            return;
        }
        List<ScheduledRecording> allScheduledRecordings = new ArrayList<ScheduledRecording>(
                mInputScheduleMap.get(input.getId()).values());
        long maxPriority = scheduledRecording.getPriority();
        for (ScheduledRecording recording : allScheduledRecordings) {
            if (scheduledRecording.isOverLapping(
                    new Range<>(recording.getStartTimeMs(), recording.getEndTimeMs()))) {
                if (maxPriority < recording.getPriority()) {
                    maxPriority = recording.getPriority();
                }
            }
        }
        TvApplication.getSingletons(getContext()).getDvrManager()
                .updateScheduledRecording(ScheduledRecording.buildFrom(scheduledRecording)
                .setPriority(maxPriority + 1).build());
        updateConflicts();
    }

    /**
     * Called when user click delete in {@link ScheduleRow}.
     */
    protected void onDeleteClicked(ScheduleRow scheduleRow, ViewHolder vh) {
        ScheduledRecording recording = scheduleRow.getRecording();
        ScheduleRowViewHolder viewHolder = (ScheduleRowViewHolder) vh;
        if (!scheduleRow.isRemoveScheduleChecked()) {
            if (mConflicts.contains(recording)) {
                TvApplication.getSingletons(mContext)
                        .getDvrManager().removeScheduledRecording(recording);
            }

            if (recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                viewHolder.mDeleteActionView.setImageDrawable(mOnAirDrawable);
                // TODO: Replace an icon whose size is the same as scheudle.
            } else {
                viewHolder.getDeleteActionView().setImageDrawable(mScheduleDrawable);
            }
            viewHolder.greyOutInfo();
            scheduleRow.setRemoveScheduleChecked(true);
            CharSequence deletedInfo = viewHolder.getProgramTitleView().getText();
            if (TextUtils.isEmpty(deletedInfo)) {
                deletedInfo = viewHolder.getChannelNameView().getText();
            }
            Toast.makeText(mContext, mContext.getResources()
                    .getString(R.string.dvr_schedules_deletion_info, deletedInfo),
                    Toast.LENGTH_SHORT).show();
            removeScheduledRecordingFromMap(recording);
        } else {
            if (recording.getState() == ScheduledRecording.STATE_RECORDING_IN_PROGRESS) {
                viewHolder.mDeleteActionView.setImageDrawable(mBeingRecordedDrawable);
                // TODO: Replace an icon whose size is the same as scheudle.
            } else {
                viewHolder.getDeleteActionView().setImageDrawable(mCancelDrawable);
            }
            viewHolder.whiteBackInfo();
            scheduleRow.setRemoveScheduleChecked(false);
            addScheduledRecordingToMap(recording);
        }
        updateConflicts();
        for (ScheduleRowClickListener l : mListeners) {
            l.onDeleteClicked(scheduleRow);
        }
    }

    /**
     * Adds {@link ScheduleRowClickListener}.
     */
    public void addListener(ScheduleRowClickListener scheduleRowClickListener) {
        mListeners.add(scheduleRowClickListener);
    }

    /**
     * Removes {@link ScheduleRowClickListener}.
     */
    public void removeListener(ScheduleRowClickListener
            scheduleRowClickListener) {
        mListeners.remove(scheduleRowClickListener);
    }

    @Override
    protected void onRowViewSelected(ViewHolder vh, boolean selected) {
        super.onRowViewSelected(vh, selected);
        onRowViewSelectedInternal(vh, selected);
    }

    /**
     * Internal method for onRowViewSelected, can be customized by subclass.
     */
    protected void onRowViewSelectedInternal(ViewHolder vh, boolean selected) {
        ScheduleRowViewHolder viewHolder = (ScheduleRowViewHolder) vh;
        boolean isRecordingConflicting = mConflicts.contains(viewHolder.mRecording);
        if (selected) {
            viewHolder.mDeleteActionContainer.setVisibility(View.VISIBLE);
            if (isRecordingConflicting) {
                viewHolder.mScheduleActionContainer.setVisibility(View.VISIBLE);
            }
        } else {
            viewHolder.mDeleteActionContainer.setVisibility(View.GONE);
            if (isRecordingConflicting) {
                viewHolder.mScheduleActionContainer.setVisibility(View.GONE);
            }
        }
    }

    /**
     * A listener for clicking {@link ScheduleRow}.
     */
    public interface ScheduleRowClickListener{
        /**
         * To notify other observers that delete button has been clicked.
         */
        void onDeleteClicked(ScheduleRow scheduleRow);
    }
}
