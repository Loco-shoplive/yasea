package cloud.shoplive.studio;

import android.media.AudioRecord;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.github.faucamp.simplertmp.RtmpHandler;
import com.seu.magicfilter.utils.MagicFilterType;

import net.ossrs.yasea.SrsEncoder;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by Leo Ma on 2016/7/25.
 */
@Keep
public class ShopLivePublisher {

    private static AudioRecord mic;
    private static AcousticEchoCanceler aec;
    private static AutomaticGainControl agc;
    private byte[] mPcmBuffer = new byte[4096];
    @Nullable
    private Future<Void> worker;

    @NonNull
    private LifecycleOwner lifecycleOwner;
    @NonNull
    private ShopLiveCameraView cameraView;

    private boolean sendVideoOnly = false;
    private boolean sendAudioOnly = false;
    private int videoFrameCount;
    private long lastTimeMillis;
    private double mSamplingFps;

    private ShopLiveFlvMuxer mFlvMuxer;
    private ShopLiveMp4Muxer mMp4Muxer;
    private SrsEncoder mEncoder;

    public ShopLivePublisher(@NonNull LifecycleOwner lifecycleOwner, @NonNull ShopLiveCameraView view) {
        this.lifecycleOwner = lifecycleOwner;
        cameraView = view;
        cameraView.setPreviewCallback(new ShopLiveCameraView.PreviewCallback() {
            @Override
            public void onGetRgbaFrame(byte[] data, int width, int height) {
                calcSamplingFps();
                if (!sendAudioOnly) {
                    mEncoder.onGetRgbaFrame(data, width, height);
                }
            }
        });
    }

