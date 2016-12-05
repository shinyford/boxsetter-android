package com.boxsetter;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

public class BoxsetterSettingsActivity extends AppCompatActivity {
    private boolean changed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);;

        ActionBar actionBar = getSupportActionBar();
        actionBar.setLogo(R.mipmap.ic_launcher);
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayUseLogoEnabled(true);
        actionBar.setHomeButtonEnabled(true);
        actionBar.setDisplayHomeAsUpEnabled(true);

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new BoxsetterSettingsFragment())
                .commit();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (changed) {
            BoxsetterUtils.setBoxsetterUser(null);
            BroadcastEntity.clearCache();

            Intent i = new Intent(this, BoxsetterActivity.class);
            i.putExtra(BoxsetterActivity.FORCE_REFRESH, "true");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        }
    }

    public void setChanged(boolean changed) {
        Log.d("BSBSA", "Setting changed to " + changed);
        this.changed = changed;
    }

}