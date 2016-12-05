package com.boxsetter;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.connectsdk.device.ConnectableDevice;
import com.connectsdk.device.ConnectableDeviceListener;
import com.connectsdk.device.DevicePicker;
import com.connectsdk.discovery.CapabilityFilter;
import com.connectsdk.discovery.DiscoveryManager;
import com.connectsdk.discovery.DiscoveryManagerListener;
import com.connectsdk.service.DeviceService;
import com.connectsdk.service.capability.MediaControl;
import com.connectsdk.service.capability.MediaPlayer;
import com.connectsdk.service.command.ServiceCommandError;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.SimpleWebServer;


public class BoxsetterActivity extends AppCompatActivity implements BroadcastEntityListener, DiscoveryManagerListener {

    public static String BASE_BOXSET_URL = "https://boxsetter.gomes.com.es/boxsets.json";

    public static final String ENTITY_URL = "ENTITY-URL";
    public static final String ENTITY_SUBTITLE = "ENTITY-SUBTITLE";
    public static final String VIDEO_URL = "VIDEO-URL";
    private static final String LAST_DEVICE_ID = "LAST-DEVICE-ID";
    protected static final String FORCE_REFRESH = "FORCE-REFRESH";
    protected static final String ENTITY_QUERY = "ENTITY-QUERY";
    private static final String LAST_DOWNLOADED = "LAST-DOWNLOADED";

    private static final int[] tabids = {R.id.boxsetsTab, R.id.moviesTab, R.id.downloadsTab};

    protected static DiscoveryManager mDiscoveryManager;
    protected static ConnectableDevice mDevice;
    protected static final Map<String, Integer> resourceIds = new HashMap<String, Integer>();
    protected static MediaControl.PlayStateStatus mMediaPlayerPlayState = MediaControl.PlayStateStatus.Paused;
    private static BoxsetterReceiver boxsetterReceiver = new BoxsetterReceiver();

    protected static MediaControl mMediaControl;

    protected static String currentlyPlayingFrom;
    private static String lastDeviceId = "none";
    private final String CURRENT_TAB_ID = "CURRENT-TAB_ID";
    private Object mActionMode;
    private List<BroadcastEntity> bes;
    private String url;

    protected Menu menu;

    private AdapterView<ListAdapter> mView;
    private BroadcastEntityAdapter mAdapter;
    private int currentTabId;
    private Parcelable state = null;

    protected static BoxsetterWebServer mServer;

    public static class BoxsetterWebServer extends SimpleWebServer {
        private String uri;

        public BoxsetterWebServer(String ip, int port, BroadcastEntity be) {
            super(ip, port, be.local_location().getParentFile(), false);
            this.uri = be.getURI();
        }