    public void addLifecycleObserver() {
        lifecycleOwner.getLifecycle().addObserver(new LifecycleEventObserver() {
            @Override
            public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
                switch (event) {
                    case ON_START: {
                        if(!hasCamera()){
                            startCamera();
                        }
                        break;
                    }
                    case ON_RESUME: {
                        resumeRecord();
                        break;
                    }
                    case ON_PAUSE: {
                        pauseRecord();
                        break;
                    }
                    case ON_DESTROY: {
                        stopPublish();
                        stopRecord();
                        break;
                    }
                }
            }
        });
    }

    private void calcSamplingFps() {
        // Calculate sampling FPS
        if (videoFrameCount == 0) {
            lastTimeMillis = System.nanoTime() / 1000000;
            videoFrameCount++;
        } else {
            if (++videoFrameCount >= SrsEncoder.VGOP) {
                long diffTimeMillis = System.nanoTime() / 1000000 - lastTimeMillis;
                mSamplingFps = (double) videoFrameCount * 1000 / diffTimeMillis;
                videoFrameCount = 0;
            }
        }
    }

    public void startCamera() {
        cameraView.startCamera(lifecycleOwner);
    }

    public void stopCamera() {
        cameraView.stopCamera();
    }

    public void startAudio() {
        mic = mEncoder.chooseAudioRecord();
        if (mic == null) {
            return;
        }

        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(mic.getAudioSessionId());
            if (aec != null) {
                //aec.setEnabled(true); //android 11 issue low volume
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(mic.getAudioSessionId());
            if (agc != null) {
                agc.setEnabled(true);
            }
        }

        worker = Executors.newCachedThreadPool().submit((Callable<Void>) () -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            mic.startRecording();
            while (worker != null && !worker.isDone()) {
                if (sendVideoOnly) {
                    mEncoder.onGetPcmFrame(mPcmBuffer, mPcmBuffer.length);
                    try {
                        // This is trivial...
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        break;
                    }
                } else {
                    int size = mic.read(mPcmBuffer, 0, mPcmBuffer.length);
                    if (size > 0) {
                        mEncoder.onGetPcmFrame(mPcmBuffer, size);
                    }
                }
            }
            return null;
        });
    }

    public void stopAudio() {
        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
            worker = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }

        if (aec != null) {
            aec.setEnabled(false);
            aec.release();
            aec = null;
        }

        if (agc != null) {
            agc.setEnabled(false);
            agc.release();
            agc = null;
        }
    }

    public void startEncode() {
        if (!mEncoder.start()) {
            return;
        }

        cameraView.enableEncoding();

        startAudio();
    }

    public void stopEncode() {
        stopAudio();
        stopCamera();
        mEncoder.stop();
    }

    public void pauseEncode() {
        stopAudio();
        cameraView.disableEncoding();
    }

    private void resumeEncode() {
        startAudio();
        cameraView.enableEncoding();
    }

    public void startPublish(String rtmpUrl) {
        if (mFlvMuxer != null) {
            mFlvMuxer.start(rtmpUrl);
            mFlvMuxer.setVideoResolution(mEncoder.getOutputWidth(), mEncoder.getOutputHeight());
            startEncode();
        }
    }

    public void resumePublish() {
        if (mFlvMuxer != null) {
            mEncoder.resume();
            resumeEncode();
        }
    }

    public void stopPublish() {
        if (mFlvMuxer != null) {
            stopEncode();
            mFlvMuxer.stop();
        }
    }

    public void pausePublish() {
        if (mFlvMuxer != null) {
            mEncoder.pause();
            pauseEncode();
        }
    }

    public boolean startRecord(String recPath) {
        return mMp4Muxer != null && mMp4Muxer.record(new File(recPath));
    }

    public void stopRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.stop();
        }
    }

    public void pauseRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.pause();
        }
    }

    public void resumeRecord() {
        if (mMp4Muxer != null) {
            mMp4Muxer.resume();
        }
    }

    public boolean isAllFramesUploaded() {
        return mFlvMuxer.getVideoFrameCacheNumber().get() == 0;
    }

    public int getVideoFrameCacheCount() {
        if (mFlvMuxer != null) {
            return mFlvMuxer.getVideoFrameCacheNumber().get();
        }
        return 0;
    }

    public void switchToSoftEncoder() {
        mEncoder.switchToSoftEncoder();
    }

    public void switchToHardEncoder() {
        mEncoder.switchToHardEncoder();
    }

    public boolean isSoftEncoder() {
        return mEncoder.isSoftEncoder();
    }

    public int getPreviewWidth() {
        return mEncoder.getPreviewWidth();
    }

    public int getPreviewHeight() {
        return mEncoder.getPreviewHeight();
    }

    public double getmSamplingFps() {
        return mSamplingFps;
    }

    public int getCameraId() {
        return cameraView.getCameraId();
    }

    public boolean hasCamera() {
        return cameraView.getCamera() != null;
    }

    public void setPreviewResolution(int width, int height) {
        int resolution[] = cameraView.setPreviewResolution(width, height);
        mEncoder.setPreviewResolution(resolution[0], resolution[1]);
    }

    public void setOutputResolution(int width, int height) {
        if (width <= height) {
            mEncoder.setPortraitResolution(width, height);
        } else {
            mEncoder.setLandscapeResolution(width, height);
        }
    }

    public void setVideoHDMode() {
        mEncoder.setVideoHDMode();
    }

    public void setVideoSmoothMode() {
        mEncoder.setVideoSmoothMode();
    }

    public void setSendVideoOnly(boolean flag) {
        if (mic != null) {
            if (flag) {
                mic.stop();
                mPcmBuffer = new byte[4096];
            } else {
                mic.startRecording();
            }
        }
        sendVideoOnly = flag;
    }

    public void setSendAudioOnly(boolean flag) {
        sendAudioOnly = flag;
    }

    public boolean switchCameraFilter(MagicFilterType type) {
        return cameraView.setFilter(type);
    }

    public void switchCameraFace() {

        if (mEncoder != null && mEncoder.isEnabled()) {
            mEncoder.pause();
        }

        cameraView.stopCamera();
        int face = cameraView.toggleCameraFace();
        cameraView.setCameraId(face);
        if (face == CameraSelector.LENS_FACING_BACK) {
            mEncoder.setCameraBackFace();
        } else {
            mEncoder.setCameraFrontFace();
        }
        if (mEncoder != null && mEncoder.isEnabled()) {
            cameraView.enableEncoding();
        }
        cameraView.startCamera(lifecycleOwner);

        if (mEncoder != null && mEncoder.isEnabled()) {
            mEncoder.resume();
        }

    }

    public void setRtmpHandler(RtmpHandler handler) {
        mFlvMuxer = new ShopLiveFlvMuxer(handler);
        if (mEncoder != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
    }

    public void setRecordHandler(ShopLiveRecordHandler handler) {
        mMp4Muxer = new ShopLiveMp4Muxer(handler);
        if (mEncoder != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }

    public void setEncodeHandler(ShopLiveEncodeHandler handler) {
        mEncoder = new SrsEncoder(handler);
        if (mFlvMuxer != null) {
            mEncoder.setFlvMuxer(mFlvMuxer);
        }
        if (mMp4Muxer != null) {
            mEncoder.setMp4Muxer(mMp4Muxer);
        }
    }
}
