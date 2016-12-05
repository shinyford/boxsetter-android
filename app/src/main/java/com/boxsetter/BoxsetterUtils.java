package com.boxsetter;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;

import com.squareup.picasso.Cache;
import com.squareup.picasso.Picasso;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by nic.ford on 26/02/15.
 */
public class BoxsetterUtils {

    private static final String BOXSETTER_FILES_DIR = "/Android/data/com.boxsetter/files";
    private static List<File> mAppDirs = null;
    private static long lastCheck = 0;
    private static boolean mNetworkAvailable = false;
    private static File mainDir;
    private static SharedPreferences prefs;
    private static Resources res;
    private static DownloadManager downloadManager;
    private static ConnectivityManager connectivityManager;
    private static String mBoxsetterUser = null;
    private static AudioManager mAudioManager;
    private static ContentResolver cResolver;
    private static DisplayMetrics displayMetrics;

    public static int max_system_volume = 0;
    public static int max_system_brightness = 255;
    public static int screenHeightL, screenWidthL;
    public static int screenHeightP, screenWidthP;


    public static boolean first(Activity ac) {
        if (mainDir == null) {
            mainDir = ac.getExternalFilesDir(null);

            mAppDirs = new ArrayList<File>();
            for (File f : ac.getExternalFilesDirs(null)) {
                Log.d("BSBU","Dir found: " + f.getAbsolutePath());
                mAppDirs.add(f);

                String root = f.getAbsolutePath().substring(0, f.getAbsolutePath().indexOf("/Android"));
                File v = new File(root, "Movies");
                if (v.exists()) {
                    Log.d("BSBU", "Dir found: " + v.getAbsolutePath());
                    mAppDirs.add(v);
                }

                v = new File(root, "Videos");
                if (v.exists()) {
                    Log.d("BSBU", "Dir found: " + v.getAbsolutePath());
                    mAppDirs.add(v);
                }
            }

            cResolver = ac.getContentResolver();

            mAudioManager = (AudioManager)ac.getSystemService(Context.AUDIO_SERVICE);
            max_system_volume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

            prefs = PreferenceManager.getDefaultSharedPreferences(ac);
            res = ac.getResources();
            downloadManager = (DownloadManager)ac.getSystemService(Context.DOWNLOAD_SERVICE);
            connectivityManager = (ConnectivityManager)ac.getSystemService(Context.CONNECTIVITY_SERVICE);

            Point size = new Point();
            ac.getWindowManager().getDefaultDisplay().getSize(size);
            screenWidthP = screenHeightL = Math.min(size.x, size.y);
            screenWidthL = screenHeightP = Math.max(size.x, size.y);

            List<BroadcastEntity> bes = BroadcastEntity.find(BroadcastEntity.class, "dirty_position = 1");
            Log.d("BSBU", "Number of dirty bes found: " + bes.size());
            BroadcastEntity.sendPositions(bes);


            final File piCacheo = ac.getExternalCacheDir();

            Log.d("BSBU", "Cache: " + piCacheo.getAbsolutePath());

            Picasso.setSingletonInstance(
                new Picasso.Builder(ac)
                    .memoryCache(
                        new Cache() {
                            @Override
                            public Bitmap get(String key) {
                                File f = new File(piCacheo, encode(key));
                                return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
                            }

                            @Override
                            public void set(String key, Bitmap bitmap) {
                                FileOutputStream out = null;
                                try {
                                    File f = new File(piCacheo, encode(key));
                                    out = new FileOutputStream(f);
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                } finally {
                                    if (out != null) {
                                        try {
                                            out.close();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                }
                            }

                            @Override
                            public int size() {
                                return (int)piCacheo.length();
                            }

                            @Override
                            public int maxSize() {
                                return Integer.MAX_VALUE;
                            }

                            @Override
                            public void clear() {
                                clearKeyUri(null);
                            }

                            @Override
                            public void clearKeyUri(String keyPrefix) {
                                for (File f : piCacheo.listFiles()) {
                                    if (keyPrefix == null || f.getName().startsWith(keyPrefix)) f.delete();
                                }
                            }

                            private String encode(String key) {
                                if (key.indexOf("g.bbcredux.com") > -1) {
                                    return key.substring(key.indexOf("programme/") + 10, key.indexOf("/download")) + ".jpg";
                                } else if (key.indexOf("gomes.com.es") > -1) {
                                    return key.substring(key.lastIndexOf('/') + 1);
                                } else {
                                    return Base64.encodeToString(key.getBytes(), 0) + ".jpg";
                                }
                            }
                        }

                    )
                    .build()
            );

            return true;
        }
        return false;
    }

    public static int getBrightness() {
        try {
            return Settings.System.getInt(cResolver, Settings.System.SCREEN_BRIGHTNESS);
        } catch (Settings.SettingNotFoundException e) {
            return 0;
        }
    }

    public static int changeBrightness(float pos, int initial_brightness, float initial_pos) {
        int brt = getValueByEventPosition(pos, initial_pos, initial_brightness, max_system_brightness);
        Settings.System.putInt(cResolver, Settings.System.SCREEN_BRIGHTNESS, brt);
        return brt;
    }

    public static int getVolume() {
        return mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public static int changeVolume(float pos, int initial_volume, float initial_pos) {
        int vol = getValueByEventPosition(pos, initial_pos, initial_volume, max_system_volume);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
        return vol;
    }

    private static int getValueByEventPosition(float pos, float initial_pos, int initial_value, int value_max) {
        float val;

//        int value_range, value_base;
//        float pos_range;
//        if (pos >= initial_pos) {
//            pos_range = screenHeightL - initial_pos;
//            value_base = initial_value;
//            value_range = value_max - initial_value;
//            Log.d("BU","Going up. POS=" + pos + ", INPOS=" + initial_pos + ", POSRNG=" + pos_range + ", VALBASE=" + value_base + ", VALRNG=" + value_range );
//            pos -= initial_pos;
//        } else {
//            pos_range = initial_pos;
//            value_base = 0;
//            value_range = initial_value;
//            Log.d("BU","Going down. POS=" + pos + ", INPOS=" + initial_pos + ", POSRNG=" + pos_range + ", VALBASE=" + value_base + ", VALRNG=" + value_range );
//        }
//        val = value_base + (pos * value_range / pos_range);

        val = initial_value + value_max*(pos - initial_pos)/screenHeightL;

        return Math.max(0, Math.min(value_max, (int)val));
    }

    public static String getQueryString(String... params) {
        if (params.length == 0) return "";

        StringBuilder sb = new StringBuilder();

        try {
            for (int i = 0; i < params.length; i += 2) {
                sb.append(i == 0 ? '?' : '&');
                sb.append(URLEncoder.encode(params[i], "UTF-8"));
                sb.append('=');
                sb.append(URLEncoder.encode(params[i+1], "UTF-8"));
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return sb.toString();
    }

    public static void setBoxsetterUser(String boxsetterUser) {
        mBoxsetterUser = boxsetterUser;
    }

    public static String getBoxsetterUser() {
        if (mBoxsetterUser == null) mBoxsetterUser = prefs.getString(res.getString(R.string.pref_key_user_name), "");
        return mBoxsetterUser;
    }

    public static String getBoxsetterPass() {
        return prefs.getString(res.getString(R.string.pref_key_redux_pass), ""); // prefs.getString(res.getString(R.string.pref_key_bx_pass), "");
    }

    private static String hexify(String string) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < string.length(); i++) {
            s.append(String.format("%02x", (int)string.charAt(i)));
        }
        return s.toString();
    }

    public static File locateFile(String filename) {
        File file = null;

        if (null != filename && !"".equals(filename)) {
            long available = -1;
            for (File dir : mAppDirs) {
                String thisDirpath = dir.getAbsolutePath();
                File thisFile = new File(thisDirpath, filename);
                if (thisFile.exists()) return thisFile;

                if (thisDirpath.indexOf("/Android") > -1) {
                    long space = dir.getUsableSpace();
                    if (available < space) {
                        available = space;
                        file = thisFile;
                    }
                }
            }

        }

        return file;
    }

    public static File locateBestLocation(String filename) {
        File file = null;

        if (null != filename && !"".equals(filename)) {
            long available = -1;
            for (File appDir : mAppDirs) {
                if (appDir.canWrite()) {
                    File thisFile = new File(appDir, filename);
                    long space = appDir.getUsableSpace() + thisFile.length();
                    if (available < space) {
                        available = space;
                        file = thisFile;
                    }
                }

            }

        }

        return file;
    }

    public static DownloadManager getDownloadManager() {
        return downloadManager;
    }

    public static boolean networkAvailable() {
        NetworkInfo[] networkInfo = connectivityManager.getAllNetworkInfo();
        for (NetworkInfo ni : networkInfo) {
            if (ni.isConnected()) return true;
        }
        return false;
    }

    public static String getBasicAuth() {
        String userpass = getBoxsetterUser() + ":" + getBoxsetterPass();
        String basicAuth = "Basic " + new String(Base64.encode(userpass.getBytes(), Base64.DEFAULT));
        Log.d("BSU","AUTH: " + basicAuth);
        return basicAuth;
    }

    public static File getMainDir() {
        return mainDir;
    }
}
