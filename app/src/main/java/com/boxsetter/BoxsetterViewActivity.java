package com.boxsetter;

import android.app.SearchManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.AnimationDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.text.format.Formatter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.connectsdk.core.MediaInfo;
import com.connectsdk.device.ConnectableDevice;
import com.connectsdk.discovery.DiscoveryManager;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.capability.listeners.ResponseListener;
import com.connectsdk.service.command.ServiceCommandError;
import com.squareup.picasso.Picasso;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by nic.ford on 03/03/15.
 */
public class BoxsetterViewActivity extends BoxsetterActivity implements VideoResponder {

    public static final String CURRENT_BE_URL = "CURRENT-BE-URL";
    public static final int PORT = 30405;
    public static final long REVERSAL_LIMIT = 15000L;
    public static final long REVERSAL_START_VALUE = 600L;

    private BroadcastEntity be;
    private static ScheduledExecutorService playPosExecutor;
    private TextView playPositionTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String query = intent.getStringExtra(SearchManager.QUERY);
        if (query != null) {
            Intent i = new Intent(this, BoxsetterActivity.class);
            i.putExtra(ENTITY_QUERY, query);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } else {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            currentlyPlayingFrom = preferences.getString(CURRENT_BE_URL, "none");

            ActionBar actionBar = getSupportActionBar();
            enableHomeAsUp(actionBar);

            String beUrl = intent.getStringExtra(ENTITY_URL);
            if (beUrl == null) {
                beUrl = preferences.getString(ENTITY_URL, "none");
            } else {
                preferences.edit().putString(ENTITY_URL, beUrl).apply();
            }
            be = BroadcastEntity.getBroadcastEntity(beUrl);

            if (be.parent != null) actionBar.setSubtitle(be.parent.getActionbarTitle());

            setContentView(R.layout.activity_view);

            ((TextView) findViewById(R.id.prog_title)).setText(be.getTitle());
            ((TextView) findViewById(R.id.prog_notation)).setText(be.getAddenda());
            ((TextView) findViewById(R.id.prog_description)).setText(be.getDescription());

            ImageView imageView = (ImageView) findViewById(R.id.prog_img);
            Picasso.with(this).load(be.getImg()).placeholder(getPlaceholderResourceId(be.getChannel())).into(imageView);

            setUpPlayingState();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        invalidateOptionsMenu();
//        setUpPlayingState();
    }

