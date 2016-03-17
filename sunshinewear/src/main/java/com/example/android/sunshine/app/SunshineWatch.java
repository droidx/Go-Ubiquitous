/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 * Reference: WatchFace sample
 */
public class SunshineWatch extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatch.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatch.Engine> mWeakReference;

        public EngineHandler(SunshineWatch.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatch.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        private static final String COLON_STRING = ":";
        private static final String WATCH_FACE_PATH = "/watchface/temp";
        private static final String TEMP_HIGH_KEY = "temp_high";
        private static final String TEMP_LOW_KEY = "temp_low";
        private static final String TEMP_WEATHER_ID_KEY = "temp_weather_id";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        // background
        private Paint mBackgroundPaint;
        // time hour
        private Paint mTimeHourPaint;
        // time minute
        private Paint mTimeMinutePaint;
        // date
        private Paint mDatePaint;
        // temp high
        private Paint mTempHighPaint;
        // temp low
        private Paint mTempLowPaint;

        // ambient paint
        private Paint mAmbientDatePaint;
        private Paint mAmbientLowTempPaint;

        private Calendar mCalendar;

        boolean mAmbient;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;


        // Offsets
        private float mXOffset;
        private float mYTimeOffset;
        private float mYDateOffset;
        private float mYDividerOffset;
        private float mYTemperatureOffset;
        private float mColonWidth;

        private Bitmap mWeatherGraphic;

        private Context mContext;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private GoogleApiClient mGoogleApiClient;

        private String mHighTempToday;
        private String mLowTempToday;
        private int mWeatherIdToday;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mContext = SunshineWatch.this;
            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatch.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(mContext, R.color.background));

            mTimeHourPaint = createTextPaint(Color.WHITE);
            mTimeMinutePaint = createTextPaint(Color.WHITE);
            mDatePaint = createTextPaint(ContextCompat.getColor(mContext, R.color.date_color));
            mTempHighPaint = createTextPaint(ContextCompat.getColor(mContext, R.color.temp_high_color));
            mTempLowPaint = createTextPaint(ContextCompat.getColor(mContext, R.color.temp_low_color));
            mAmbientDatePaint = createTextPaint(Color.WHITE);
            mAmbientLowTempPaint = createTextPaint(Color.WHITE);

            mCalendar = Calendar.getInstance();
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatch.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatch.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatch.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatch.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYTimeOffset = resources.getDimension(isRound
                    ? R.dimen.time_y_offset_round : R.dimen.time_y_offset);
            mYDateOffset = resources.getDimension(isRound
                    ? R.dimen.date_y_offest_round : R.dimen.date_y_offest);
            mYDividerOffset = resources.getDimension(isRound
                    ? R.dimen.divider_y_offest_round : R.dimen.divider_y_offest);
            mYTemperatureOffset = resources.getDimension(isRound
                    ? R.dimen.temp_y_offest_round : R.dimen.temp_y_offest);

            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.time_text_size_round : R.dimen.time_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

            mTimeHourPaint.setTextSize(timeTextSize);
            mTimeMinutePaint.setTextSize(timeTextSize);
            mDatePaint.setTextSize(dateTextSize);
            mTempHighPaint.setTextSize(tempTextSize);
            mTempLowPaint.setTextSize(tempTextSize);
            mAmbientDatePaint.setTextSize(dateTextSize);
            mAmbientLowTempPaint.setTextSize(tempTextSize);
            mColonWidth = mTimeHourPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeHourPaint.setAntiAlias(!inAmbientMode);
                    mTimeMinutePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mAmbientDatePaint.setAntiAlias(!inAmbientMode);
                    mTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTempLowPaint.setAntiAlias(!inAmbientMode);
                    mAmbientLowTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatch.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    /*mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));*/
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            Paint datePaint;
            Paint lowTempPaint;
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
                datePaint = mAmbientDatePaint;
                lowTempPaint = mAmbientLowTempPaint;
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                datePaint = mDatePaint;
                lowTempPaint = mTempLowPaint;
            }

            float x = mXOffset;
            long millis = System.currentTimeMillis();
            mCalendar.setTimeInMillis(millis);
            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatch.this);

            String hourString;
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }

            float timeLength = mTimeHourPaint.measureText(hourString) + mColonWidth + mTimeMinutePaint.measureText(minuteString);
            x = (bounds.width() - timeLength) / 2;
            canvas.drawText(hourString, x, mYTimeOffset, mTimeHourPaint);
            x += mTimeHourPaint.measureText(hourString);

            canvas.drawText(COLON_STRING, x, mYTimeOffset, mTimeHourPaint);
            x += mColonWidth;
            // Draw the minutes.
            canvas.drawText(minuteString, x, mYTimeOffset, mTimeMinutePaint);

            String date = DateUtils.formatDateTime(getApplicationContext(), millis, DateUtils.FORMAT_ABBREV_WEEKDAY | DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            x = (bounds.width() - datePaint.measureText(date)) / 2;
            canvas.drawText(date, x, mYDateOffset, datePaint);

            if (mHighTempToday != null && mLowTempToday != null && mWeatherGraphic != null) {
                canvas.drawLine(bounds.centerX() - 24, mYDividerOffset, bounds.centerX() + 24, mYDividerOffset, datePaint);
                float tempHighLength = mTempHighPaint.measureText(mHighTempToday);
                float lowTempLength = lowTempPaint.measureText(mLowTempToday);
                float totalLen = tempHighLength + lowTempLength;
                x = (bounds.width() - totalLen) / 2;
                if (!mAmbient) {
                    totalLen = tempHighLength + lowTempLength + mWeatherGraphic.getWidth();
                    x = (bounds.width() - totalLen) / 2;
                    canvas.drawBitmap(mWeatherGraphic, x, mYTemperatureOffset - mWeatherGraphic.getHeight(), null);
                    x += mWeatherGraphic.getWidth() + 5;
                }
                canvas.drawText(mHighTempToday, x, mYTemperatureOffset, mTempHighPaint);
                x += mTempHighPaint.measureText(mHighTempToday);
                canvas.drawText(mLowTempToday, x, mYTemperatureOffset, lowTempPaint);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + bundle);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);

            // TODO implement the below function
            // updateConfigDataItemAndUiOnStartup();
        }

        @Override
        public void onConnectionSuspended(int i) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + i);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = dataEvent.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WATCH_FACE_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        mHighTempToday = dataMap.getString(TEMP_HIGH_KEY);
                        mLowTempToday = dataMap.getString(TEMP_LOW_KEY);

                        if (dataMap.containsKey(TEMP_WEATHER_ID_KEY)) {
                            mWeatherIdToday = dataMap.getInt(TEMP_WEATHER_ID_KEY);
                            int weatherDrawable = Utility.getIconResourceForWeatherCondition(mWeatherIdToday);
                            Bitmap weatherBitmap = BitmapFactory.decodeResource(getApplicationContext().getResources(), weatherDrawable);
                            float width = (weatherBitmap.getWidth() * mTempHighPaint.getTextSize()) / weatherBitmap.getHeight();
                            mWeatherGraphic = Bitmap.createScaledBitmap(weatherBitmap, (int) width, (int) mTempHighPaint.getTextSize(), true);
                        }
                        //updateCount(dataMap.getInt(COUNT_KEY));


                    }

                    invalidate();
                }
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + connectionResult);
            }
        }
    }
}
