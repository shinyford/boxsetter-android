package com.boxsetter;

import java.util.List;

/**
 * Created by nicford on 03/02/16.
 */
public interface BroadcastEntityListener {
    public void onBroadcastEntitiesAcquired(String url, List<BroadcastEntity> bes);
}