    @Override
    protected void onPause() {
        stopShowingPlayPos();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopShowingPlayPos();
        if (mMediaPlayerPlayState == MediaControl.PlayStateStatus.Paused && currentlyPlayingFrom.equals(be.getSource())) {
            mMediaControl.stop(null);
            mMediaControl = null;
            setCurrentPlaying("none");
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        if (this.be != null) {
            MenuItem item = menu.findItem(R.id.action_delete);
            item.setVisible(be.isLocal());

            item = menu.findItem(R.id.action_download);
            Log.d("BSBVA","Downloading: " + be.isDownloading() + "; local: " + be.hasLocalFile());
            item.setVisible(!be.isDownloading() && !be.hasLocalFile());

            if (mDevice != null && mMediaControl != null && currentlyPlayingFrom.equals(be.getSource())) {
                if (!mDevice.isConnected()) mDevice.connect();
                findViewById(R.id.rewind_button).setVisibility(ImageView.VISIBLE);
                findViewById(R.id.fast_forward_button).setVisibility(ImageView.VISIBLE);
                ((ImageView) findViewById(R.id.play_pause_button)).setImageResource(mMediaPlayerPlayState == MediaControl.PlayStateStatus.Paused ? R.drawable.ic_action_play : R.drawable.ic_action_pause);
            } else {
                findViewById(R.id.rewind_button).setVisibility(ImageView.INVISIBLE);
                findViewById(R.id.fast_forward_button).setVisibility(ImageView.INVISIBLE);
                ((ImageView) findViewById(R.id.play_pause_button)).setImageResource(R.drawable.ic_action_play);

                TextView tv = (TextView)findViewById(R.id.playPosition);
                if (tv != null) tv.setText("");

                if (currentlyPlayingFrom.equals(be.getSource())) mMediaPlayerPlayState = MediaControl.PlayStateStatus.Paused;
            }
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_delete:
                checkForDelete(new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Toast.makeText(BoxsetterViewActivity.this, "Deleting downloaded video.", Toast.LENGTH_SHORT).show();
                        be.local_location().delete();
                        be.removeFromDownloadedEntities();
                        ((ImageView) findViewById(R.id.downloaded)).setVisibility(ImageView.INVISIBLE);
                        invalidateOptionsMenu();
                    }
                });
                return true;
            case R.id.action_download:
                Toast.makeText(BoxsetterViewActivity.this, "Downloading '" + be.getFilename() + "'.", Toast.LENGTH_SHORT).show();
                be.ensureDownloaded(this);
                invalidateOptionsMenu();
                return true;
            case R.id.action_search:
                onSearchRequested();
                Log.d("BVA","onSearchRequested");
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void playPauseVideo(View view) {
        if (mDevice != null && mMediaControl != null && currentlyPlayingFrom.equals(be.getSource())) {
            if (!mDevice.isConnected()) mDevice.connect();
            mMediaControl.getPlayState(new MediaControl.PlayStateListener() {
                @Override
                public void onSuccess(MediaControl.PlayStateStatus playStateStatus) {
                    if (playStateStatus == MediaControl.PlayStateStatus.Playing) {
                        mMediaControl.pause(null);
                        mMediaPlayerPlayState = MediaControl.PlayStateStatus.Paused;
                        stopShowingPlayPos();
                    } else {
                        mMediaControl.play(null);
                        mMediaPlayerPlayState = MediaControl.PlayStateStatus.Playing;
                        startShowingPlayPos();
                    }
                    invalidateOptionsMenu();
                }

                @Override
                public void onError(ServiceCommandError serviceCommandError) {
                    Log.d("BSBVA","Error: " + serviceCommandError.toString());
                }
            });
        } else {
            ImageView loadingAnimView = (ImageView) findViewById(R.id.play_pause_button);
            loadingAnimView.setImageResource(R.drawable.loading);
            AnimationDrawable anim = (AnimationDrawable)loadingAnimView.getDrawable();
            anim.start();

            if (mMediaControl != null) {
                mMediaControl.stop(null);
                mMediaControl = null;
            }

            if (be.isLocal()) {
                if (mDevice != null) {
                    if (!mDevice.isConnected()) mDevice.connect();
                    WifiManager wm = (WifiManager)getSystemService(WIFI_SERVICE);
                    String ip = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());

                    try {
                        if (mServer != null) mServer.stop();

                        mServer = new BoxsetterWebServer(ip, PORT, be);
                        mServer.start();

                        String url = "http://" + ip + ":" + PORT + be.getURI().replace(' ', '+');
                        Log.d("BSBVA", "Looking for url " + url);
                        onVideoLocated(url, be.getPosition());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    onVideoLocated(be.local_location().getAbsolutePath(), be.getPosition());
                }
            } else {
                be.locateRemoteVideo(this);
            }
        }
    }

    public void onVideoLocated(String url, Long position) {
        ImageView loadingAnimView = (ImageView) findViewById(R.id.play_pause_button);
        AnimationDrawable anim = (AnimationDrawable)loadingAnimView.getDrawable();
        anim.stop();
        loadingAnimView.setImageResource(R.drawable.ic_action_pause);

        be.setPosition(position);

        if (mDevice != null && mDevice.isConnected()) {
            if (mMediaControl != null) {
                mMediaControl.stop(null);
                mMediaControl = null;
            }

            setCurrentPlaying(be.getSource());

            MediaInfo mediaInfo = new MediaInfo.Builder(url, "video/avc")
                    .setTitle(be.getTitle())
                    .setDescription(be.getDescription())
                    .setIcon(be.getImg())
                    .build();

            MediaPlayer mediaPlayer = mDevice.getCapability(MediaPlayer.class);
            mediaPlayer.playMedia(mediaInfo, false,
                    new MediaPlayer.LaunchListener() {
                        @Override
                        public void onSuccess(MediaPlayer.MediaLaunchObject object) {
                            mMediaControl = object.mediaControl;
                            mMediaPlayerPlayState = MediaControl.PlayStateStatus.Playing;
                            startShowingPlayPos();
                            invalidateOptionsMenu();
                            if (be.getPosition() > 0L) {
                                mMediaControl.seek(be.getPosition(), new ResponseListener<Object>() {
                                    @Override
                                    public void onSuccess(Object o) {
                                    }

                                    @Override
                                    public void onError(ServiceCommandError serviceCommandError) {
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(ServiceCommandError error) {
                            Log.d("BSBVA", "Display video failure: " + error);
                        }
                    }
            );
        } else {
            Intent videoIntent = new Intent(this, BoxsetterVideoActivity.class);
            videoIntent.putExtra(ENTITY_URL, this.be.getSource());
            videoIntent.putExtra(VIDEO_URL, url);
            startActivity(videoIntent);
        }
    }

    private void setCurrentPlaying(String source) {
        if (!source.equals(currentlyPlayingFrom)) {
            currentlyPlayingFrom = source;
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            preferences.edit().putString(CURRENT_BE_URL, currentlyPlayingFrom).apply();
        }
    }

    private void startShowingPlayPos() {
        if (mMediaControl != null && playPosExecutor == null) {
            playPositionTextView = (TextView)findViewById(R.id.playPosition);
            playPosExecutor = Executors.newScheduledThreadPool(1);
            playPosExecutor.scheduleAtFixedRate(playPosUpdate, 0, 1, TimeUnit.SECONDS);
        }
    }

    private void stopShowingPlayPos() {
        if (playPosExecutor != null) {
            playPosExecutor.shutdown();
            playPosExecutor = null;
            playPositionTextView = (TextView)findViewById(R.id.playPosition);
            if (playPositionTextView != null) playPositionTextView.setText("");
            be.savePosition(0L);
        }
    }

    Runnable playPosUpdate = new Runnable() {
         public void run() {
            if (mMediaControl != null) mMediaControl.getPosition(posListener);
        }
    };

    MediaControl.PositionListener posListener = new MediaControl.PositionListener() {

        public void onSuccess(Long position) {
            Long time = position / 1000L;

            playPositionTextView = (TextView)BoxsetterViewActivity.this.findViewById(R.id.playPosition);
            if (playPositionTextView != null) {
                Long secs = time % 60L;
                Long mins = (time / 60L) % 60L;
                Long hours = (time / 3600L);
                String timestamp;
                if (hours > 0) {
                    timestamp = String.format("%d:%02d:%02d", hours, mins, secs);
                } else {
                    timestamp = String.format("%02d:%02d", mins, secs);
                }
                playPositionTextView.setText(timestamp);
            }

            if (time % 10 == 0) {
                be.savePosition(position);
            } else {
                be.setPosition(position);
            }
        }

        @Override
        public void onError(ServiceCommandError serviceCommandError) {

        }
    };

    private enum JumpDir {
        BACKWARD (-1000L),
        NOWHERE (0L),
        FORWARD (1000L);

        private long val;

        private JumpDir(long val) {
            this.val = val;
        }

        private long value() { return val; }
    }

    private JumpDir lastJumpDir = JumpDir.NOWHERE;
    private Long delta = REVERSAL_START_VALUE;
    private Long lastJump = new Date().getTime();

    public void rewind(View view) {
        jump(JumpDir.BACKWARD);
    }

    public void fastForward(View view) {
        jump(JumpDir.FORWARD);
    }

    private void jump(final JumpDir jumpDir) {
        long time = new Date().getTime();
        if (time > lastJump + REVERSAL_LIMIT) {
            delta = REVERSAL_START_VALUE; // reset
            lastJumpDir = JumpDir.NOWHERE;
        }
        lastJump = time;

        if (mMediaControl != null && mDevice != null && mDevice.isConnected()) {

            mMediaControl.getPosition(new MediaControl.PositionListener() {
                @Override
                public void onSuccess(Long pos) {
                    if (lastJumpDir != jumpDir) {
                        lastJumpDir = jumpDir;
                        if (delta > 3L) delta /= 3L;
                    }
                    pos += delta * jumpDir.value();
                    if (pos < 0L) pos = 0L;
                    final Long cPos = pos;

                    mMediaControl.seek(pos, new ResponseListener<Object>() {
                        @Override
                        public void onSuccess(Object o) {
                            be.savePosition(cPos);
                        }

                        @Override
                        public void onError(ServiceCommandError serviceCommandError) {

                        }
                    });
                }

                @Override
                public void onError(ServiceCommandError serviceCommandError) {

                }
            });
        }
    }

    @Override
    public void onDeviceAdded(DiscoveryManager discoveryManager, ConnectableDevice device) {
        ConnectableDevice origDevice = mDevice;

        super.onDeviceAdded(discoveryManager, device);

        if (mDevice != origDevice) setUpPlayingState(); // we've just found the device, or it's changed
    }

    private void setUpPlayingState() {
        mMediaPlayerPlayState = MediaControl.PlayStateStatus.Paused;

        findViewById(R.id.downloaded).setVisibility(be.isLocal() ? ImageView.VISIBLE : ImageView.INVISIBLE);

        if (mDevice != null) {
            if (!mDevice.isConnected()) mDevice.connect();
            mMediaControl = mDevice.getMediaControl();

            Log.d("BSBVA", "Cur: " + currentlyPlayingFrom + " vs " + be.getSource());
            if (currentlyPlayingFrom.equals(be.getSource())) {
                mMediaControl.getPlayState(new MediaControl.PlayStateListener() {
                    @Override
                    public void onSuccess(MediaControl.PlayStateStatus playStateStatus) {
                        Log.d("BSBVA","Found currently playing video: " + playStateStatus);
                        mMediaPlayerPlayState = playStateStatus;
                        if (mMediaPlayerPlayState == MediaControl.PlayStateStatus.Playing) {
                            startShowingPlayPos();
                        }
                        invalidateOptionsMenu();
                    }

                    @Override
                    public void onError(ServiceCommandError serviceCommandError) {
                        Log.d("BSBVA","Error: " + serviceCommandError.toString());
                        mMediaPlayerPlayState = MediaControl.PlayStateStatus.Paused;
                        setCurrentPlaying("none");
                        invalidateOptionsMenu();
                    }
                });
            } else {
                invalidateOptionsMenu();
            }
        } else {
            invalidateOptionsMenu();
        }
    }

    @Override
    protected void redraw(BroadcastEntity be) {
        if (be.getSource() == this.be.getSource()) findViewById(R.id.downloaded).setVisibility(View.VISIBLE);
    }

}

