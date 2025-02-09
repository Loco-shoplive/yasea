package net.ossrs.yasea.demo;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.utils.MagicFilterType;

import cloud.shoplive.studio.ShopLiveCameraView;
import cloud.shoplive.studio.ShopLiveEncodeHandler;
import cloud.shoplive.studio.ShopLivePublisher;
import cloud.shoplive.studio.ShopLiveRecordHandler;

import java.io.IOException;
import java.net.SocketException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Yasea";
    public final static int RC_CAMERA = 100;

    private Button btnPublish;
    private Button btnSwitchCamera;
    private Button btnSwitchEncoder;
    private Button btnPause;

    private SharedPreferences sp;
    private String rtmpUrl = "rtmp://dev-stream.shoplive.cloud:1935/stream/dev-1-2oHaHijHSRQgGlb";

    private ShopLivePublisher mPublisher;
    private ShopLiveCameraView mCameraView;

    private int mWidth = 1080;
    private int mHeight = 1920;
    private boolean isPermissionGranted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        requestPermission();
    }

    private void requestPermission() {
        //1. 检查是否已经有该权限
        if (Build.VERSION.SDK_INT >= 23 && (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)) {
            //2. 权限没有开启，请求权限
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE}, RC_CAMERA);
        }else{
            //权限已经开启，做相应事情
            isPermissionGranted = true;
            init();
        }
    }

    //3. 接收申请成功或者失败回调
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //权限被用户同意,做相应的事情
                isPermissionGranted = true;
                init();
            } else {
                //权限被用户拒绝，做相应的事情
                finish();
            }
        }
    }

    private void init() {
        // restore data.
        sp = getSharedPreferences("Yasea", MODE_PRIVATE);
        rtmpUrl = sp.getString("rtmpUrl", rtmpUrl);

        // initialize url.
        final EditText efu = (EditText) findViewById(R.id.url);
        efu.setText(rtmpUrl);

        btnPublish = (Button) findViewById(R.id.publish);
        btnSwitchCamera = (Button) findViewById(R.id.swCam);
        btnSwitchEncoder = (Button) findViewById(R.id.swEnc);
        btnPause = (Button) findViewById(R.id.pause);
        btnPause.setEnabled(false);
        mCameraView = (ShopLiveCameraView) findViewById(R.id.glsurfaceview_camera);

        mPublisher = new ShopLivePublisher(this, mCameraView);
        mPublisher.setEncodeHandler(new ShopLiveEncodeHandler(new ShopLiveEncodeHandler.ShopLiveEncodeListener() {
            @Override
            public void onNetworkWeak() {
                Toast.makeText(getApplicationContext(), "Network weak", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNetworkResume() {
                Toast.makeText(getApplicationContext(), "Network resume", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onEncodeIllegalArgumentException(IllegalArgumentException e) {
                handleException(e);
            }
        }));
        mPublisher.setRtmpHandler(new RtmpHandler(new RtmpHandler.RtmpListener() {

            @Override
            public void onRtmpConnecting(String msg) {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRtmpConnected(String msg) {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRtmpVideoStreaming() {
            }

            @Override
            public void onRtmpAudioStreaming() {
            }

            @Override
            public void onRtmpStopped() {
                Toast.makeText(getApplicationContext(), "Stopped", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRtmpDisconnected() {
                Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRtmpVideoFpsChanged(double fps) {
                Log.i(TAG, String.format("Output Fps: %f", fps));
            }

            @Override
            public void onRtmpVideoBitrateChanged(double bitrate) {
                int rate = (int) bitrate;
                if (rate / 1000 > 0) {
                    Log.i(TAG, String.format("Video bitrate: %f kbps", bitrate / 1000));
                } else {
                    Log.i(TAG, String.format("Video bitrate: %d bps", rate));
                }
            }

            @Override
            public void onRtmpAudioBitrateChanged(double bitrate) {
                int rate = (int) bitrate;
                if (rate / 1000 > 0) {
                    Log.i(TAG, String.format("Audio bitrate: %f kbps", bitrate / 1000));
                } else {
                    Log.i(TAG, String.format("Audio bitrate: %d bps", rate));
                }
            }

            @Override
            public void onRtmpSocketException(SocketException e) {
                handleException(e);
            }

            @Override
            public void onRtmpIOException(IOException e) {
                handleException(e);
            }

            @Override
            public void onRtmpIllegalArgumentException(IllegalArgumentException e) {
                handleException(e);
            }

            @Override
            public void onRtmpIllegalStateException(IllegalStateException e) {
                handleException(e);
            }
        }));
        mPublisher.setRecordHandler(new ShopLiveRecordHandler(new ShopLiveRecordHandler.ShopLiveRecordListener() {
            @Override
            public void onRecordPause() {
                Toast.makeText(getApplicationContext(), "Record paused", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecordResume() {
                Toast.makeText(getApplicationContext(), "Record resumed", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecordStarted(String msg) {
                Toast.makeText(getApplicationContext(), "Recording file: " + msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecordFinished(String msg) {
                Toast.makeText(getApplicationContext(), "MP4 file saved: " + msg, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRecordIOException(IOException e) {
                handleException(e);
            }

            @Override
            public void onRecordIllegalArgumentException(IllegalArgumentException e) {
                handleException(e);
            }
        }));
        mPublisher.setPreviewResolution(mWidth, mHeight);
        mPublisher.setOutputResolution(mWidth, mHeight); // 这里要和preview反过来
        mPublisher.setVideoHDMode();
        mPublisher.startCamera();
        mPublisher.addLifecycleObserver();
//
//        mCameraView.setCameraCallbacksHandler(new ShopLiveCameraView.CameraCallbacksHandler(){
//            @Override
//            public void onCameraParameters(Camera.Parameters params) {
//                //params.setFocusMode("custom-focus");
//                //params.setWhiteBalance("custom-balance");
//                //etc...
//            }
//        });

        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnPublish.getText().toString().contentEquals("publish")) {
                    rtmpUrl = efu.getText().toString();
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("rtmpUrl", rtmpUrl);
                    editor.apply();

                    mPublisher.startPublish(rtmpUrl);
                    mPublisher.startCamera();

                    if (btnSwitchEncoder.getText().toString().contentEquals("soft encoder")) {
                        Toast.makeText(getApplicationContext(), "Use hard encoder", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getApplicationContext(), "Use soft encoder", Toast.LENGTH_SHORT).show();
                    }
                    btnPublish.setText("stop");
                    btnSwitchEncoder.setEnabled(false);
                    btnPause.setEnabled(true);
                } else if (btnPublish.getText().toString().contentEquals("stop")) {
                    mPublisher.stopPublish();
                    mPublisher.stopRecord();
                    btnPublish.setText("publish");
                    btnSwitchEncoder.setEnabled(true);
                    btnPause.setEnabled(false);
                }
            }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(btnPause.getText().toString().equals("Pause")){
                    mPublisher.pausePublish();
                    btnPause.setText("resume");
                }else{
                    mPublisher.resumePublish();
                    btnPause.setText("Pause");
                }
            }
        });

        btnSwitchCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPublisher.switchCameraFace();
            }
        });

        btnSwitchEncoder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnSwitchEncoder.getText().toString().contentEquals("soft encoder")) {
                    mPublisher.switchToSoftEncoder();
                    btnSwitchEncoder.setText("hard encoder");
                } else if (btnSwitchEncoder.getText().toString().contentEquals("hard encoder")) {
                    mPublisher.switchToHardEncoder();
                    btnSwitchEncoder.setText("soft encoder");
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        } else {
            switch (id) {
                case R.id.cool_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.COOL);
                    break;
                case R.id.beauty_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.BEAUTY);
                    break;
                case R.id.early_bird_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.EARLYBIRD);
                    break;
                case R.id.evergreen_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.EVERGREEN);
                    break;
                case R.id.n1977_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.N1977);
                    break;
                case R.id.nostalgia_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.NOSTALGIA);
                    break;
                case R.id.romance_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.ROMANCE);
                    break;
                case R.id.sunrise_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.SUNRISE);
                    break;
                case R.id.sunset_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.SUNSET);
                    break;
                case R.id.tender_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.TENDER);
                    break;
                case R.id.toast_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.TOASTER2);
                    break;
                case R.id.valencia_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.VALENCIA);
                    break;
                case R.id.walden_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.WALDEN);
                    break;
                case R.id.warm_filter:
                    mPublisher.switchCameraFilter(MagicFilterType.WARM);
                    break;
                case R.id.original_filter:
                default:
                    mPublisher.switchCameraFilter(MagicFilterType.NONE);
                    break;
            }
        }
        setTitle(item.getTitle());

        return super.onOptionsItemSelected(item);
    }

//    @Override
//    protected void onStart() {
//        super.onStart();
//        if(!mPublisher.hasCamera() && isPermissionGranted){
//            mPublisher.startCamera();
//        }
//    }
//
//    @Override
//    protected void onResume() {
//        super.onResume();
//        final Button btn = (Button) findViewById(R.id.publish);
//        btn.setEnabled(true);
//        mPublisher.resumeRecord();
//    }
//
//    @Override
//    protected void onPause() {
//        super.onPause();
//        mPublisher.pauseRecord();
//    }
//
//    @Override
//    protected void onDestroy() {
//        super.onDestroy();
//        mPublisher.stopPublish();
//        mPublisher.stopRecord();
//    }

    private void handleException(Exception e) {
        try {
            Log.e(TAG, e.toString());
            Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            mPublisher.stopPublish();
            mPublisher.stopRecord();
            btnPublish.setText("publish");
            btnSwitchEncoder.setEnabled(true);
        } catch (Exception e1) {
            //
        }
    }
}
