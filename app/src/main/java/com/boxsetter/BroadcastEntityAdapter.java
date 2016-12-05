package com.boxsetter;

import android.database.DataSetObserver;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.squareup.picasso.Picasso;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by nic.ford on 12/03/15.
 */
public class BroadcastEntityAdapter extends ArrayAdapter<BroadcastEntity> {

    private final BoxsetterActivity activity;
    private final List<BroadcastEntity> bes;
    private final Map<String, View> views = new HashMap<String, View>();

    public BroadcastEntityAdapter(BoxsetterActivity activity, List<BroadcastEntity> bes) {
        super(activity, 0);
        this.activity = activity;
        this.bes = bes;
    }

    @Override
    public void registerDataSetObserver(DataSetObserver observer) {

    }

    @Override
    public void unregisterDataSetObserver(DataSetObserver observer) {

    }

    @Override
    public int getCount() {
        return bes.size();
    }

    @Override
    public BroadcastEntity getItem(int position) {
        return bes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        LayoutInflater inflater = activity.getLayoutInflater();

        View rowView;
        if (view != null) {
            rowView = view;
        } else if (getItemViewType(position) == 0) {
            rowView = inflater.inflate(R.layout.fragment_prog_left, null, true);
        } else {
            rowView = inflater.inflate(R.layout.fragment_prog_right, null, true);
        }

        BroadcastEntity be = bes.get(position);
        if (!be.isProgramme() && be.getChildren().size() == 1) be = be.getChildren().get(0);

        views.put(be.getSource(), rowView);

        TextView txtTitle = (TextView)rowView.findViewById(R.id.title);
        TextView txtNotation = (TextView)rowView.findViewById(R.id.notation);
        final ImageView imageView = (ImageView)rowView.findViewById(R.id.img);
        ImageView downloadedIcon = (ImageView)rowView.findViewById(R.id.downloaded);

        txtTitle.setText(be.getTitle());
        txtNotation.setText(be.getAddenda());
        Picasso.with(activity).load(be.getImg()).placeholder(activity.getPlaceholderResourceId(be.getChannel())).into(imageView);
        downloadedIcon.setVisibility(be.isLocal() ? ImageView.VISIBLE : ImageView.INVISIBLE);

        return rowView;
    }

    @Override
    public int getItemViewType(int position) {
        return position & 0x01;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    @Override
    public boolean isEnabled(int position) {
        return true;
    }

    public void showDownloaded(String url) {
        Log.d("BSBEA", "Looking for '" + url + "' out of " + views.size());
        View v = views.get(url);
        Log.d("BSBEA","Found: " + v);
        if (v != null) v.findViewById(R.id.downloaded).setVisibility(View.VISIBLE);
    }

}
