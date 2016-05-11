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

package com.android.tv.common.ui.setup.leanback;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.support.annotation.ColorInt;
import android.support.annotation.VisibleForTesting;
import android.util.AttributeSet;
import android.view.View;

import com.android.tv.common.R;
import com.android.tv.common.annotation.UsedByReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A page indicator with dots.
 * @hide
 */
public class PagingIndicator extends View {
    // attribute
    private final int mDotDiameter;
    private final int mDotRadius;
    private final int mDotGap;
    private final int mArrowDiameter;
    private final int mArrowRadius;
    private final int mArrowGap;
    private final int mShadowRadius;
    private Dot[] mDots;
    // X position when the dot is selected.
    private int[] mDotSelectedX;
    // X position when the dot is located to the left of the selected dot.
    private int[] mDotSelectedLeftX;
    // X position when the dot is located to the right of the selected dot.
    private int[] mDotSelectedRightX;
    private int mDotCenterY;

    // state
    private int mPageCount;
    private int mCurrentPage;
    private int mPreviousPage;

    // drawing
    @ColorInt
    private final int mDotFgSelectColor;
    private final Paint mBgPaint;
    private final Paint mFgPaint;
    private final Animator mShowAnimator;
    private final Animator mHideAnimator;
    private final AnimatorSet mAnimator = new AnimatorSet();
    private final Bitmap mArrow;
    private final Rect mArrowRect;
    private final float mArrowToBgRatio;

    public PagingIndicator(Context context) {
        this(context, null, 0);
    }

