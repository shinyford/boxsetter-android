package com.boxsetter;

/**
 * Created by nic.ford on 04/03/15.
 */
public interface VideoResponder {
    public void onVideoLocated(String url, Long position);

    public void onVideoLocated(int status);
}

