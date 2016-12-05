package com.boxsetter;

import android.app.DownloadManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.orm.SugarRecord;
import com.orm.dsl.Ignore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by nic.ford on 12/02/15.
 */
public class BroadcastEntity extends SugarRecord implements Comparable<BroadcastEntity> {

    public String img;
    public String title;
    public String hierarchical_title;
    public String addenda;
    public String source;
    public String filename;
    public String description;
    public String beid;
    public String betype;
    public String boxsetPrefix;
    public String channelIdentifier;
    public String parentSource;
    public String userName;
    public Long position = 0L;
    public Long viewedAt = 0L;
    public Long downloadId = -1L;
    public Boolean movie = false;
    public Boolean dirtyPosition = false;
    private Integer duration = 0;
    public Long updatedAt = 0L;

    @Ignore
    public boolean rescinded = false;
    @Ignore
    private boolean selected = false;
    @Ignore
    private File local_location = null;
    @Ignore
    private List<BroadcastEntity> children;
    @Ignore
    public BroadcastEntity parent;

    private static Map<String, Map<String, BroadcastEntity>> entityCache = new HashMap<String, Map<String, BroadcastEntity>>();
    public static final int HTTP_OK = 200;
    public static final int HTTP_DISALLOWED = 403;
    private static List<BroadcastEntity> downloadedEntities;
    private static Timer savePositionTimer = null;
    private static final HashMap<Long, BroadcastEntity> pending_position_updates = new HashMap<Long, BroadcastEntity>();
    private static final List<String> pending_broadcast_entity_urls = new ArrayList<>();
    private static Timer loadUrlsTimer;

    public BroadcastEntity(JSONObject j, String url, long updateTime) {
        initialise(j, url, updateTime);
    }

    public BroadcastEntity() {
    }

    public BroadcastEntity(String source) {
        this.source = source;
    }

