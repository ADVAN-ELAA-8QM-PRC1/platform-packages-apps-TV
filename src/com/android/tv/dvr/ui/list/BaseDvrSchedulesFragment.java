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

import android.os.Bundle;
import android.support.v17.leanback.app.DetailsFragment;
import android.support.v17.leanback.widget.ClassPresenterSelector;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.android.tv.R;
import com.android.tv.TvApplication;
import com.android.tv.dvr.DvrDataManager;
import com.android.tv.dvr.ScheduledRecording;

/**
 * A  base fragment to show the list of schedule recordings.
 */
public abstract class BaseDvrSchedulesFragment extends DetailsFragment
        implements DvrDataManager.ScheduledRecordingListener,
        SchedulesHeaderRowPresenter.SchedulesHeaderRowListener,
        ScheduleRowPresenter.ScheduleRowClickListener {
    /**
     * The key for scheduled recording which has be selected in the list.
     */
    public static String SCHEDULES_KEY_SCHEDULED_RECORDING = "schedules_key_scheduled_recording";

    private SchedulesHeaderRowPresenter mHeaderRowPresenter;
    private ScheduleRowPresenter mRowPresenter;
    private ScheduleRowAdapter mRowsAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ClassPresenterSelector presenterSelector = new ClassPresenterSelector();
        mHeaderRowPresenter = onCreateHeaderRowPresenter();
        mHeaderRowPresenter.addListener(this);
        mRowPresenter = onCreateRowPresenter();
        mRowPresenter.addListener(this);
        presenterSelector.addClassPresenter(SchedulesHeaderRow.class, mHeaderRowPresenter);
        presenterSelector.addClassPresenter(ScheduleRow.class, mRowPresenter);
        mRowsAdapter = onCreateRowsAdapter(presenterSelector);
        setAdapter(mRowsAdapter);
        mRowsAdapter.start();
        TvApplication.getSingletons(getContext()).getDvrDataManager()
                .addScheduledRecordingListener(this);
    }

    /**
     * Returns rows adapter.
     */
    protected ScheduleRowAdapter getRowsAdapter() {
        return mRowsAdapter;
    }

    /**
     * Shows the empty message.
     */
    protected void showEmptyMessage(int message) {
        TextView emptyInfoScreenView = (TextView) getActivity().findViewById(
                R.id.empty_info_screen);
        emptyInfoScreenView.setText(message);
        emptyInfoScreenView.setVisibility(View.VISIBLE);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        // setSelectedPosition works only after the view is attached to a window.
        view.getViewTreeObserver().addOnWindowAttachListener(
                new ViewTreeObserver.OnWindowAttachListener() {
            @Override
            public void onWindowAttached() {
                int firstItemPosition = getFirstItemPosition();
                if (firstItemPosition != -1) {
                    setSelectedPosition(firstItemPosition, false);
                }
                view.getViewTreeObserver().removeOnWindowAttachListener(this);
            }

            @Override
            public void onWindowDetached() {
            }
        });
        return view;
    }

    @Override
    public View onInflateTitleView(LayoutInflater inflater, ViewGroup parent,
            Bundle savedInstanceState) {
        // Workaround of b/31046014
        return null;
    }

    @Override
    public void onDestroy() {
        TvApplication.getSingletons(getContext()).getDvrDataManager()
                .removeScheduledRecordingListener(this);
        mHeaderRowPresenter.removeListener(this);
        mRowPresenter.removeListener(this);
        mRowsAdapter.stop();
        super.onDestroy();
    }

    /**
     * Creates header row presenter.
     */
    public abstract SchedulesHeaderRowPresenter onCreateHeaderRowPresenter();

    /**
     * Creates rows presenter.
     */
    public abstract ScheduleRowPresenter onCreateRowPresenter();

    /**
     * Creates rows adapter.
     */
    public abstract ScheduleRowAdapter onCreateRowsAdapter(ClassPresenterSelector presenterSelecor);

    /**
     * Gets the first focus position in schedules list.
     */
    protected int getFirstItemPosition() {
        Bundle args = getArguments();
        ScheduledRecording recording = null;
        if (args != null) {
            recording = args.getParcelable(SCHEDULES_KEY_SCHEDULED_RECORDING);
        }
        final int selectedPostion = mRowsAdapter.indexOf(
                mRowsAdapter.findRowByScheduledRecording(recording));
        if (selectedPostion != -1) {
            return selectedPostion;
        }
        for (int i = 0; i < mRowsAdapter.size(); i++) {
            if (mRowsAdapter.get(i) instanceof ScheduleRow) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void onScheduledRecordingAdded(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecording recording : scheduledRecordings) {
            if (mRowPresenter != null) {
                mRowPresenter.onScheduledRecordingAdded(recording);
            }
            if (mRowsAdapter != null) {
                mRowsAdapter.onScheduledRecordingAdded(recording);
            }
        }
    }

    @Override
    public void onScheduledRecordingRemoved(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecording recording : scheduledRecordings) {
            if (mRowPresenter != null) {
                mRowPresenter.onScheduledRecordingRemoved(recording);
            }
            if (mRowsAdapter != null) {
                mRowsAdapter.onScheduledRecordingRemoved(recording);
            }
        }
    }

    @Override
    public void onScheduledRecordingStatusChanged(ScheduledRecording... scheduledRecordings) {
        for (ScheduledRecording recording : scheduledRecordings) {
            if (mRowPresenter != null) {
                mRowPresenter.onScheduledRecordingUpdated(recording);
            }
            if (mRowsAdapter != null) {
                mRowsAdapter.onScheduledRecordingUpdated(recording);
            }
        }
    }

    @Override
    public void onUpdateAllScheduleRows() {
        if (getRowsAdapter() != null) {
            getRowsAdapter().notifyArrayItemRangeChanged(0, getRowsAdapter().size());
        }
    }

    @Override
    public void onDeleteClicked(ScheduleRow scheduleRow) {
        if (mRowsAdapter != null) {
            mRowsAdapter.notifyArrayItemRangeChanged(0, mRowsAdapter.size());
        }
    }
}
