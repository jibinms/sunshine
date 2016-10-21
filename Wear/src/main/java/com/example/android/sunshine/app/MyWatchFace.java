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
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Class to draw a custom watch-face, with time, day, high-low temperature, and
 * corresponding weather icon.
 */
public class MyWatchFace extends CanvasWatchFaceService {

    //Typeface we would use.
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC);
    private int roundOffset;
    private Bitmap bitmap = null;
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

        private static final String DATE_FORMAT_EEE = "EEE, MMM dd yyyy";
        private final Handler mUpdateTimeHandler = new EngineHandler(this);
        private boolean mRegisteredTimeZoneReceiver = false;
        private GoogleApiClient mApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        private Paint mBackgroundPaint, mTextPaint, mTemperaturePaint;
        private boolean mAmbient;
        private Calendar mCalendar;
        private float mXOffsetTime, mXOffsetDate, mYOffset, mYOffsetDate, mYOffsetTemperature;
        private String tempHigh = null, tempLow = null;

        //Receiver that's notified of the time zone changes.
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);


            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
//                    .setAcceptsTapEvents(true)
                    .build());

            //getting the resources.
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            mYOffsetTemperature = resources.getDimension(R.dimen.digital_y_offset_temp);
            initPaints(resources);
            mApiClient.connect();
            mCalendar = Calendar.getInstance();
        }

        private void initPaints(Resources resources) {

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint.setColor(resources.getColor(R.color.digital_text));
            mTextPaint.setTypeface(NORMAL_TYPEFACE);
            mTextPaint.setAntiAlias(true);

            mTemperaturePaint = new Paint();
            mTemperaturePaint.setTypeface(BOLD_TYPEFACE);
            mTemperaturePaint.setColor(resources.getColor(R.color.digital_text));
            mTemperaturePaint.setAntiAlias(true);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mApiClient.connect();
                registerReceiver();
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                mApiClient.disconnect();
                unregisterReceiver();
            }

            updateTimer();
        }


        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }


        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }


        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Resources resources = MyWatchFace.this.getResources();
            float textSize;
            if (insets.isRound()) {
                textSize = resources.getDimension(R.dimen.digital_text_size_round);

                mTemperaturePaint.setTextSize(resources.getInteger(R.integer.temp_round));
                roundOffset = (int) resources.getDimension(R.dimen.digital_x_offset);
            } else {
                textSize = resources.getDimension(R.dimen.digital_text_size);
                mXOffsetTime = resources.getDimension(R.dimen.digital_x_offset);
                mTemperaturePaint.setTextSize(resources.getInteger(R.integer.temp_square));
                roundOffset = 0;
            }

            mTextPaint.setTextSize(textSize);

            Rect rct = new Rect();

            String text = "88:88";
            mTemperaturePaint.getTextBounds(text, 0, text.length(), rct);
            mXOffsetTime = rct.width() / 2;
            mYOffsetTemperature = rct.height() / 2;
            text = "Wed, Oct 30 2016";
            mTextPaint.getTextBounds(text, 0, text.length(), rct);
            mXOffsetDate = rct.width() / 2;


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
                    mTextPaint.setAntiAlias(!inAmbientMode);
                    mTemperaturePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            updateTimer();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            //No current requirements to do anything.
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            int width = bounds.width(), height = bounds.height();
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.save();
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                Random random = new Random();
                mBackgroundPaint.setColor(Color.argb(150, random.nextInt(256), random.nextInt(256), random.nextInt(256)));
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            }

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            String text = String.format(Locale.US, "%02d:%02d", mCalendar.get(Calendar.HOUR_OF_DAY),
                    mCalendar.get(Calendar.MINUTE));
            SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT_EEE, Locale.US);
            String date = format.format(now);
            canvas.drawText(text, -mXOffsetTime + width / 2, mYOffset + roundOffset, mTemperaturePaint);
            canvas.drawText(date, -mXOffsetDate + width / 2, mYOffsetDate + roundOffset, mTextPaint);

            if (tempHigh == null || tempLow == null) {
                return;
            }

            float xOff = mXOffsetTime;
            float yOff = mYOffsetTemperature;
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, mXOffsetTime, (height + mYOffsetTemperature) / 2, mBackgroundPaint);
                xOff += bitmap.getWidth();
                yOff += bitmap.getHeight();
            }
            canvas.drawText((tempHigh + " | " + tempLow), xOff, (height + yOff) / 2, mTemperaturePaint);
        }


        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }


        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


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
        public void onConnected(@Nullable Bundle bundle) {

            Wearable.DataApi.addListener(mApiClient, this).setResultCallback(new ResultCallbacks<Status>() {
                @Override
                public void onSuccess(@NonNull Status status) {
                    Log.i("myTag", String.valueOf(status));
                }

                @Override
                public void onFailure(@NonNull Status status) {
                    Log.i("myTag", String.valueOf(status));
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals("/weather")) {
                        final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        tempHigh = dataMap.getString("high");
                        tempLow = dataMap.getString("low");

                        new Thread(new Runnable() {
                            @Override
                            public void run() {

                                Asset asset = dataMap.getAsset("icon");
                                if (asset == null)
                                    throw new IllegalArgumentException("Asset must not be null!");

                                ConnectionResult result =
                                        mApiClient.blockingConnect(10000, TimeUnit.MILLISECONDS);

                                if (!result.isSuccess()) {
                                    return;
                                }
                                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                                        mApiClient, asset).await().getInputStream();

                                if (assetInputStream == null) {
                                    return;
                                }
                                // decode the stream into a bitmap
                                bitmap = BitmapFactory.decodeStream(assetInputStream);
                                invalidate();
                            }
                        }).start();
                    }
                    invalidate();
                }
            }
        }
    }
}
