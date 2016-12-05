package com.boxsetter;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * Created by nic.ford on 15/05/15.
 */
public class BoxsetterVideoActivity extends Activity implements VideoControllerView.MediaPlayerControl, MediaPlayer.OnPreparedListener, SurfaceHolder.Callback {

    private boolean is16x9 = true;
    private VideoControllerView mMediaController;
    private MediaPlayer mPlayer;
    private long lastShow = 0;
    private BroadcastEntity be;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);

        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        decorView.setSystemUiVisibility(uiOptions);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.hide();

        setContentView(R.layout.video_view);

        Intent intent = getIntent();
        String beUrl = intent.getStringExtra(BoxsetterActivity.ENTITY_URL);
        if (beUrl != null) {
            intent.removeExtra(BoxsetterActivity.ENTITY_URL);

            this.be = BroadcastEntity.getBroadcastEntity(beUrl);
            String videoUrl = intent.getStringExtra(BoxsetterActivity.VIDEO_URL);

            SurfaceView videoSurface = (SurfaceView) findViewById(R.id.surfaceView);
            SurfaceHolder videoHolder = videoSurface.getHolder();
            videoHolder.addCallback(this);

            videoSurface.setOnTouchListener(new View.OnTouchListener() {
                private static final int MIN_DISTANCE = 100;

                private float lastX = 0;
                private float lastY = 0;
                private float firstX = 0;
                private float firstY = 0;
                private int initialVolume = 0;
                private int initalBrightness = 0;
                private int initalPosition = 0;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getAction();

//                    long now = new Date().getTime();
//                    if (now - lastShow < 200L) return true;
//                    lastShow = now;

                    float eventX = event.getRawX();
                    float eventY = BoxsetterUtils.screenHeightL - event.getRawY();

                    if (action == MotionEvent.ACTION_DOWN){
                        this.lastX = this.firstX = eventX;
                        this.lastY = this.firstY = eventY;
                        this.initialVolume = BoxsetterUtils.getVolume();
                        this.initalBrightness = BoxsetterUtils.getBrightness();
                        this.initalPosition = getCurrentPosition();
                        return true; // allow other events like Click to be processed
                    } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {

                        float deltaX = eventX - firstX;
                        float deltaY = eventY - firstY;
                        float absDeltaX = Math.abs(deltaX);
                        float absDeltaY = Math.abs(deltaY);

                        if (firstX < BoxsetterUtils.screenWidthL && absDeltaX > MIN_DISTANCE) {
                            // horizontal swipe detection
                            int pos = (int) (initalPosition + 90000*deltaX/BoxsetterUtils.screenWidthL);
                            mMediaController.show();
                            seekTo(pos);
                            mMediaController.setProgress();
                        } else if (absDeltaY > MIN_DISTANCE) {
                            // vertical swipe detection
                            if (eventX > 3 * BoxsetterUtils.screenWidthL / 4) { // right hand quarter of screen
                                BoxsetterUtils.changeVolume(eventY, initialVolume, firstY);
                            } else if (eventX < 1 * BoxsetterUtils.screenWidthL / 4) { // left hand quarter of screen
                                BoxsetterUtils.changeBrightness(eventY, initalBrightness, firstY);
                            }
                        } else if (action != MotionEvent.ACTION_MOVE) {
                            // effectively a tap, since there was no move
                            if (mMediaController.isShowing()) {
                                mMediaController.hide();
                            } else {
                                mMediaController.show();
                            }
                        }

                        this.lastX = eventX;
                        this.lastY = eventY;
                    }
                    return false;
                }
            });

            try {
                mPlayer = new MediaPlayer();
                mMediaController = new VideoControllerView(this);
                mMediaController.setAnchorView((FrameLayout) findViewById(R.id.videoSurfaceContainer));
                mPlayer.setDataSource(this, Uri.parse(videoUrl));
                mPlayer.setOnPreparedListener(this);
            } catch (IOException e) {
                e.printStackTrace();
            }

            setAspectRatio(videoSurface, true);
        } else {
            super.finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stop();
    }

    private void setAspectRatio(View v, boolean is16x9) {
        int aspectX = (is16x9 ? 16 : 4), aspectY = (is16x9 ? 9 : 3);
        int height = BoxsetterUtils.screenHeightL;
        int width = (height * aspectX) / aspectY;

        this.is16x9 = is16x9;

        v.getLayoutParams().width = width;
        v.getLayoutParams().height = height;
    }

    // Implement SurfaceHolder.Callback
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d("BSBVA", "Surface changed");
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mPlayer != null) {
            mPlayer.setDisplay(holder);
            mPlayer.prepareAsync();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mPlayer != null) stop();
    }
    // End SurfaceHolder.Callback

    // Implement VideoMediaController.MediaPlayerControl
    @Override
    public boolean canPause() {
        return true;
    }

    @Override
    public boolean canSeekBackward() {
        return true;
    }

    @Override
    public boolean canSeekForward() {
        return true;
    }

    @Override
    public int getBufferPercentage() {
        int duration = getDuration();
        return duration == 0 ? 0 : (getCurrentPosition() * 100) / duration;
    }

    @Override
    public int getCurrentPosition() {
        return mPlayer == null ? 0 : mPlayer.getCurrentPosition();
    }

    @Override
    public int getDuration() {
        return mPlayer == null ? 0 : mPlayer.getDuration();
    }

    @Override
    public boolean isPlaying() {
        return mPlayer != null && mPlayer.isPlaying();
    }

    @Override
    public void pause() {
        updatePlayPosition(-1L);
        if (mPlayer != null) mPlayer.pause();
    }

    private void stop() {
        updatePlayPosition(-1L);
        if (mPlayer != null) {
            mPlayer.stop();
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public void seekTo(int i) {
        int pos = Math.max(0, Math.min(i, getDuration()));
        mPlayer.seekTo(pos);
        updatePlayPosition(pos);
    }

    @Override
    public void start() {
        mPlayer.start();
        mPlayer.seekTo((int) be.getPosition());
    }

    @Override
    public boolean isFullScreen() {
        return true;
    }

    @Override
    public void toggleFullScreen() {
        setAspectRatio(findViewById(R.id.surfaceView), !is16x9);
        mMediaController.updateFullScreen();
    }
    // End VideoMediaController.MediaPlayerControl

    // Implement MediaPlayer.OnPreparedListener
    @Override
    public void onPrepared(MediaPlayer mp) {
        mMediaController.setMediaPlayer(this);
        mMediaController.setAnchorView((FrameLayout) findViewById(R.id.videoSurfaceContainer));
        start();
    }
    // End MediaPlayer.OnPreparedListener

    private void updatePlayPosition(long position) {
        if (mPlayer != null) {
            if (position == -1L) position = mPlayer.getCurrentPosition();
            mMediaController.updateFullScreen();
            be.setPosition(position);
        }
    }

}