        @Override
        public Response serve(IHTTPSession session) {
            String sessionUri = session.getUri();

            if (sessionUri.equals(this.uri)) {
                return super.serve(session);
            }

            return getInternalErrorResponse("Resource is not available: " + sessionUri);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (BoxsetterUtils.first(this)) BroadcastEntity.clearCache();

        DiscoveryManager.init(getApplicationContext());
        mDiscoveryManager = DiscoveryManager.getInstance();
        CapabilityFilter videoFilter = new CapabilityFilter(
            MediaPlayer.Play_Video
        );
        mDiscoveryManager.setCapabilityFilters(videoFilter);
        mDiscoveryManager.addListener(this);
        mDiscoveryManager.start();

        boxsetterReceiver.setActivity(this);
        registerReceiver(boxsetterReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        ActionBar actionBar = getSupportActionBar();
        actionBar.setLogo(R.mipmap.ic_launcher);
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayUseLogoEnabled(true);

        if (this.getClass() == BoxsetterActivity.class) { // needed for BoxsetterViewActivity, which extends this one
            Intent intent = getIntent();

            currentTabId = intent.getIntExtra(CURRENT_TAB_ID, R.id.boxsetsTab);

            String subtitle = intent.getStringExtra(ENTITY_SUBTITLE);
            Log.d("BSBA","Setting subtitle: " + subtitle);
            if (subtitle != null) {
                actionBar.setSubtitle(subtitle);
            }

            String query = intent.getStringExtra(SearchManager.QUERY);
            intent.removeExtra(SearchManager.QUERY);
            if ("".equals(BoxsetterUtils.getBoxsetterPass()) || "".equals(BoxsetterUtils.getBoxsetterUser())) {
                Intent i = new Intent(this, BoxsetterSettingsActivity.class);
                startActivity(i);
            } else if (query != null && BoxsetterUtils.networkAvailable()) {
                BroadcastEntity.clearCache();
                Intent i = new Intent(this, BoxsetterActivity.class);
                i.putExtra(ENTITY_QUERY, query);
                i.putExtra(CURRENT_TAB_ID, currentTabId);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            } else {
                query = intent.getStringExtra(ENTITY_QUERY);
                this.url = intent.getStringExtra(ENTITY_URL);
                if (this.url == null) {
                    this.url = BASE_BOXSET_URL;
                } else if (this.url != BASE_BOXSET_URL) {
                    enableHomeAsUp(actionBar);
                }

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
                long now = new Date().getTime();
                boolean force_on_time = (now - prefs.getLong(LAST_DOWNLOADED, 0) > 24 * 60 * 60 * 1000);
                String refresh_choice = intent.getStringExtra(FORCE_REFRESH);
                boolean force_on_choice = refresh_choice != null;
                boolean force_refresh = force_on_choice || force_on_time;
                intent.removeExtra(FORCE_REFRESH);
                intent.removeExtra(ENTITY_QUERY);
                List<BroadcastEntity> bes = (force_refresh ? null : BroadcastEntity.getBroadcastEntities(this.url));

                Log.d("BSBA","FoT: " + force_on_time + ", FoC: " + force_on_choice + ", Q: " + query + ", bes: " + bes);

                if (force_refresh || bes == null || bes.size() == 0 || query != null) {
                    setContentView(R.layout.activity_waiting);
                    prefs.edit().putLong(LAST_DOWNLOADED, now).apply();
                    if (query != null) {
                        boolean movie = (currentTabId == R.id.moviesTab);
                        Log.d("BSBA", "Searching for " + query + " with movie = " + movie);
                        BroadcastEntity.acquireBroadcastEntities(this.url, query, movie, this);
                    } else {
                        Log.d("BSBA", "Now loading entities for " + this.url);
                        BroadcastEntity.acquireBroadcastEntities(this.url, force_refresh, refresh_choice, this);
                    }
                } else {
                    onBroadcastEntitiesAcquired(this.url, bes);
                }
            }

        } else {
            Log.d("BSBA", "Bypassing onCreate for class " + this.getLocalClassName());
        }
    }

    protected void enableHomeAsUp(ActionBar actionBar) {
        actionBar.setHomeButtonEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);
    }

    @Override
    public void startActivity(Intent intent) {
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            intent.putExtra(CURRENT_TAB_ID, currentTabId);
        }
        super.startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(boxsetterReceiver);
        } catch (IllegalArgumentException e) {

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(boxsetterReceiver);
        } catch (IllegalArgumentException e) {

        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mView != null) {
            if (currentTabId == R.id.downloadsTab) this.bes = BroadcastEntity.getDownloadedEntities();
            mAdapter = new BroadcastEntityAdapter(this, this.bes);
            mView.setAdapter(mAdapter);
            if (state != null) {
                if (mView instanceof ListView) {
                    ((ListView) mView).onRestoreInstanceState(state);
                } else if (mView instanceof GridView){
                    ((GridView) mView).onRestoreInstanceState(state);
                }
            }
        }
        boxsetterReceiver.setActivity(this);
        registerReceiver(boxsetterReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        invalidateOptionsMenu();
    }

