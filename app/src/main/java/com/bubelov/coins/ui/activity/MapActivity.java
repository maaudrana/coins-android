package com.bubelov.coins.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.bubelov.coins.BuildConfig;
import com.bubelov.coins.Constants;
import com.bubelov.coins.MerchantsCache;
import com.bubelov.coins.R;
import com.bubelov.coins.dagger.Injector;
import com.bubelov.coins.event.DatabaseSyncedEvent;
import com.bubelov.coins.model.Amenity;
import com.bubelov.coins.model.Currency;
import com.bubelov.coins.model.Merchant;
import com.bubelov.coins.model.MerchantNotification;
import com.bubelov.coins.model.NotificationArea;
import com.bubelov.coins.provider.NotificationAreaProvider;
import com.bubelov.coins.ui.widget.DrawerMenu;
import com.bubelov.coins.ui.widget.MerchantDetailsView;
import com.bubelov.coins.util.MapMarkersCache;
import com.bubelov.coins.util.OnCameraChangeMultiplexer;
import com.bubelov.coins.util.StaticClusterRenderer;
import com.bubelov.coins.util.Utils;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.ContentViewEvent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.maps.android.clustering.ClusterManager;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.squareup.otto.Subscribe;

import java.util.Collection;
import java.util.Collections;

import butterknife.BindView;
import butterknife.ButterKnife;

/**
 * @author Igor Bubelov
 */

public class MapActivity extends AbstractActivity implements DrawerMenu.OnItemClickListener {
    private static final String MERCHANT_ID_EXTRA = "merchant_id";
    private static final String NOTIFICATION_AREA_EXTRA = "notification_area";
    private static final String CLEAR_MERCHANT_NOTIFICATIONS_EXTRA = "clear_merchant_notifications";

    private static final int REQUEST_CHECK_LOCATION_SETTINGS = 10;
    private static final int REQUEST_ACCESS_LOCATION = 20;
    private static final int REQUEST_FIND_MERCHANT = 40;

