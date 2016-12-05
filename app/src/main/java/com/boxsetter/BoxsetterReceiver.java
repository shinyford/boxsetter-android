package com.boxsetter;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Nic on 10/06/2015.
 */
public class BoxsetterReceiver extends BroadcastReceiver {

    private BoxsetterActivity activity;
    private Map<Long, BroadcastEntity> downloadingEntities = new HashMap<Long, BroadcastEntity>();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            BroadcastEntity be = downloadingEntities.remove(downloadId);

            if (be != null) {
                be.addToDownloadedEntities();
                if (activity != null) activity.redraw(be);
            }
        }
    }

    public void setActivity(BoxsetterActivity activity) {
        this.activity = activity;
    }

    public void registerDownload(Long did, BroadcastEntity be) {
        downloadingEntities.put(did, be);
    }
}
