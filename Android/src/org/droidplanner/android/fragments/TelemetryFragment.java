package org.droidplanner.android.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.o3dr.android.client.Drone;
import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.apis.CapabilityApi;
import com.o3dr.android.client.apis.solo.SoloCameraApi;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;

import com.o3dr.services.android.lib.gcs.event.GCSEvent;
import com.o3dr.services.android.lib.drone.property.Attitude;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.SimpleCommandListener;

import org.droidplanner.android.R;
import org.droidplanner.android.activities.WidgetActivity;
import org.droidplanner.android.fragments.helpers.ApiListenerFragment;
import org.droidplanner.android.utils.unit.providers.speed.SpeedUnitProvider;
import org.droidplanner.android.widgets.AttitudeIndicator;

import timber.log.Timber;

public class TelemetryFragment extends ApiListenerFragment {

    private static final String TAG = TelemetryFragment.class.getSimpleName();
    private static final String ACTION_GCS_INIT_ATT_LOCKED = GCSEvent.GCS_INIT_ATTITUDE_LOCKED;
    private long gcsAttLastUpdate = 0;
    private static final int UPDATE_INTERVAL = 100;

    private final static IntentFilter eventFilter = new IntentFilter();

    static {
        eventFilter.addAction(AttributeEvent.ATTITUDE_UPDATED);
        eventFilter.addAction(AttributeEvent.SPEED_UPDATED);
        eventFilter.addAction(AttributeEvent.STATE_CONNECTED);
    }


    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            switch (action) {
                case AttributeEvent.ATTITUDE_UPDATED:
                    onOrientationUpdate();
                    break;

                case AttributeEvent.SPEED_UPDATED:
                    onSpeedUpdate();
                    break;

                case AttributeEvent.STATE_CONNECTED:
                    tryStreamingVideo();
                    break;
            }
        }
    };

    private final static IntentFilter gcsEventFilter = new IntentFilter();
    static {
        gcsEventFilter.addAction(GCSEvent.GCS_ATTITUDE_UPDATED);
    }

    private final BroadcastReceiver gcsEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            switch (action) {
                case GCSEvent.GCS_ATTITUDE_UPDATED: // from GCS event
                    onGCSAttitudeUpdate();
                    break;
            }
        }
    };

    private AttitudeIndicator attitudeIndicator;
    private TextView roll;
    private TextView yaw;
    private TextView pitch;

    private TextView horizontalSpeed;
    private TextView verticalSpeed;

    private TextView gcsYaw;
    private TextView gcsPitch;
    private TextView gcsRoll;

    private Attitude gcsAtt;
    private Attitude gcsAttLocked;

    private Button gcsGestureButton;
    private Boolean gcsGestureButtonClicked = false;
    private Boolean gcsGestureInitialized = false;
    private int orangeColor;
    private int greyColor;
    private int whiteColor;
    private int greenColor;

    private View videoContainer;
    private View gcsAttContainer;
    private TextureView videoView;

    private boolean headingModeFPV;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_telemetry, container, false);
        attitudeIndicator = (AttitudeIndicator) view.findViewById(R.id.aiView);

        roll = (TextView) view.findViewById(R.id.rollValueText);
        yaw = (TextView) view.findViewById(R.id.yawValueText);
        pitch = (TextView) view.findViewById(R.id.pitchValueText);

        horizontalSpeed = (TextView) view.findViewById(R.id.horizontal_speed_telem);
        verticalSpeed = (TextView) view.findViewById(R.id.vertical_speed_telem);

        gcsAttContainer= view.findViewById(R.id.gcs_attitude_widget);
        if (gcsGestureInitialized==false){initGCSGestureButton(view);}

        videoContainer = view.findViewById(R.id.minimized_video_container);
        videoContainer.setVisibility(View.GONE);
        videoContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(getContext(), WidgetActivity.class)
                        .putExtra(WidgetActivity.EXTRA_WIDGET_ID, WidgetActivity.WIDGET_SOLOLINK_VIDEO));
            }
        });

        videoView = (TextureView) view.findViewById(R.id.minimized_video);

        return view;
    }


    private void initGCSGestureButton(View view) {
        if (getControlTower().hasGCSAccel() && getControlTower().hasGCSGyro()) {
            gcsYaw = (TextView) view.findViewById(R.id.gcs_yaw_local);
            gcsPitch = (TextView) view.findViewById(R.id.gcs_pitch_local);
            gcsRoll = (TextView) view.findViewById(R.id.gcs_roll_local);

            orangeColor = getResources().getColor(R.color.orange);
            greyColor = getResources().getColor(R.color.light_grey);
            whiteColor = getResources().getColor(R.color.white);

            gcsGestureButton = (Button) view.findViewById(R.id.gcs_gesture_send);
            gcsGestureButton.setBackgroundColor(greyColor);
            gcsGestureButton.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            gcsGestureButton.setText("Sending");
                            gcsGestureButton.setBackgroundColor(orangeColor);
                            gcsYaw.setBackgroundColor(greyColor);
                            gcsPitch.setBackgroundColor(greyColor);
                            gcsRoll.setBackgroundColor(greyColor);
                            if (!gcsGestureButtonClicked) {
                                getContext().sendBroadcast(new Intent(ACTION_GCS_INIT_ATT_LOCKED));
                                gcsGestureButtonClicked = true;
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                            gcsGestureButton.setText("Gesture");
                            gcsGestureButton.setBackgroundColor(greyColor);
                            gcsYaw.setBackgroundColor(whiteColor);
                            gcsPitch.setBackgroundColor(whiteColor);
                            gcsRoll.setBackgroundColor(whiteColor);
                            gcsGestureButtonClicked = false;
                    }
                    return false;
                }
            });
            gcsAttContainer.setVisibility(View.VISIBLE);
            gcsGestureInitialized = true;
        } else {
            gcsAttContainer.setVisibility(View.GONE);
        }
    }

    public Boolean isGCSGestureInitialized() {
        return gcsGestureInitialized;
    }

    private final static int GCS_MSG_INTERVAL=1000;
    private Handler handler = new Handler();
    private Runnable sendGCSGestureRunnable=new Runnable() {
        @Override
        public void run() {
            getDrone().followGCSGesture(gcsAttLocked,gcsAtt,true);
            handler.postDelayed(this, GCS_MSG_INTERVAL);
        }
    };

    public void activateGCSGestureButton() {
        if (gcsGestureInitialized) {
            greenColor = getResources().getColor(R.color.green);
            gcsGestureButton.setOnTouchListener(null);//nullify all previous listeners
            gcsGestureButton.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            gcsGestureButton.setText("Sending");
                            gcsGestureButton.setBackgroundColor(greenColor);
                            gcsYaw.setBackgroundColor(greyColor);
                            gcsPitch.setBackgroundColor(greyColor);
                            gcsRoll.setBackgroundColor(greyColor);
                            if (!gcsGestureButtonClicked) {
                                getContext().sendBroadcast(new Intent(ACTION_GCS_INIT_ATT_LOCKED));
                                gcsGestureButtonClicked = true;
                            }
                            //getDrone().followGCSGesture(gcsAttLocked, gcsAtt,true);//send one gesture data right away
                            //handler.postDelayed(sendGCSGestureRunnable, GCS_MSG_INTERVAL);
                            sendGCSGestureRunnable.run();
                            break;
                        case MotionEvent.ACTION_UP:
                            gcsGestureButton.setText("Gesture");
                            gcsGestureButton.setBackgroundColor(greyColor);
                            gcsYaw.setBackgroundColor(whiteColor);
                            gcsPitch.setBackgroundColor(whiteColor);
                            gcsRoll.setBackgroundColor(whiteColor);
                            gcsGestureButtonClicked = false;
                            handler.removeCallbacks(sendGCSGestureRunnable);
                            getDrone().sendRcOverride(4, 0);// cancel the override value
                    }
                    return false;
                }
            });
        }
    }
    @Override
    public void onStart() {
        super.onStart();
        getActivity().registerReceiver(gcsEventReceiver, gcsEventFilter);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        headingModeFPV = prefs.getBoolean("pref_heading_mode", false);
    }

    @Override
    public void onApiConnected() {
        initGCSGestureButton(this.getView());
        updateAllTelem();
        getBroadcastManager().registerReceiver(eventReceiver, eventFilter);
    }

    @Override
    public void onResume(){
        super.onResume();
        tryStreamingVideo();
    }

    @Override
    public void onPause(){
        super.onPause();
        tryStoppingVideoStream();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getActivity().unregisterReceiver(gcsEventReceiver);
    }

    @Override
    public void onApiDisconnected() {
        tryStoppingVideoStream();
        getBroadcastManager().unregisterReceiver(eventReceiver);
    }

    private void updateAllTelem() {
        onOrientationUpdate();
        onSpeedUpdate();
        onGCSAttitudeUpdate();
        tryStreamingVideo();
    }

    private void tryStoppingVideoStream() {
        final Drone drone = getDrone();
        Timber.d("Stopping video stream with tag %s.", TAG);
        SoloCameraApi.getApi(drone).stopVideoStream(TAG, new AbstractCommandListener() {
            @Override
            public void onSuccess() {
                Timber.d("Video streaming stopped successfully.");
            }

            @Override
            public void onError(int i) {
                Timber.d("Unable to stop video streaming: %d", i);
            }

            @Override
            public void onTimeout() {
                Timber.d("Timed out while trying to stop video streaming.");
            }
        });
    }

    private void tryStreamingVideo() {
        final Drone drone = getDrone();
        CapabilityApi.getApi(drone).checkFeatureSupport(CapabilityApi.FeatureIds.SOLO_VIDEO_STREAMING, new CapabilityApi.FeatureSupportListener() {
            @Override
            public void onFeatureSupportResult(String featureId, int result, Bundle bundle) {
                switch (result) {
                    case CapabilityApi.FEATURE_SUPPORTED:
                        if (videoContainer != null) {
                            videoContainer.setVisibility(View.VISIBLE);
                            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                                @Override
                                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                                    Timber.d("Starting video with tag %s", TAG);
                                    SoloCameraApi.getApi(drone).startVideoStream(new Surface(surface), TAG, new SimpleCommandListener() {

                                        @Override
                                        public void onSuccess() {
                                            Timber.d("Video started successfully.");
                                        }

                                        @Override
                                        public void onError(int i) {
                                            Timber.d("Starting video error: %d", i);
                                        }

                                        @Override
                                        public void onTimeout() {
                                            Timber.d("Starting video timeout.");
                                        }
                                    });
                                }

                                @Override
                                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

                                }

                                @Override
                                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                                    tryStoppingVideoStream();
                                    return true;
                                }

                                @Override
                                public void onSurfaceTextureUpdated(SurfaceTexture surface) {

                                }
                            });
                        }
                        break;

                    default:
                        if (videoContainer != null) {
                            videoContainer.setVisibility(View.GONE);
                        }
                }
            }
        });
    }

    private void onOrientationUpdate() {
        final Drone drone = getDrone();

        final Attitude attitude = drone.getAttribute(AttributeType.ATTITUDE);
        if (attitude == null)
            return;

        float r = (float) attitude.getRoll();
        float p = (float) attitude.getPitch();
        float y = (float) attitude.getYaw();

        if (!headingModeFPV & y < 0) {
            y = 360 + y;
        }

        attitudeIndicator.setAttitude(r, p, y);

        roll.setText(String.format("%3.0f\u00B0", r));
        pitch.setText(String.format("%3.0f\u00B0", p));
        yaw.setText(String.format("%3.0f\u00B0", y));

    }

    private void onSpeedUpdate() {
        final Drone drone = getDrone();
        final Speed speed = drone.getAttribute(AttributeType.SPEED);

        final double groundSpeedValue = speed != null ? speed.getGroundSpeed() : 0;
        final double verticalSpeedValue = speed != null ? speed.getVerticalSpeed() : 0;

        final SpeedUnitProvider speedUnitProvider = getSpeedUnitProvider();
//        Log.e(TAG, "speed updated");
        horizontalSpeed.setText(getString(R.string.horizontal_speed_telem, speedUnitProvider.boxBaseValueToTarget(groundSpeedValue).toString()));
        verticalSpeed.setText(getString(R.string.vertical_speed_telem, speedUnitProvider.boxBaseValueToTarget(verticalSpeedValue).toString()));
    }


    public void onGCSAttitudeUpdate() {
        long actualTime = System.currentTimeMillis();
        //only change the display
        if ((actualTime-gcsAttLastUpdate)>UPDATE_INTERVAL) {
//          Attitude gcsAtt = intent.getParcelableExtra(GCSEvent.GCS_ATTITUDE_UPDATED);
            gcsAtt = getControlTower().getGCSAttitude();
            gcsAttLocked = getControlTower().getGCSAttitudeLocked();
            final double R2D = 180.0 / Math.PI;
            final double yawValue = gcsAtt != null ? gcsAtt.getYaw() * R2D : 0;
            final double pitchValue = gcsAtt != null ? gcsAtt.getPitch() * R2D : 0;
            final double rollValue = gcsAtt != null ? gcsAtt.getRoll() * R2D : 0;

            final SpeedUnitProvider speedUnitProvider = getSpeedUnitProvider();
//          Log.e(TAG, "gcs attitude updated");
            gcsYaw.setText(getString(R.string.gcs_yaw_telem, speedUnitProvider.boxBaseValueToTarget(yawValue).toString()));
            gcsPitch.setText(getString(R.string.gcs_pitch_telem, speedUnitProvider.boxBaseValueToTarget(pitchValue).toString()));
            gcsRoll.setText(getString(R.string.gcs_roll_telem, speedUnitProvider.boxBaseValueToTarget(rollValue).toString()));
            gcsAttLastUpdate = actualTime;
        }
    }
}