    private static final int MAP_ANIMATION_DURATION_MILLIS = 350;
    private static final float MAP_DEFAULT_ZOOM = 13;

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.merchant_toolbar)
    Toolbar merchantToolbar;

    @BindView(R.id.merchant_top_gradient)
    View merchantTopGradient;

    @BindView(R.id.drawer_layout)
    DrawerLayout drawer;

    @BindView(R.id.sliding_panel)
    SlidingUpPanelLayout slidingLayout;

    @BindView(R.id.merchant_details)
    MerchantDetailsView merchantDetails;

    @BindView(R.id.locate_button)
    FloatingActionButton actionButton;

    private ActionBarDrawerToggle drawerToggle;

    private GoogleMap map;

    private ClusterManager<Merchant> merchantsManager;

    private Amenity amenity;

    private GoogleApiClient googleApiClient;

    private Merchant selectedMerchant;

    private boolean saveCameraPositionFlag;

    private CameraUpdate cameraBeforeSelection;

    private MerchantsCache merchantsCache;

    private boolean firstLaunch;

    public static Intent newShowMerchantIntent(Context context, long merchantId, boolean clearNotifications) {
        Intent intent = new Intent(context, MapActivity.class);
        intent.putExtra(MERCHANT_ID_EXTRA, merchantId);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(CLEAR_MERCHANT_NOTIFICATIONS_EXTRA, clearNotifications);
        return intent;
    }

    public static Intent newShowNotificationAreaIntent(Context context, NotificationArea notificationArea, boolean clearNotifications) {
        Intent intent = new Intent(context, MapActivity.class);
        intent.putExtra(NOTIFICATION_AREA_EXTRA, notificationArea);
        intent.putExtra(CLEAR_MERCHANT_NOTIFICATIONS_EXTRA, clearNotifications);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);
        ButterKnife.bind(this);

        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);

        merchantToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slidingLayout.collapsePanel();
            }
        });

        drawerToggle = new DrawerToggle(this, drawer, android.R.string.ok, android.R.string.ok);
        drawer.setDrawerListener(drawerToggle);

        DrawerMenu drawerMenu = ButterKnife.findById(this, R.id.left_drawer);
        drawerMenu.setItemSelectedListener(this);

        firstLaunch = savedInstanceState == null;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();

        slidingLayout.hidePanel();
        slidingLayout.setAnchorPoint(0.5f);
        PanelSlideListener slideListener = new PanelSlideListener();
        slidingLayout.setPanelSlideListener(slideListener);

        if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.COLLAPSED)) {
            slideListener.onPanelCollapsed(null);
        }

        if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED)) {
            slideListener.onPanelAnchored(null);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadMerchants();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_map_activity, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        int id = item.getItemId();

        switch (id) {
            case R.id.action_settings:
                SettingsActivity.start(this);
                return true;
            case R.id.action_search:
                MerchantsSearchActivity.startForResult(this, map.getMyLocation(), REQUEST_FIND_MERCHANT);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(final Intent intent) {
        if (intent.getBooleanExtra(CLEAR_MERCHANT_NOTIFICATIONS_EXTRA, false)) {
            MerchantNotification.deleteAll();
        }

        if (intent.hasExtra(MERCHANT_ID_EXTRA)) {
            slidingLayout.postDelayed(new Runnable() {
                @Override
                public void run() {
                    MapActivity.this.selectMerchant(intent.getLongExtra(MERCHANT_ID_EXTRA, -1), false);
                    slidingLayout.anchorPanel();
                }
            }, 1);
        }

        if (intent.hasExtra(NOTIFICATION_AREA_EXTRA)) {
            NotificationArea notificationArea = (NotificationArea) intent.getSerializableExtra(NOTIFICATION_AREA_EXTRA);
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(notificationArea.getCenter(), MAP_DEFAULT_ZOOM));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CHECK_LOCATION_SETTINGS && resultCode == RESULT_OK) {
            moveToUserLocation();
        }

        if (requestCode == REQUEST_FIND_MERCHANT && resultCode == RESULT_OK) {
            Merchant merchant = (Merchant) data.getSerializableExtra(MerchantsSearchActivity.MERCHANT_EXTRA);
            selectMerchant(merchant.getId(), false);
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedMerchant.getPosition(), MAP_DEFAULT_ZOOM));
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_ACCESS_LOCATION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initLocation();
                }
        }
    }

    private void initLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        map.setMyLocationEnabled(true);

        googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(new LocationApiConnectionCallbacks())
                .addOnConnectionFailedListener(new LocationAliConnectionFailedListener())
                .build();

        googleApiClient.connect();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public void onBackPressed() {
        if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED) || slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.EXPANDED)) {
            slidingLayout.collapsePanel();
        } else {
            super.onBackPressed();
        }
    }

    private void onMerchantsLoaded(Collection<Merchant> merchants) {
        merchantsManager.clearItems();
        merchantsManager.addItems(merchants);
        merchantsManager.cluster();
    }

    @Override
    public void onAmenitySelected(Amenity amenity, String title) {
        drawer.closeDrawer(GravityCompat.START);
        getSupportActionBar().setTitle(title);
        this.amenity = amenity;
        reloadMerchants();

        if (amenity != null) {
            Answers.getInstance().logContentView(new ContentViewEvent()
                    .putContentName("Amenity")
                    .putContentType("Amenities")
                    .putContentId(amenity.name()));
        }
    }

    private void onPlayServicesAvailable() {
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        map = mapFragment.getMap();
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setCompassEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);

        if (BuildConfig.DEBUG) {
            map.getUiSettings().setZoomControlsEnabled(true);
            actionButton.setTranslationX(-Utils.dpToPx(this, 48));
        }

        initClustering();

        map.setOnCameraChangeListener(new OnCameraChangeMultiplexer(merchantsManager, new CameraChangeListener()));

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            initLocation();
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                moveToDefaultLocation();

                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_ACCESS_LOCATION);
            } else {
                moveToDefaultLocation();
            }
        }

        merchantsCache = Injector.INSTANCE.getAppComponent().getMerchantsCache();

        DrawerMenu drawerMenu = ButterKnife.findById(this, R.id.left_drawer);
        drawerMenu.setAmenity(amenity);
    }

    private void moveToUserLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Location lastLocation = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);

        if (lastLocation != null) {
            onUserLocationReceived(lastLocation);
        } else {
            moveToDefaultLocation();
        }
    }

    private void moveToDefaultLocation() {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(Constants.DEFAULT_LOCATION, MAP_DEFAULT_ZOOM));
    }

    private void onUserLocationReceived(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, MAP_DEFAULT_ZOOM));

        NotificationAreaProvider notificationAreaProvider = new NotificationAreaProvider(this);

        if (notificationAreaProvider.get() == null) {
            notificationAreaProvider.save(new NotificationArea(latLng));
        }
    }

    private void initClustering() {
        merchantsManager = new ClusterManager<>(this, map);
        PlacesRenderer renderer = new PlacesRenderer(this, map, merchantsManager);
        merchantsManager.setRenderer(renderer);
        renderer.setOnClusterItemClickListener(new ClusterItemClickListener());

        map.setOnCameraChangeListener(merchantsManager);
        map.setOnMarkerClickListener(merchantsManager);
        map.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                slidingLayout.hidePanel();
                selectedMerchant = null;
            }
        });
    }

    @Subscribe
    public void onDatabaseSyncFinished(DatabaseSyncedEvent event) {
        merchantsCache.invalidate();

        if (map == null) {
            return;
        }

        reloadMerchants();
    }

    private void reloadMerchants() {
        if (map == null) {
            return;
        }

        if (selectedMerchant != null && slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED)) {
            onMerchantsLoaded(Collections.singletonList(selectedMerchant));
            return;
        }

        final LatLngBounds bounds = map.getProjection().getVisibleRegion().latLngBounds;

        if (merchantsCache.isInitialized()) {
            onMerchantsLoaded(merchantsCache.getMerchants(bounds, amenity));
        } else {
            merchantsCache.getListeners().add(new MerchantsCache.MerchantsCacheListener() {
                @Override
                public void onMerchantsCacheInitialized() {
                    onMerchantsLoaded(merchantsCache.getMerchants(bounds, amenity));
                    merchantsCache.getListeners().remove(this);
                }
            });
        }
    }

    private Merchant getMerchant(long id) {
        Merchant merchant = Merchant.find(id);
        merchant.setCurrencies(Currency.findByMerchant(merchant));
        return merchant;
    }

    public void onActionButtonClicked(View view) {
        if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.ANCHORED)) {
            Utils.showDirections(this, selectedMerchant.getLatitude(), selectedMerchant.getLongitude());
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                moveToUserLocation();
            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_ACCESS_LOCATION);
            }
        }
    }

    private class DrawerToggle extends ActionBarDrawerToggle {
        public DrawerToggle(Activity activity, DrawerLayout drawerLayout, int openDrawerContentDescRes, int closeDrawerContentDescRes) {
            super(activity, drawerLayout, openDrawerContentDescRes, closeDrawerContentDescRes);
        }

        @Override
        public void onDrawerOpened(View drawerView) {
            super.onDrawerOpened(drawerView);
            invalidateOptionsMenu();
        }

        @Override
        public void onDrawerClosed(View drawerView) {
            super.onDrawerClosed(drawerView);
            invalidateOptionsMenu();
        }
    }

    private class PlacesRenderer extends StaticClusterRenderer<Merchant> {
        private MapMarkersCache cache;

        public PlacesRenderer(Context context, GoogleMap map, ClusterManager<Merchant> clusterManager) {
            super(context, map, clusterManager);
            cache = Injector.INSTANCE.getAppComponent().getMarkersCache();
        }

        @Override
        protected void onBeforeClusterItemRendered(Merchant item, MarkerOptions markerOptions) {
            super.onBeforeClusterItemRendered(item, markerOptions);
            markerOptions.icon(cache.getMarker(item.getAmenity())).anchor(Constants.MAP_MARKER_ANCHOR_U, Constants.MAP_MARKER_ANCHOR_V);
        }
    }

    private class CameraChangeListener implements GoogleMap.OnCameraChangeListener {
        @Override
        public void onCameraChange(CameraPosition cameraPosition) {
            reloadMerchants();

            if (slidingLayout.getPanelState().equals(SlidingUpPanelLayout.PanelState.COLLAPSED)) {
                saveCameraPositionFlag = true;
            }
        }
    }

    private class ClusterItemClickListener implements ClusterManager.OnClusterItemClickListener<Merchant> {
        @Override
        public boolean onClusterItemClick(Merchant merchant) {
            selectMerchant(merchant.getId(), true);
            return false;
        }
    }

    private void selectMerchant(long merchantId, boolean saveCameraPosition) {
        saveCameraPositionFlag = saveCameraPosition;
        selectedMerchant = getMerchant(merchantId);
        merchantDetails.setMerchant(selectedMerchant);

        slidingLayout.setPanelHeight(merchantDetails.getHeaderHeight());
        slidingLayout.showPanel();
    }

    private class LocationApiConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
        @Override
        public void onConnected(Bundle bundle) {
            if (firstLaunch && !getIntent().hasExtra(NOTIFICATION_AREA_EXTRA)) {
                moveToUserLocation();
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
            showToast("Connection to location API was suspended");
        }
    }

    private class LocationAliConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            showToast("Couldn't connect to location API");
        }
    }

    private class PanelSlideListener implements SlidingUpPanelLayout.PanelSlideListener {
        private boolean wasExpanded;

        @Override
        public void onPanelSlide(View view, float offset) {
            float locateButtonOffset = -offset * (view.getHeight() - merchantDetails.getHeaderHeight()) - merchantDetails.getHeaderHeight();
            actionButton.setTranslationY(Math.min(locateButtonOffset, 0));

            if (saveCameraPositionFlag) {
                cameraBeforeSelection = CameraUpdateFactory.newCameraPosition(map.getCameraPosition());
                saveCameraPositionFlag = false;
            }

            if (offset < 0.2) {
                if (!getSupportActionBar().isShowing()) {
                    getSupportActionBar().show();
                    merchantToolbar.setVisibility(View.GONE);
                    merchantTopGradient.setVisibility(View.GONE);
                }
            } else {
                if (getSupportActionBar().isShowing()) {
                    getSupportActionBar().hide();
                    merchantToolbar.setVisibility(View.VISIBLE);
                    merchantTopGradient.setVisibility(View.VISIBLE);
                }
            }
        }

        @Override
        public void onPanelCollapsed(View view) {
            slidingLayout.post(new Runnable() {
                @Override
                public void run() {
                    slidingLayout.setPanelHeight(merchantDetails.getHeaderHeight());
                    actionButton.setTranslationY(-merchantDetails.getHeaderHeight());
                }
            });

            if (!wasExpanded) {
                return;
            }

            map.setPadding(0, 0, 0, 0);
            map.getUiSettings().setAllGesturesEnabled(true);

            if (cameraBeforeSelection != null) {
                map.animateCamera(cameraBeforeSelection, MAP_ANIMATION_DURATION_MILLIS, null);
                cameraBeforeSelection = null;
                merchantDetails.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        reloadMerchants();
                    }
                }, 1);
            }

            actionButton.setImageResource(R.drawable.ic_my_location_24dp);
        }

        @Override
        public void onPanelExpanded(View view) {
            wasExpanded = true;
        }

        @Override
        public void onPanelAnchored(View view) {
            wasExpanded = true;
            map.setPadding(0, getResources().getDimensionPixelSize(R.dimen.marker_size) / 2, 0, Utils.getScreenHeight(MapActivity.this) / 2 + Utils.getStatusBarHeight(getApplicationContext()));
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedMerchant.getPosition(), MAP_DEFAULT_ZOOM), MAP_ANIMATION_DURATION_MILLIS, null);
            map.getUiSettings().setAllGesturesEnabled(false);
            actionButton.setImageResource(R.drawable.ic_directions_24dp);
        }

        @Override
        public void onPanelHidden(View view) {
            selectedMerchant = null;
            wasExpanded = false;
            map.setPadding(0, 0, 0, 0);
            map.getUiSettings().setAllGesturesEnabled(true);
            actionButton.setTranslationY(0);
            actionButton.setImageResource(R.drawable.ic_my_location_24dp);
        }
    }
}