    public void onBroadcastEntitiesAcquired(String url, List<BroadcastEntity> bes) {
        switch (currentTabId) {
            case R.id.downloadsTab:
                this.bes = BroadcastEntity.getDownloadedEntities();
                break;

            case R.id.moviesTab:
                if (BASE_BOXSET_URL.equals(url)) { // we're at the top
                    for (BroadcastEntity be : bes) {
                        if (be.isMovie()) { // First movie child is the movie root
                            bes = be.getChildren();
                            if (bes.size() == 0) {
                                setContentView(R.layout.activity_waiting);
                                BroadcastEntity.acquireBroadcastEntities(be.getSource(), false, null, this);
                                return;
                            }
                            break;
                        }
                    }
                }
                //bleed into...

            default:
                boolean showingMovies = (currentTabId == R.id.moviesTab);
                this.bes = new ArrayList<BroadcastEntity>();
                for (BroadcastEntity be : bes) {
                    if (!be.isRescinded() && (be.isMovie() == showingMovies)) this.bes.add(be);
                }
                break;
        }

//        Collections.sort(this.bes);

        setContentView(R.layout.activity_main);

        for (int i = 0; i < tabids.length; i++) {
            final int tabid = tabids[i];
            TextView tv = (TextView)findViewById(tabid);
            if (currentTabId == tabid) {
                tv.setBackgroundColor(0x11ffffff);
//                tv.setElevation((1+1) * 10.0F); // requires min-API of 21; currently we're at 18
            } else {
//                tv.setElevation(100.0F); // requires min-API of 21; currently we're at 18
                tv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent i = new Intent(BoxsetterActivity.this, BoxsetterActivity.class);
                        i.putExtra(CURRENT_TAB_ID, tabid);
                        startActivity(i);
                        overridePendingTransition(0, 0);
                    }
                });
            }
        }

        mView = (AdapterView)findViewById(R.id.entityview);

        mAdapter = new BroadcastEntityAdapter(this, this.bes);
        mView.setAdapter(mAdapter);
        mView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mActionMode != null) {
                    toggleSelected(view, BoxsetterActivity.this.bes.get(position));
                } else {
                    saveViewState();
                    BroadcastEntity be = BoxsetterActivity.this.bes.get(position);
                    Intent intent = new Intent(BoxsetterActivity.this, be.isProgramme() ? BoxsetterViewActivity.class : BoxsetterActivity.class);
                    intent.putExtra(ENTITY_URL, be.getSource());
                    intent.putExtra(ENTITY_SUBTITLE, be.getActionbarTitle());
                    intent.putExtra(CURRENT_TAB_ID, currentTabId);
                    startActivity(intent);
                    overridePendingTransition(0, 0);
                }
            }
        });
        mView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> arg0, View view, int position, long id) {
                if (mActionMode != null) {
                    return false;
                }
                mActionMode = startSupportActionMode(mActionModeCallback);
                toggleSelected(view, BoxsetterActivity.this.bes.get(position));
                return true;
            }
        });
    }

    public String getUserName() {
        return BoxsetterUtils.getBoxsetterUser();
    }

    ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onActionItemClicked(final ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.action_delete:
                    checkForDelete(new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Toast.makeText(BoxsetterActivity.this, "Delete!", Toast.LENGTH_SHORT).show();
                            for (int i = 0; i < BoxsetterActivity.this.bes.size(); i++) {
                                BroadcastEntity be = BoxsetterActivity.this.bes.get(i);
                                if (be.isSelected()) {
                                    be.setSelected(false);
                                    be.killAndCascade();
                                }
                            }
                            mode.finish();
                            onBroadcastEntitiesAcquired(BoxsetterActivity.this.url, BoxsetterActivity.this.bes);
                        }
                    });
                    return true;
                case R.id.action_amalgamate:
                    List<String> ids = new ArrayList<String>();
                    for (int i = 0; i < BoxsetterActivity.this.bes.size(); i++) {
                        BroadcastEntity be = BoxsetterActivity.this.bes.get(i);
                        if (be.isSelected()) {
                            be.setSelected(false);
                            ids.add(be.getBeid());
                        }
                    }
                    if (ids.size() > 0) {
                        Toast.makeText(BoxsetterActivity.this, "Joining!", Toast.LENGTH_SHORT).show();
                        setContentView(R.layout.activity_waiting);
                        mode.finish();
                        BroadcastEntity.acquireBroadcastEntities(BoxsetterActivity.this.url, ids, BoxsetterActivity.this);
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.context_menu, menu);
            if ("season".equals(BoxsetterActivity.this.bes.get(1).getType())) {
                MenuItem item = menu.findItem(R.id.action_amalgamate);
                item.setVisible(true);
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode arg0) {
            clearSelection();
            mActionMode = null;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode arg0, Menu arg1) {
            return false;
        }
    };

    private void clearSelection() {
        AbsListView lv = (AbsListView)findViewById(R.id.entityview);
        if (lv != null) {
            lv.clearChoices();
            for (int i = 0; i < lv.getChildCount(); i++) {
                lv.getChildAt(i).setBackgroundColor(getResources().getColor(R.color.background_material_light));
                bes.get(i).setSelected(false);
            }
        }
    }

    private void toggleSelected(View view, BroadcastEntity be) {
        boolean state = be.toggleSelected();
        view.setSelected(state);
        view.setBackgroundColor(state ? Color.LTGRAY : getResources().getColor(R.color.background_material_light));
    }

    protected int getPlaceholderResourceId(String name) {
        Integer resId = resourceIds.get(name);
        if (resId == null) {
            resId = getResources().getIdentifier(name, "drawable", getPackageName());
            resourceIds.put(name, resId);
        }
        return (resId == 0 ? R.drawable.boxsetter2 : resId);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        this.menu = menu;

        getMenuInflater().inflate(R.menu.menu_main, menu);

        MenuItem item = menu.findItem(R.id.action_cast);
        item.setVisible(mDiscoveryManager != null && mDiscoveryManager.getCompatibleDevices().keySet().size() > 0);
        if (mDevice != null && mDevice.isConnected()) {
            item.setIcon(R.drawable.ic_action_cast_linked);
        }

        return true;
    }

    private void setLastDeviceId(String deviceId) {
        lastDeviceId = deviceId;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.edit().putString(LAST_DEVICE_ID, lastDeviceId).apply();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent i;

        switch (item.getItemId()) {
            case android.R.id.home:
                this.finish();
                return true;
            case R.id.action_cast:
                connectToCastableDevice();
                return true;
            case R.id.action_search:
                onSearchRequested();
                Log.d("BA", "onSearchRequested");
                return true;
            case R.id.action_update:
                return startUpdateActivity("update");
            case R.id.action_refresh:
                return startUpdateActivity("refresh");
            case R.id.action_settings:
                i = new Intent(this, BoxsetterSettingsActivity.class);
                startActivity(i);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private boolean startUpdateActivity(String refresh_choice) {
        BroadcastEntity.clearCache();
        Intent i = new Intent(this, BoxsetterActivity.class);
        i.putExtra(FORCE_REFRESH, refresh_choice);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        return true;
    }

    // Connect SDK stuff
    private void connectToCastableDevice() {
        AlertDialog dialog;
        if (mDevice != null && mDevice.isConnected()) {
            dialog = new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.AlertDialogCustom))
                    .setTitle(mDevice.getFriendlyName())
                    .setCancelable(true)
                    .setNeutralButton("Disconnect", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mDevice.disconnect();
                            mDevice = null;
                            setLastDeviceId("none");
                            invalidateOptionsMenu();
                        }
                    })
                    .create();
        } else {
            DevicePicker devicePicker = new DevicePicker(this);
            dialog = devicePicker.getPickerDialog("Connect", new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView adapter, View parent, int position, long id) {
                    if (mDevice != null && mDevice.isConnected()) mDevice.disconnect();
                    mDevice = (ConnectableDevice) adapter.getItemAtPosition(position);
                    mDevice.addListener(deviceListener);
                    mDevice.connect();
                }
            });
        }

        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    public void onVideoLocated(int status) {
        if (status == BroadcastEntity.HTTP_DISALLOWED) {
            Toast.makeText(this, "User disallowed from accessing video at this time", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Error: " + status, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDeviceAdded(DiscoveryManager discoveryManager, ConnectableDevice device) {
        if (lastDeviceId == null) lastDeviceId = PreferenceManager.getDefaultSharedPreferences(this).getString(LAST_DEVICE_ID, "none");
        if (device != mDevice && device.getId().equalsIgnoreCase(lastDeviceId)) {
            mDevice = device;
            mMediaControl = device.getMediaControl();
            mDevice.addListener(deviceListener);
            mDevice.connect();
            Log.d("BSBA", "REDISCOVERED: " + device.getFriendlyName() + ". Device count now " + mDiscoveryManager.getCompatibleDevices().keySet().size());
        } else {
            changeCastIconOnDeviceSetChange(mDiscoveryManager, device, 1);
            Log.d("BSBA", "DISCOVERED: " + device.getFriendlyName() + ". Device count now " + mDiscoveryManager.getCompatibleDevices().keySet().size());
        }
    }

    @Override
    public void onDeviceRemoved(DiscoveryManager discoveryManager, ConnectableDevice device) {
        if (mDevice == device) {
            mDevice = null;
            mMediaControl = null;
        }
        changeCastIconOnDeviceSetChange(mDiscoveryManager, device, 0);
    }

    private void changeCastIconOnDeviceSetChange(DiscoveryManager discoveryManager, ConnectableDevice connectableDevice, int threshold) {
        int numdevices = discoveryManager.getCompatibleDevices().keySet().size();
        if (numdevices == threshold) invalidateOptionsMenu();
        Log.d("BSBA", (threshold == 1 ? "ADDED:" : "REMOVED") + ": " + connectableDevice.getFriendlyName() + ". Device count now " + numdevices);
    }

    @Override
    public void onDeviceUpdated(DiscoveryManager discoveryManager, ConnectableDevice connectableDevice) {
    }

    @Override
    public void onDiscoveryFailed(DiscoveryManager discoveryManager, ServiceCommandError serviceCommandError) {

    }

    protected void checkForDelete(DialogInterface.OnClickListener listener) {
        new AlertDialog.Builder(this)
                .setTitle("DELETE")
                .setMessage("Are you sure?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.yes, listener)
                .setNegativeButton(android.R.string.no, null).show();
    }

    private ConnectableDeviceListener deviceListener = new ConnectableDeviceListener() {
        @Override
        public void onDeviceReady(ConnectableDevice connectableDevice) {
            if (mDevice == connectableDevice) {
                Log.d("BSBA", "Device " + mDevice.getFriendlyName() + " connected: " + mDevice.isConnected());
                setLastDeviceId(mDevice.getId());
                invalidateOptionsMenu();
            } else {
                Log.d("BSBA","Hm. Weird device claims it's ready: " + connectableDevice.getFriendlyName() + " (should be: " + mDevice.getFriendlyName() + ")");
            }
        }

        @Override
        public void onDeviceDisconnected(ConnectableDevice connectableDevice) {
        }

        @Override
        public void onPairingRequired(ConnectableDevice connectableDevice, DeviceService deviceService, DeviceService.PairingType pairingType) {

        }

        @Override
        public void onCapabilityUpdated(ConnectableDevice connectableDevice, List<String> strings, List<String> strings2) {

        }

        @Override
        public void onConnectionFailed(ConnectableDevice connectableDevice, ServiceCommandError serviceCommandError) {

        }
    };

    protected static void monitorDownload(Long did, BroadcastEntity be) {
        boxsetterReceiver.registerDownload(did, be);
    }

    protected void redraw(BroadcastEntity be) {
        if (be.isLocal()) {
            if (currentTabId == R.id.downloadsTab) {
                onBroadcastEntitiesAcquired(BASE_BOXSET_URL, null);
            } else {
                mAdapter.showDownloaded(be.getSource());
            }
        }
    }

    private void saveViewState() {
        if (mView != null) {
            if (mView instanceof ListView) {
                state = ((ListView)mView).onSaveInstanceState();
            } else if (mView instanceof GridView){
                state = ((GridView)mView).onSaveInstanceState();
            }
        }
    }

}
