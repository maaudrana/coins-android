package com.bubelov.coins.ui.activity;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.SeekBar;

import com.bubelov.coins.Constants;
import com.bubelov.coins.R;
import com.bubelov.coins.manager.UserNotificationManager;
import com.bubelov.coins.ui.fragment.ConfirmationDialog;
import com.bubelov.coins.ui.fragment.ConfirmationDialogListener;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapFragment;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

/**
 * Author: Igor Bubelov
 * Date: 12/07/14 16:24
 */

public class AreaSelectActivity extends ActionBarActivity implements ConfirmationDialogListener {
    private static final String CENTER_EXTRA = "center";
    private static final String RADIUS_EXTRA = "radius";

    private static final String SAVE_DATA_DIALOG = "save_data_dialog";

    private GoogleMap map;

    private UserNotificationManager notificationManager;

    private Marker center;
    private Circle circle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_area_select);

        MapFragment mapFragment = (MapFragment)getFragmentManager().findFragmentById(R.id.map);
        map = mapFragment.getMap();
        map.getUiSettings().setZoomControlsEnabled(false);
        map.setOnMarkerDragListener(new OnMarkerDragListener());

        notificationManager = new UserNotificationManager(this);

        if (savedInstanceState != null) {
            addArea((LatLng)savedInstanceState.getParcelable(CENTER_EXTRA), savedInstanceState.getInt(RADIUS_EXTRA));
        } else {
            LatLng center = notificationManager.getNotificationAreaCenter();

            if (center == null) {
                center = new LatLng(Constants.SAN_FRANCISCO_LATITUDE, Constants.SAN_FRANCISCO_LONGITUDE);
                findMyLocationAndMoveAreaHere();
            }

            addArea(center, notificationManager.getNotificationAreaRadius());
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(center, 8));
        }

        SeekBar seekBar = (SeekBar)findViewById(R.id.seek_bar_radius);
        seekBar.setMax(500000);
        seekBar.setProgress((int)circle.getRadius());
        seekBar.setOnSeekBarChangeListener(new SeekBarChangeListener());

        initActions();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (center != null && circle != null) {
            outState.putParcelable(CENTER_EXTRA, center.getPosition());
            outState.putInt(RADIUS_EXTRA, (int)circle.getRadius());
        }

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (notificationManager.getNotificationAreaCenter() == null) {
            ConfirmationDialog.newInstance(R.string.app_name).show(getFragmentManager(), SAVE_DATA_DIALOG);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfirmed(String tag) {
        if (SAVE_DATA_DIALOG.equals(tag)) {
            saveSelectedArea();
            finish();
        }
    }

    @Override
    public void onCancelled(String tag) {
        if (SAVE_DATA_DIALOG.equals(tag)) {
            finish();
        }
    }

    private void initActions() {
        startSupportActionMode(new ActionModeCallback());
    }

    private void findMyLocationAndMoveAreaHere() {
        map.setOnMyLocationChangeListener(location -> {
            map.setOnMyLocationChangeListener(null);
            moveArea(new LatLng(location.getLatitude(), location.getLongitude()));
        });
    }

    private void addArea(LatLng center, int radius) {
        this.center = map.addMarker(new MarkerOptions().position(center).draggable(true));

        CircleOptions circleOptions = new CircleOptions()
                .center(this.center.getPosition())
                .radius(getIntent().getIntExtra(RADIUS_EXTRA, notificationManager.getNotificationAreaRadius()))
                .fillColor(getResources().getColor(R.color.notification_area))
                .strokeColor(getResources().getColor(R.color.notification_area_border))
                .strokeWidth(4);

        circle = map.addCircle(circleOptions);
    }

    private void moveArea(LatLng location) {
        center.setPosition(location);
        circle.setCenter(location);
    }

    private void saveSelectedArea() {
        notificationManager.setNotificationAreaCenter(circle.getCenter());
        notificationManager.setNotificationAreaRadius((int) circle.getRadius());
    }

    private class OnMarkerDragListener implements GoogleMap.OnMarkerDragListener {
        @Override
        public void onMarkerDragStart(Marker marker) {
            circle.setFillColor(getResources().getColor(android.R.color.transparent));
        }

        @Override
        public void onMarkerDrag(Marker marker) {
            circle.setCenter(marker.getPosition());
            map.animateCamera(CameraUpdateFactory.newLatLng(marker.getPosition()));
        }

        @Override
        public void onMarkerDragEnd(Marker marker) {
            circle.setFillColor(getResources().getColor(R.color.notification_area));
        }
    }

    private class ActionModeCallback implements android.support.v7.view.ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(android.support.v7.view.ActionMode actionMode, Menu menu) {
            MenuInflater inflater = actionMode.getMenuInflater();
            inflater.inflate(R.menu.empty, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(android.support.v7.view.ActionMode actionMode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(android.support.v7.view.ActionMode actionMode, MenuItem menuItem) {
            return false;
        }

        @Override
        public void onDestroyActionMode(android.support.v7.view.ActionMode actionMode) {
            if (notificationManager.getNotificationAreaCenter() == null) {
                ConfirmationDialog.newInstance(R.string.dialog_save_notification_area).show(getFragmentManager(), SAVE_DATA_DIALOG);
            } else {
                supportFinishAfterTransition();
            }
        }
    }

    private class SeekBarChangeListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            circle.setRadius(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {

        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {

        }
    }
}