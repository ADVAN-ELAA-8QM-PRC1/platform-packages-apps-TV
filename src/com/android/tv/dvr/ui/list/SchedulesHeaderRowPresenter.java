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
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.support.v17.leanback.widget.RowPresenter;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.dvr.DvrUiHelper;
import com.android.tv.dvr.SeriesRecording;
import com.android.tv.dvr.ui.DvrSchedulesActivity;
import com.android.tv.dvr.ui.list.SchedulesHeaderRow.SeriesRecordingHeaderRow;

import java.util.Set;

/**
 * A base class for RowPresenter for {@link SchedulesHeaderRow}
 */
public abstract class SchedulesHeaderRowPresenter extends RowPresenter {
    private Context mContext;
    private Set<SchedulesHeaderRowListener> mListeners = new ArraySet<>();

    public SchedulesHeaderRowPresenter(Context context) {
        setHeaderPresenter(null);
        setSelectEffectEnabled(false);
        mContext = context;
    }

    /**
     * Returns the context.
     */
    Context getContext() {
        return mContext;
    }

    /**
     * Adds {@link SchedulesHeaderRowListener}.
     */
    public void addListener(SchedulesHeaderRowListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes {@link SchedulesHeaderRowListener}.
     */
    public void removeListener(SchedulesHeaderRowListener listener) {
        mListeners.remove(listener);
    }

    void notifyUpdateAllScheduleRows() {
        for (SchedulesHeaderRowListener listener : mListeners) {
            listener.onUpdateAllScheduleRows();
        }
    }

    /**
     * A ViewHolder for {@link SchedulesHeaderRow}.
     */
    public static class SchedulesHeaderRowViewHolder extends RowPresenter.ViewHolder {
        private TextView mTitle;
        private TextView mDescription;

        public SchedulesHeaderRowViewHolder(Context context, ViewGroup parent) {
            super(LayoutInflater.from(context).inflate(R.layout.dvr_schedules_header, parent,
                    false));
            mTitle = (TextView) view.findViewById(R.id.header_title);
            mDescription = (TextView) view.findViewById(R.id.header_description);
        }
    }

    @Override
    protected void onBindRowViewHolder(RowPresenter.ViewHolder viewHolder, Object item) {
        super.onBindRowViewHolder(viewHolder, item);
        SchedulesHeaderRowViewHolder headerViewHolder = (SchedulesHeaderRowViewHolder) viewHolder;
        SchedulesHeaderRow header = (SchedulesHeaderRow) item;
        headerViewHolder.mTitle.setText(header.getTitle());
        headerViewHolder.mDescription.setText(header.getDescription());
    }

    /**
     * A presenter for {@link com.android.tv.dvr.ui.list.SchedulesHeaderRow.DateHeaderRow}.
     */
    public static class DateHeaderRowPresenter extends SchedulesHeaderRowPresenter {
        public DateHeaderRowPresenter(Context context) {
            super(context);
        }

        @Override
        protected ViewHolder createRowViewHolder(ViewGroup parent) {
            return new DateHeaderRowViewHolder(getContext(), parent);
        }

        /**
         * A ViewHolder for
         * {@link com.android.tv.dvr.ui.list.SchedulesHeaderRow.DateHeaderRow}.
         */
        public static class DateHeaderRowViewHolder extends SchedulesHeaderRowViewHolder {
            public DateHeaderRowViewHolder(Context context, ViewGroup parent) {
                super(context, parent);
            }
        }
    }

    /**
     * A presenter for {@link SeriesRecordingHeaderRow}.
     */
    public static class SeriesRecordingHeaderRowPresenter extends SchedulesHeaderRowPresenter {
        private final boolean mLtr;
        private final Drawable mSettingsDrawable;
        private final Drawable mCancelDrawable;
        private final Drawable mResumeDrawable;

        private final String mSettingsInfo;
        private final String mCancelAllInfo;
        private final String mResumeInfo;

        public SeriesRecordingHeaderRowPresenter(Context context) {
            super(context);
            mLtr = context.getResources().getConfiguration().getLayoutDirection()
                    == View.LAYOUT_DIRECTION_LTR;
            mSettingsDrawable = context.getDrawable(R.drawable.ic_settings);
            mCancelDrawable = context.getDrawable(R.drawable.ic_dvr_cancel_large);
            mResumeDrawable = context.getDrawable(R.drawable.ic_record_start);
            mSettingsInfo = context.getString(R.string.dvr_series_schedules_settings);
            mCancelAllInfo = context.getString(R.string.dvr_series_schedules_cancel_all);
            mResumeInfo = context.getString(R.string.dvr_series_schedules_resume);
        }

        @Override
        protected ViewHolder createRowViewHolder(ViewGroup parent) {
            return new SeriesRecordingRowViewHolder(getContext(), parent);
        }

        @Override
        protected void onBindRowViewHolder(RowPresenter.ViewHolder viewHolder, Object item) {
            super.onBindRowViewHolder(viewHolder, item);
            SeriesRecordingRowViewHolder headerViewHolder =
                    (SeriesRecordingRowViewHolder) viewHolder;
            SeriesRecordingHeaderRow header = (SeriesRecordingHeaderRow) item;
            headerViewHolder.mSeriesSettingsButton.setVisibility(
                    isSeriesScheduleCanceled(getContext(), header) ? View.INVISIBLE : View.VISIBLE);
            headerViewHolder.mSeriesSettingsButton.setText(mSettingsInfo);
            setTextDrawable(headerViewHolder.mSeriesSettingsButton, mSettingsDrawable);
            if (header.isCancelAllChecked()) {
                headerViewHolder.mTogglePauseButton.setText(mResumeInfo);
                setTextDrawable(headerViewHolder.mTogglePauseButton, mResumeDrawable);
            } else {
                headerViewHolder.mTogglePauseButton.setText(mCancelAllInfo);
                setTextDrawable(headerViewHolder.mTogglePauseButton, mCancelDrawable);
            }
            headerViewHolder.mSeriesSettingsButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    DvrUiHelper.startSeriesSettingsActivity(getContext(),
                            header.getSeriesRecording().getId());
                }
            });
            headerViewHolder.mTogglePauseButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (!header.isCancelAllChecked()) {
                        DvrUiHelper.showCancelAllSeriesRecordingDialog((DvrSchedulesActivity) view
                                .getContext());
                    } else {
                        if (isSeriesScheduleCanceled(getContext(), header)) {
                            TvApplication.getSingletons(getContext()).getDvrManager()
                                    .updateSeriesRecording(SeriesRecording.buildFrom(header
                                            .getSeriesRecording()).setState(SeriesRecording
                                            .STATE_SERIES_NORMAL).build());
                        }
                        header.setCancelAllChecked(false);
                        notifyUpdateAllScheduleRows();
                    }
                }
            });
        }

        private void setTextDrawable(TextView textView, Drawable drawableStart) {
            if (mLtr) {
                textView.setCompoundDrawablesWithIntrinsicBounds(drawableStart, null, null, null);
            } else {
                textView.setCompoundDrawablesWithIntrinsicBounds(null, null, drawableStart, null);
            }
        }

        private static boolean isSeriesScheduleCanceled(Context context,
                SeriesRecordingHeaderRow header) {
            return TvApplication.getSingletons(context).getDvrDataManager()
                    .getSeriesRecording(header.getSeriesRecording().getId()).getState()
                    == SeriesRecording.STATE_SERIES_CANCELED;
        }

        /**
         * A ViewHolder for {@link SeriesRecordingHeaderRow}.
         */
        public static class SeriesRecordingRowViewHolder extends SchedulesHeaderRowViewHolder {
            private final TextView mSeriesSettingsButton;
            private final TextView mTogglePauseButton;
            private final boolean mLtr;

            private final View mSelector;

            private View mLastFocusedView;
            public SeriesRecordingRowViewHolder(Context context, ViewGroup parent) {
                super(context, parent);
                mLtr = context.getResources().getConfiguration().getLayoutDirection()
                        == View.LAYOUT_DIRECTION_LTR;
                view.findViewById(R.id.button_container).setVisibility(View.VISIBLE);
                mSeriesSettingsButton = (TextView) view.findViewById(R.id.series_settings);
                mTogglePauseButton = (TextView) view.findViewById(R.id.series_toggle_pause);
                mSelector = view.findViewById(R.id.selector);
                OnFocusChangeListener onFocusChangeListener = new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View view, boolean focused) {
                        onIconFouseChange(view);
                    }
                };
                mSeriesSettingsButton.setOnFocusChangeListener(onFocusChangeListener);
                mTogglePauseButton.setOnFocusChangeListener(onFocusChangeListener);
            }

            void onIconFouseChange(View focusedView) {
                updateSelector(focusedView, mSelector);
            }

            private void updateSelector(View focusedView, final View selectorView) {
                int animationDuration = selectorView.getContext().getResources()
                        .getInteger(android.R.integer.config_shortAnimTime);
                DecelerateInterpolator interpolator = new DecelerateInterpolator();

                if (focusedView.hasFocus()) {
                    final ViewGroup.LayoutParams lp = selectorView.getLayoutParams();
                    final int targetWidth = focusedView.getWidth();
                    float targetTranslationX;
                    if (mLtr) {
                        targetTranslationX = focusedView.getLeft() - selectorView.getLeft();
                    } else {
                        targetTranslationX = focusedView.getRight() - selectorView.getRight();
                    }

                    // if the selector is invisible, set the width and translation X directly -
                    // don't animate.
                    if (selectorView.getAlpha() == 0) {
                        selectorView.setTranslationX(targetTranslationX);
                        lp.width = targetWidth;
                        selectorView.requestLayout();
                    }

                    // animate the selector in and to the proper width and translation X.
                    final float deltaWidth = lp.width - targetWidth;
                    selectorView.animate().cancel();
                    selectorView.animate().translationX(targetTranslationX).alpha(1f)
                            .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                                @Override
                                public void onAnimationUpdate(ValueAnimator animation) {
                                    // Set width to the proper width for this animation step.
                                    lp.width = targetWidth + Math.round(
                                            deltaWidth * (1f - animation.getAnimatedFraction()));
                                    selectorView.requestLayout();
                                }
                            }).setDuration(animationDuration).setInterpolator(interpolator).start();
                    mLastFocusedView = focusedView;
                } else if (mLastFocusedView == focusedView) {
                    selectorView.animate().cancel();
                    selectorView.animate().alpha(0f).setDuration(animationDuration)
                            .setInterpolator(interpolator).start();
                    mLastFocusedView = null;
                }
            }
        }
    }

    public interface SchedulesHeaderRowListener {
        /**
         * Updates all schedule rows.
         */
        void onUpdateAllScheduleRows();
    }
}