    private void initialise(JSONObject j, String parentSource, long updateTime) {
        try {
            this.img = j.getString("image");
            this.title = j.getString("title");
            this.hierarchical_title = j.getString("hierarchical_title");
            this.addenda = j.getString("addenda");
            this.source = j.getString("source");
            this.filename = j.getString("filename");
            this.description = j.getString("description");
            this.beid = j.getString("id");
            this.betype = j.getString("type");
            this.boxsetPrefix = j.getString("boxsetprefix");
            this.channelIdentifier = j.getString("channel");
            this.movie = j.getBoolean("movie");
            this.parentSource = parentSource;
            this.duration = j.getInt("duration");

            this.userName = BoxsetterUtils.getBoxsetterUser();
            this.updatedAt = updateTime;

            Long viewed_at = j.getLong("viewed_at");
            Long position = j.getLong("position");
            if (this.position != position) {
                if (this.viewedAt < viewed_at) {
                    this.position = position;
                    this.viewedAt = viewed_at;
                } else {
                    savePosition(this.position);
                }
            }

            this.save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public String getAddenda() {
        return addenda;
    }

    public String getTitle() {
        return title;
    }

    public String getImg() {
        return img;
    }

    public String getSource() { return source; }

    public String getActionbarTitle() {
        return generateActionbarTitle(new StringBuilder()).toString();
    }

    private StringBuilder generateActionbarTitle(StringBuilder sb) {
        if (parent != null && parent.hierarchical_title != null) parent.generateActionbarTitle(sb).append(" > ");
        sb.append(hierarchical_title);
        return sb;
    }

    public String getDescription() {
        return description;
    }

    public boolean toggleSelected() {
        return (this.selected = !this.selected);
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean isSelected() {
        return selected;
    }

    public String getBeid() {
        if (isProgramme()) {
            return "P" + beid;
        }
        return beid;
    }

    public void setType(String type) {
        this.betype = type;
        this.save();
    }

    public String getType() {
        return betype;
    }

    public String getFilename() {
        return filename;
    }

    public boolean isProgramme() {
        return "programme".equals(betype);
    }

    public boolean hasLocalFile() {
        File file = local_location();
        return file != null && file.exists();
    }

    public boolean isDownloading() {
        if (hasDownloaded()) this.downloadId = -1L;
        return this.downloadId > -1L;
    }

    public void setDownloadId(long downloadId) {
        Log.d("BSBE", "Set downloadId for " + getFilename() + "@" + this.toString() + " to " + downloadId);
        this.downloadId = downloadId;
        this.save();
    }

    public boolean isLocal() {
        return !isDownloading() && isProgramme() && hasLocalFile();
    }

    public List<BroadcastEntity> getChildren() {
        if (children == null) {
            Log.d("BSBE", "Looking for entity for " + this.source);
            List<BroadcastEntity> bes = BroadcastEntity.find(BroadcastEntity.class, "parent_source = ? and user_name = ?", this.source, BoxsetterUtils.getBoxsetterUser());
            Log.d("BSBE", "Found " + bes.size() + " items");
            children = new ArrayList<BroadcastEntity>();
            for (BroadcastEntity be : bes) {
                BroadcastEntity.setBroadcastEntity(be.getSource(), be);
                children.add(be);
                be.parent = this;
            }
        }
        return children;
    }

    public void setChildren(List<BroadcastEntity> children) {
        this.children = children;
        for (BroadcastEntity child : children) {
            child.parent = this;
        }
    }

    public void killAndCascade() {
        this.deleteSavedFiles();
        new RemoteControlBroadcastEntity(source).execute("delete", "now");
    }

    private void deleteSavedFiles() {
        this.rescinded = true;
        this.save();
        if (isProgramme() && isLocal()) {
            local_location().delete();
            removeFromDownloadedEntities();
        } else {
            for (BroadcastEntity be : this.getChildren()) {
                be.deleteSavedFiles();
            }
        }
    }

    public synchronized File local_location() {
        if (this.local_location == null) {
            this.local_location = BoxsetterUtils.locateFile(filename + ".mp4");
        }
        return this.local_location;
    }

    public File local_modified_location() {
        if ("Sony".equals(Build.MANUFACTURER)) return new File(BoxsetterUtils.getMainDir().getAbsolutePath(), filename + ".mp4");
        return local_location();
    }

    public void ensureDownloaded(final BoxsetterActivity ba) {
        if (BoxsetterUtils.networkAvailable() && !isDownloading()) {
            File file = local_location();

            if (null != file && !file.exists()) {
                new RetrieveRemoteLocation(
                    new VideoResponder() {
                        @Override
                        public void onVideoLocated(String url, Long position) {
                            File file = local_modified_location();

                            if (file != null && !file.exists()) { // check again
                                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                                request.setDescription(addenda);
                                request.setTitle(title);
                                request.setVisibleInDownloadsUi(true);
                                request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                                    request.allowScanningByMediaScanner();
                                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                                }
                                request.setDestinationUri(Uri.fromFile(file));

                                long downloadId = BoxsetterUtils.getDownloadManager().enqueue(request);
                                BroadcastEntity.this.setDownloadId(downloadId);
                                BoxsetterActivity.monitorDownload(downloadId, BroadcastEntity.this);
                            }
                        }

                        @Override
                        public void onVideoLocated(int status) {
                            ba.onVideoLocated(status);
                        }
                    }
                ).execute();
            }
        }
    }

    public void locateRemoteVideo(final BoxsetterViewActivity bva) {
        new RetrieveRemoteLocation(bva).execute();
    }

    public long getPosition() {
        return this.position;
    }

    public boolean isRescinded() {
        return this.rescinded;
    }

    public String getChannel() {
        return channelIdentifier;
    }

    public long posSavedAt() {
        return this.viewedAt;
    }

    @Override
    public int compareTo(BroadcastEntity other) {
        Log.d("BSBE","this " + this.betype + " vs " + other.betype);
        if (isProgramme()) return this.getFilename().compareTo(other.getFilename());
        return this.getTitle().compareTo(other.getTitle());
    }

    public boolean isMovie() {
        return this.movie;
    }

    public boolean hasDownloaded() {
        boolean status = false;

        if (this.downloadId > -1L) {
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(this.downloadId);
            Cursor c = BoxsetterUtils.getDownloadManager().query(query);
            if (c.moveToFirst()) {
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int download_status = c.getInt(columnIndex);
                Log.d("BSBE", this.title + " downloaded with status " + download_status);

                if (download_status == DownloadManager.STATUS_SUCCESSFUL || download_status == DownloadManager.STATUS_FAILED) this.downloadId = -1L;

                if (download_status == DownloadManager.STATUS_SUCCESSFUL) {
                    File file = BoxsetterUtils.locateFile(filename + ".mp4");
                    File bestFile = BoxsetterUtils.locateBestLocation(filename + ".mp4");
                    if (bestFile != null && !bestFile.getAbsolutePath().equals(file.getAbsolutePath())) {
                        moveFile(file, bestFile);
                        this.local_location = bestFile;
                    } else {
                        this.local_location = file;
                    }
                }

                status = (download_status == DownloadManager.STATUS_SUCCESSFUL);
            }
            c.close();
        }

        return status;
    }

    private void moveFile(final File file, final File bestFile) {
        new AsyncTask<File, File, Void>() {
            @Override
            protected Void doInBackground(File... params) {
                try {
                    InputStream in = new FileInputStream(file);
                    OutputStream out = new FileOutputStream(bestFile);

                    byte[] buf = new byte[40960];
                    while (true) {
                        int len = in.read(buf);
                        if (len <= 0) break;
                        out.write(buf, 0, len);
                    }
                    out.flush();
                    out.close();
                    in.close();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                BroadcastEntity.this.local_location = bestFile;
                file.delete();

                return null;
            }

        }.execute();
    }

    private class RetrieveRemoteLocation extends AsyncTask<String, Void, String> {
        private VideoResponder vr;

        public RetrieveRemoteLocation(VideoResponder vr) {
            this.vr = vr;
        }

        @Override
        protected String doInBackground(String... params) {
            String url = source + BoxsetterUtils.getQueryString("format", "json");
            return BroadcastEntity.readFromInterwebs(url);
        }

        protected void onPostExecute(String json) {
            try {
                JSONObject jsonObject = new JSONObject(json);
                int status = jsonObject.getInt("status");
                if (status == HTTP_OK) {
                    vr.onVideoLocated(jsonObject.getString("destination"), jsonObject.getLong("position"));
                } else {
                    vr.onVideoLocated(status);
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    public void setPosition(Long position) {
        if (position > 0L && this.position != position && position <= duration*1000L) {
            this.position = position;
            this.viewedAt = new Date().getTime();
            this.dirtyPosition = true;
            this.save();
        }
    }

    public void savePosition(long position) {
        setPosition(position);
        if (this.dirtyPosition) sendPositions(Arrays.asList(this));
    }

    public static void sendPositions(List<BroadcastEntity> bes) {
        for (BroadcastEntity be : bes) {
            pending_position_updates.put(be.getId(), be);
        }
        sendPositions();
    }



    public static synchronized void sendPositions() {
        if (savePositionTimer == null) {
            savePositionTimer = new Timer();
        } else {
            savePositionTimer.purge();
        }

        savePositionTimer.schedule(new TimerTask() {
            @Override
            public synchronized void run() {
                if (pending_position_updates.size() > 0 && BoxsetterUtils.networkAvailable()) {
                    for (Long id : pending_position_updates.keySet()) {
                        final BroadcastEntity be = pending_position_updates.remove(id);
                        if (be.dirtyPosition) {
                            new RemoteControlBroadcastEntity(be.source) {
                                @Override
                                protected void onPostExecute(String jsonstr) {
                                    try {
                                        JSONObject json = new JSONObject(jsonstr);
                                        if (json.getInt("status") == HTTP_OK) {
                                            long viewed_at = json.getLong("viewed_at");
                                            if (viewed_at == be.viewedAt) {
                                                Log.d("BSBE", "Saved position for " + be.getFilename() + " as " + be.getPosition());
                                            } else {
                                                be.position = json.getLong("position");
                                                be.viewedAt = viewed_at;
                                                Log.d("BSBE", "Reset position for " + be.getFilename() + " as " + be.getPosition());
                                            }
                                            be.dirtyPosition = false;
                                            be.save();
                                        } else {
                                            throw new JSONException("");
                                        }
                                    } catch (JSONException e) {
                                        pending_position_updates.put(be.getId(), be); // add it back in if it didn't work
                                    } finally {
                                        sendPositions();
                                    }
                                }
                            }.execute("position", be.position.toString(), "viewedat", be.viewedAt.toString());
                            break;
                        }
                    }
                }
            }
        }, 1000);
    }

    private static class RemoteControlBroadcastEntity extends AsyncTask<String, Void, String> {
        private final String url;

        public RemoteControlBroadcastEntity(String url) {
            this.url = url;
        }

        @Override
        protected String doInBackground(String... params) {
            String url = this.url + BoxsetterUtils.getQueryString(params);
            return BroadcastEntity.readFromInterwebs(url);
        }
    }

    public String getURI() {
        return "/" + filename + ".mp4";
    }

    public static void acquireBroadcastEntities(String url, String query, boolean movie, BroadcastEntityListener bel) {
        new DownloadBroadcastEntities(bel, url, query, movie).execute();
    }

    public static void acquireBroadcastEntities(String url, final boolean force_refresh, final String refresh_choice, BroadcastEntityListener bel) {
        new DownloadBroadcastEntities(bel, url, force_refresh, refresh_choice).execute();

        if (loadUrlsTimer == null) loadUrlsTimer = new Timer();
        loadUrlsTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (pending_broadcast_entity_urls.size() > 0) {
                    acquireBroadcastEntities(pending_broadcast_entity_urls.remove(0), force_refresh, refresh_choice, new BroadcastEntityListener() {
                        @Override
                        public void onBroadcastEntitiesAcquired(String url, List<BroadcastEntity> bes) {
                            // do nothing
                        }
                    });
                }
            }
        }, 50);

    }

    public static void acquireBroadcastEntities(String url, List<String> ids, BroadcastEntityListener bel) {
        new DownloadBroadcastEntities(bel, url, ids).execute();
    }

    private static class DownloadBroadcastEntities extends AsyncTask<String, Void, List<BroadcastEntity>> {
        private final BroadcastEntityListener bel;
        private String url;
        private List<String> ids = null;
        private String query = null;
        private String refresh_choice = "refresh";
        private boolean force_refresh = false;
        private boolean movie = false;
        private long updateTime = 0;
        private int page = 0;

        public DownloadBroadcastEntities(BroadcastEntityListener bel, String url) {
            this.bel = bel;
            this.url = url;
            this.updateTime = new Date().getTime();
        }

        public DownloadBroadcastEntities(BroadcastEntityListener bel, String url, String query, boolean movie) {
            this(bel, url);
            this.query = query;
            this.movie = movie;
        }

        public DownloadBroadcastEntities(BroadcastEntityListener bel, String url, String query, boolean movie, int page) {
            this(bel, url);
            this.query = query;
            this.movie = movie;
            this.page = page;
        }

        public DownloadBroadcastEntities(BroadcastEntityListener bel, String url, List<String> ids) {
            this(bel, url);
            this.ids = ids;
        }

        public DownloadBroadcastEntities(BroadcastEntityListener bel, String url, boolean force_refresh, String refresh_choice) {
            this(bel, url);
            this.force_refresh = force_refresh;
            if (refresh_choice != null) this.refresh_choice = refresh_choice;
        }

        protected List<BroadcastEntity> doInBackground(String... urls) {
            String url = this.url; //BoxsetterActivity.BASE_BOXSET_URL;

            if (force_refresh) {
                url += BoxsetterUtils.getQueryString("refresh", this.refresh_choice);
            } else if (query != null) {
                url += BoxsetterUtils.getQueryString("search", query, "page", Integer.toString(page), "type", movie ? "movie" : "boxset");
            } else if (ids != null) {
                url += BoxsetterUtils.getQueryString("join", TextUtils.join(",", ids));
            } else {
                url += BoxsetterUtils.getQueryString();
            }

            long start = new Date().getTime();
            String jsonstr = readFromInterwebs(url);
            long middle = new Date().getTime();
            List<BroadcastEntity> entitiesFromJSON = createEntitiesFromJSON(jsonstr);
            long end = new Date().getTime();
            Log.d("BE", "URL: " + url);
            Log.d("BE", "      Read: " + (middle - start) + "ms; parse json: " + (end - middle) + "ms; total: " + (end - start) + "ms");

            return entitiesFromJSON;
        }

        protected void onPostExecute(List<BroadcastEntity> bes) {
            Log.d("BSBE", "BES generated: " + bes);
            if (bes != null) BroadcastEntity.purge(this.updateTime);
            bel.onBroadcastEntitiesAcquired(this.url, BroadcastEntity.getBroadcastEntities(this.url));
        }

        private List<BroadcastEntity> createEntitiesFromJSON(String jsonstr) {
            List<BroadcastEntity> bes = null;
            try {
                JSONObject json = new JSONObject(jsonstr);
                if (json.getInt("status") == HTTP_OK) {
                    Log.d("BSBE", "JSON for " + json.getString("source"));
                    JSONArray objects = json.getJSONArray("objects");
                    if (objects.length() > 0) {
                        bes = createEntitiesFromJSONArray(this.url, objects);
                        if (page == 0) {
                            BroadcastEntity.setBroadcastEntities(this.url, bes);
                        } else {
                            BroadcastEntity.addBroadcastEntities(this.url, bes);
                        }
                        if (json.has("more") && json.getBoolean("more")) {
                            new DownloadBroadcastEntities(bel, url, query, movie, page + 1).execute();
                        }
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return bes;
        }

        private List<BroadcastEntity> createEntitiesFromJSONArray(String parentUrl, JSONArray beJSON) throws JSONException {
            return createEntitiesFromJSONArray(parentUrl, beJSON, new Date().getTime());
        }

        private List<BroadcastEntity> createEntitiesFromJSONArray(String parentUrl, JSONArray beJSON, long receivedAt) throws JSONException {
            for (BroadcastEntity be : getBroadcastEntities(parentUrl)) {
                be.updatedAt = this.updateTime; // effectively the preUpdateTime
            }
            List<BroadcastEntity> bes = new ArrayList<BroadcastEntity>();
            for (int i = 0; i < beJSON.length(); i++) {
                JSONObject j = beJSON.getJSONObject(i);
                String beUrl = j.getString("source");
                BroadcastEntity be = BroadcastEntity.getBroadcastEntity(beUrl);
                if (be == null) {
                    be = new BroadcastEntity(j, parentUrl, receivedAt);
                    BroadcastEntity.setBroadcastEntity(beUrl, be);
                } else {
                    be.initialise(j, parentUrl, receivedAt);
                }
                if (!be.isProgramme()) pending_broadcast_entity_urls.add(beUrl);
                JSONArray childs = j.getJSONArray("objects");
                if (childs.length() > 0) be.setChildren(createEntitiesFromJSONArray(beUrl, childs, receivedAt));
                bes.add(be);
            }
            return bes;
        }

    }

    private static String readFromInterwebs(String beUrl) {
        String ret = "{\"status\": 500}";

        if (BoxsetterUtils.networkAvailable()) {
            try {
                StringBuilder sb = new StringBuilder();
                URL url = new URL(beUrl);
                HttpURLConnection http = (HttpURLConnection) url.openConnection();
                if (beUrl.matches("https://boxsetter\\.gomes\\.com\\.es/.*")) {
                    http.setRequestProperty("Authorization", BoxsetterUtils.getBasicAuth());
                }
                http.setConnectTimeout(3000);
                int statusCode = http.getResponseCode();

                Log.d("BSBE", "Status code returned: " + statusCode + ": " + beUrl);

                if (statusCode == HTTP_OK) {
                    InputStream is = http.getInputStream();
                    BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
                    for (int cp = rd.read(); cp != -1; cp = rd.read()) {
                        sb.append((char) cp);
                    }
                    is.close();
                } else {
                    sb.append("{\"status\": ");
                    sb.append(statusCode);
                    sb.append('}');
                }

                http.disconnect();
                ret = sb.toString();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return ret;
    }

    public static void setBroadcastEntity(String url, BroadcastEntity broadcastEntity) {
        getEntityCache().put(sanitizeUrl(url), broadcastEntity);
    }

    public static BroadcastEntity getBroadcastEntity(String url) {
        url = sanitizeUrl(url);
        BroadcastEntity be = getEntityCache().get(url);
        if (be == null) {
            List<BroadcastEntity> bes = BroadcastEntity.find(BroadcastEntity.class, "source = ? and user_name = ?", url, BoxsetterUtils.getBoxsetterUser());
            if (bes.size() > 0) {
                be = bes.get(0);
                getEntityCache().put(url, be);
            }
        }
        return be;
    }

    public static void setBroadcastEntities(String url, List<BroadcastEntity> bes) {
        BroadcastEntity broadcastEntity = getBroadcastEntity(url);
        if (broadcastEntity != null) {
            broadcastEntity.setChildren(bes);
        }
    }

    public static void addBroadcastEntities(String url, List<BroadcastEntity> bes) {
        BroadcastEntity broadcastEntity = getBroadcastEntity(url);
        if (broadcastEntity != null) {
            broadcastEntity.getChildren().addAll(bes);
            Collections.sort(broadcastEntity.getChildren());
        }
    }

    public static List<BroadcastEntity> getBroadcastEntities(String url) {
        BroadcastEntity broadcastEntity = getBroadcastEntity(url);
        if (broadcastEntity == null) return null;
        return broadcastEntity.getChildren();
    }

    public static String sanitizeUrl(String url) {
        int pos = url.indexOf('?');
        if (pos > -1) url = url.substring(0, pos);
        return url;
    }

    public static void clearCache() {
        Map<String, BroadcastEntity> userHash = new HashMap<String, BroadcastEntity>();
        userHash.put(BoxsetterActivity.BASE_BOXSET_URL, new BroadcastEntity(BoxsetterActivity.BASE_BOXSET_URL));
        entityCache.put(BoxsetterUtils.getBoxsetterUser(), userHash);
        downloadedEntities = null;
    }

    protected static Map<String, BroadcastEntity> getEntityCache() {
        return entityCache.get(BoxsetterUtils.getBoxsetterUser());
    }

    public static List<BroadcastEntity> getDownloadedEntities() {
        if (downloadedEntities == null) {
            downloadedEntities = new ArrayList<BroadcastEntity>();
            List<BroadcastEntity> bes = BroadcastEntity.find(BroadcastEntity.class, "betype = 'programme' and user_name = ? order by filename", BoxsetterUtils.getBoxsetterUser());

            Log.d("BSBE", "Num entities returned: " + bes.size());

            for (BroadcastEntity be : bes) {
                Log.d("BSBE", be.getFilename() + "@" + be.toString() + " - Dwn: " + be.isDownloading() + ", Ply: " + be.isProgramme() + ", Hsf: " + be.hasLocalFile());
                if (!be.isRescinded() && be.isLocal()) {
                    Log.d("BSBE","DOWNLOADED: " + be.getFilename());
                    downloadedEntities.add(be);
                }
            }
        }

        return downloadedEntities;
    }

    public void addToDownloadedEntities() {
        if (downloadedEntities != null) {
            if (this.isLocal()) {
                downloadedEntities.add(this);
                Collections.sort(downloadedEntities);
            }
        }
    }

    public void removeFromDownloadedEntities() {
        if (downloadedEntities != null) {
            for (BroadcastEntity be : downloadedEntities) {
                if (be.getFilename().equals(this.getFilename())) {
                    boolean b = downloadedEntities.remove(be);
                    Log.d("BSBE", "Removing " + getFilename() + ": " + b);
                    break;
                }
            }
        }
    }

    public static void purge(long preUpdateTime) {
        for (BroadcastEntity be : BroadcastEntity.listAll(BroadcastEntity.class)) {
            if (be.updatedAt == preUpdateTime) be.delete();
        }
    }
}