    public PagingIndicator(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PagingIndicator(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Resources res = getResources();
        mDotRadius = res.getDimensionPixelSize(R.dimen.lb_page_indicator_dot_radius);
        mDotDiameter = mDotRadius * 2;
        mDotGap = res.getDimensionPixelSize(R.dimen.lb_page_indicator_dot_gap);
        mArrowGap = res.getDimensionPixelSize(R.dimen.lb_page_indicator_arrow_gap);
        mArrowDiameter = res.getDimensionPixelSize(R.dimen.lb_page_indicator_arrow_diameter);
        mArrowRadius = mArrowDiameter / 2;
        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mDotFgSelectColor = res.getColor(R.color.lb_page_indicator_arrow_background);
        int bgColor = res.getColor(R.color.lb_page_indicator_dot);
        int shadowColor = res.getColor(R.color.lb_page_indicator_arrow_shadow);
        mBgPaint.setColor(bgColor);
        mShadowRadius = res.getDimensionPixelSize(R.dimen.lb_page_indicator_arrow_shadow_radius);
        mFgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int shadowOffset = res.getDimensionPixelSize(R.dimen.lb_page_indicator_arrow_shadow_offset);
        mFgPaint.setShadowLayer(mShadowRadius, shadowOffset, shadowOffset, shadowColor);
        mArrow = BitmapFactory.decodeResource(res, R.drawable.lb_ic_nav_arrow);
        mArrowRect = new Rect(0, 0, mArrow.getWidth(), mArrow.getHeight());
        mArrowToBgRatio = (float) mArrow.getWidth() / (float) mArrowDiameter;
        // Initialize animations.
        List<Animator> animators = new ArrayList<>();
        mShowAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.animator.lb_page_indicator_dot_show);
        mHideAnimator = AnimatorInflater.loadAnimator(getContext(),
                R.animator.lb_page_indicator_dot_hide);
        animators.add(mShowAnimator);
        animators.add(mHideAnimator);
        mAnimator.playTogether(animators);
        // Use software layer to show shadows.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    /**
     * Sets the page count.
     */
    public void setPageCount(int pages) {
        if (pages <= 0) {
            throw new IllegalArgumentException("The page count should be a positive integer");
        }
        mPageCount = pages;
        mDots = new Dot[mPageCount];
        for (int i = 0; i < mPageCount; ++i) {
            mDots[i] = new Dot();
        }
        calculateDotPositions();
        setSelectedPage(0);
    }

    /**
     * Called when the page has been selected.
     */
    public void onPageSelected(int pageIndex, boolean withAnimation) {
        if (mCurrentPage == pageIndex) {
            return;
        }
        if (mAnimator.isStarted()) {
            mAnimator.end();
        }
        mPreviousPage = mCurrentPage;
        if (withAnimation) {
            mHideAnimator.setTarget(mDots[mPreviousPage]);
            mShowAnimator.setTarget(mDots[pageIndex]);
            mAnimator.start();
        }
        setSelectedPage(pageIndex);
    }

    private void calculateDotPositions() {
        int left = getPaddingLeft();
        int top = getPaddingTop();
        int right = getWidth() - getPaddingRight();
        int requiredWidth = getRequiredWidth();
        int mid = (left + right) / 2;
        int startLeft = mid - requiredWidth / 2;
        mDotSelectedX = new int[mPageCount];
        mDotSelectedLeftX = new int[mPageCount];
        mDotSelectedRightX = new int[mPageCount];
        // mDotSelectedX[0] should be mDotSelectedLeftX[-1] + mArrowGap
        mDotSelectedX[0] = startLeft + mDotRadius - mDotGap + mArrowGap;
        mDotSelectedLeftX[0] = startLeft + mDotRadius;
        mDotSelectedRightX[0] = 0;
        for (int i = 1; i < mPageCount; i++) {
            mDotSelectedX[i] = mDotSelectedLeftX[i - 1] + mArrowGap;
            mDotSelectedLeftX[i] = mDotSelectedLeftX[i - 1] + mDotGap;
            mDotSelectedRightX[i] = mDotSelectedX[i - 1] + mArrowGap;
        }
        mDotCenterY = top + mArrowRadius;
        adjustDotPosition();
    }

    @VisibleForTesting
    int getPageCount() {
        return mPageCount;
    }

    @VisibleForTesting
    int[] getDotSelectedX() {
        return mDotSelectedX;
    }

    @VisibleForTesting
    int[] getDotSelectedLeftX() {
        return mDotSelectedLeftX;
    }

    @VisibleForTesting
    int[] getDotSelectedRightX() {
        return mDotSelectedRightX;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredHeight = getDesiredHeight();
        int height;
        switch (MeasureSpec.getMode(heightMeasureSpec)) {
            case MeasureSpec.EXACTLY:
                height = MeasureSpec.getSize(heightMeasureSpec);
                break;
            case MeasureSpec.AT_MOST:
                height = Math.min(desiredHeight, MeasureSpec.getSize(heightMeasureSpec));
                break;
            case MeasureSpec.UNSPECIFIED:
            default:
                height = desiredHeight;
                break;
        }
        int desiredWidth = getDesiredWidth();
        int width;
        switch (MeasureSpec.getMode(widthMeasureSpec)) {
            case MeasureSpec.EXACTLY:
                width = MeasureSpec.getSize(widthMeasureSpec);
                break;
            case MeasureSpec.AT_MOST:
                width = Math.min(desiredWidth, MeasureSpec.getSize(widthMeasureSpec));
                break;
            case MeasureSpec.UNSPECIFIED:
            default:
                width = desiredWidth;
                break;
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        setMeasuredDimension(width, height);
        calculateDotPositions();
    }

    private int getDesiredHeight() {
        return getPaddingTop() + mArrowDiameter + getPaddingBottom() + mShadowRadius;
    }

    private int getRequiredWidth() {
        return 2 * mDotRadius + 2 * mArrowGap + (mPageCount - 3) * mDotGap;
    }

    private int getDesiredWidth() {
        return getPaddingLeft() + getRequiredWidth() + getPaddingRight();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (int i = 0; i < mPageCount; ++i) {
            mDots[i].draw(canvas);
        }
    }

    private void setSelectedPage(int now) {
        if (now == mCurrentPage) {
            return;
        }

        mCurrentPage = now;
        adjustDotPosition();
    }

    private void adjustDotPosition() {
        for (int i = 0; i < mCurrentPage; ++i) {
            mDots[i].deselect();
            mDots[i].mDirection = i == mPreviousPage ? Dot.LEFT : Dot.RIGHT;
            mDots[i].mCenterX = mDotSelectedLeftX[i];
        }
        mDots[mCurrentPage].select();
        mDots[mCurrentPage].mDirection = mPreviousPage < mCurrentPage ? Dot.LEFT : Dot.RIGHT;
        mDots[mCurrentPage].mCenterX = mDotSelectedX[mCurrentPage];
        for (int i = mCurrentPage + 1; i < mPageCount; ++i) {
            mDots[i].deselect();
            mDots[i].mDirection = Dot.RIGHT;
            mDots[i].mCenterX = mDotSelectedRightX[i];
        }
    }

    public class Dot {
        static final float LEFT = -1;
        static final float RIGHT = 1;

        float mAlpha;
        @ColorInt
        int mBgColor;
        @ColorInt
        int mFgColor;
        float mTranslationX;
        float mCenterX;
        float mDiameter;
        float mRadius;
        float mArrowImageRadius;
        float mDirection = RIGHT;

        void select() {
            mTranslationX = 0.0f;
            mCenterX = 0.0f;
            mDiameter = mArrowDiameter;
            mRadius = mArrowRadius;
            mArrowImageRadius = mRadius * mArrowToBgRatio;
            mAlpha = 1.0f;
            adjustAlpha();
        }

        void deselect() {
            mTranslationX = 0.0f;
            mCenterX = 0.0f;
            mDiameter = mDotDiameter;
            mRadius = mDotRadius;
            mArrowImageRadius = mRadius * mArrowToBgRatio;
            mAlpha = 0.0f;
            adjustAlpha();
        }

        public void adjustAlpha() {
            int alpha = Math.round(0xFF * mAlpha);
            int red = Color.red(mDotFgSelectColor);
            int green = Color.green(mDotFgSelectColor);
            int blue = Color.blue(mDotFgSelectColor);
            mFgColor = Color.argb(alpha, red, green, blue);
        }

        @UsedByReflection
        public float getAlpha() {
            return mAlpha;
        }

        @UsedByReflection
        public void setAlpha(float alpha) {
            this.mAlpha = alpha;
            adjustAlpha();
            invalidate();
        }

        @UsedByReflection
        public float getTranslationX() {
            return mTranslationX;
        }

        @UsedByReflection
        public void setTranslationX(float translationX) {
            this.mTranslationX = translationX * mDirection;
            invalidate();
        }

        @UsedByReflection
        public float getDiameter() {
            return mDiameter;
        }

        @UsedByReflection
        public void setDiameter(float diameter) {
            this.mDiameter = diameter;
            this.mRadius = diameter / 2;
            this.mArrowImageRadius = diameter / 2 * mArrowToBgRatio;
            invalidate();
        }

        void draw(Canvas canvas) {
            float centerX = mCenterX + mTranslationX;
            canvas.drawCircle(centerX, mDotCenterY, mRadius, mBgPaint);
            if (mAlpha > 0) {
                mFgPaint.setColor(mFgColor);
                canvas.drawCircle(centerX, mDotCenterY, mRadius, mFgPaint);
                canvas.drawBitmap(mArrow, mArrowRect, new Rect((int) (centerX - mArrowImageRadius),
                        (int) (mDotCenterY - mArrowImageRadius),
                        (int) (centerX + mArrowImageRadius),
                        (int) (mDotCenterY + mArrowImageRadius)), null);
            }
        }
    }
}
