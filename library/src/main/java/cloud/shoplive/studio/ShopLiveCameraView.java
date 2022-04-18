package cloud.shoplive.studio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGLUtils;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

@Keep
public class ShopLiveCameraView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private final String TAG = this.getClass().getName();

    @Nullable
    private GPUImageFilter magicFilter = null;
    @Nullable
    private SurfaceTexture surfaceTexture = null;
    private int textureId = OpenGLUtils.NO_TEXTURE;
    private int surfaceWidth;
    private int surfaceHeight;
    private int previewWidth;
    private int previewHeight;
    private final AtomicBoolean isEncoding = new AtomicBoolean(false);
    private float inputAspectRatio;
    private float outputAspectRatio;
    private final float[] projectionMatrix = new float[16];
    private final float[] surfaceMatrix = new float[16];
    private final float[] transformMatrix = new float[16];

    @Nullable
    private ByteBuffer bufferGLPreview = null;
    @CameraSelector.LensFacing
    private int camFacingId = CameraSelector.LENS_FACING_BACK;
    private int previewOrientation = Configuration.ORIENTATION_PORTRAIT;

    @Nullable
    private Camera camera = null;
    @Nullable
    private Preview preview = null;
    @Nullable
    private Future<Void> worker = null;
    private final Object writeLock = new Object();
    private final ConcurrentLinkedQueue<IntBuffer> bufferCache = new ConcurrentLinkedQueue<>();
    @Nullable
    private PreviewCallback previewCallback = null;
    private CameraCallbacksHandler cameraCallbacksHandler = new CameraCallbacksHandler();

    public ShopLiveCameraView(Context context) {
        this(context, null);
    }

    @SuppressLint("ClickableViewAccessibility")
    public ShopLiveCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (camera == null) {
                    return true;
                }
                MeteringPoint point = new SurfaceOrientedMeteringPointFactory(1f, 1f)
                        .createPoint(.5f, .5f);

                camera.getCameraControl().startFocusAndMetering(
                        new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
                );
            }
            return true;
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);

        magicFilter = new GPUImageFilter(MagicFilterType.NONE);
        magicFilter.init(getContext().getApplicationContext());
        magicFilter.onInputSizeChanged(previewWidth, previewHeight);

        textureId = OpenGLUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(surfaceTexture -> requestRender());
    }

    public void startCamera(LifecycleOwner lifecycleOwner) {
        final CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(camFacingId).build();
        final ListenableFuture<ProcessCameraProvider> listenableFuture = ProcessCameraProvider.getInstance(getContext());
        listenableFuture.addListener(() -> {
            try {
                if (camera != null) {
                    return;
                }
                ProcessCameraProvider cameraProvider = listenableFuture.get();
                preview = new Preview.Builder()
                        .setTargetResolution(new Size(previewWidth, previewHeight))
                        .build();


                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview);
                cameraCallbacksHandler.onCameraParameter(camera);

                Preview.SurfaceProvider surfaceProvider = request -> {
                    if (surfaceTexture == null) {
                        return;
                    }
                    Size resolution = request.getResolution();

                    surfaceTexture.setDefaultBufferSize(resolution.getWidth(), resolution.getHeight());
                    Surface surface = new Surface(surfaceTexture);
                    request.provideSurface(surface, ContextCompat.getMainExecutor(getContext()), result -> {
                        Log.i(TAG, result.toString());
                    });
                };
                preview.setSurfaceProvider(surfaceProvider);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        surfaceWidth = width;
        surfaceHeight = height;
        if (magicFilter != null) {
            magicFilter.onDisplaySizeChanged(width, height);
            magicFilter.onInputSizeChanged(previewWidth, previewHeight);
        }

        outputAspectRatio = width > height ? (float) width / height : (float) height / width;
        float aspectRatio = outputAspectRatio / inputAspectRatio;
        if (width > height) {
            Matrix.orthoM(projectionMatrix, 0, -1.0f, 1.0f, -aspectRatio, aspectRatio, -1.0f, 1.0f);
        } else {
            Matrix.orthoM(projectionMatrix, 0, -aspectRatio, aspectRatio, -1.0f, 1.0f, -1.0f, 1.0f);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (surfaceWidth != previewWidth || surfaceHeight != previewHeight) {
            //May be a buffer overflow in enableEncoding()
            //mPreviewWidth changed but onSurfaceCreated fired after enable encoding (mIsEncoding == true)
            //could be calling magicFilter.onInputSizeChanged(width, height) in setPreviewResolution() after changing mGLPreviewBuffer?
            //or start the encoder only after onSurfaceCreated ...            
            Log.e(TAG, String.format("Surface dimensions differ from Preview. May be a buffer overflow. Surface: %dx%d, Preview: %dx%d ", surfaceWidth, surfaceHeight, previewWidth, previewHeight));
            return;
        }

        if (surfaceTexture == null) {
            Log.e(TAG, "SurfaceTexture is Null");
            return;
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(surfaceMatrix);
        Matrix.multiplyMM(transformMatrix, 0, surfaceMatrix, 0, projectionMatrix, 0);
        if (magicFilter != null) {
            magicFilter.setTextureTransformMatrix(transformMatrix);
            magicFilter.onDrawFrame(textureId);
        }

        if (isEncoding.get()) {
            bufferCache.add(magicFilter.getGLFboBuffer());
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }
    }

    public void setPreviewCallback(@Nullable PreviewCallback callback) {
        previewCallback = callback;
    }

    @Nullable
    public Camera getCamera() {
        return this.camera;
    }

    public int[] setPreviewResolution(int width, int height) {
        previewWidth = width;
        previewHeight = height;

        getHolder().setFixedSize(previewWidth, previewHeight);

        bufferGLPreview = ByteBuffer.allocate(previewWidth * previewHeight * 4);
        inputAspectRatio = previewWidth > previewHeight ?
                (float) previewWidth / previewHeight : (float) previewHeight / previewWidth;

        return new int[]{previewWidth, previewHeight};
    }

    public boolean setFilter(final MagicFilterType type) {
        if (camera == null) {
            return false;
        }

        queueEvent(() -> {
            if (magicFilter != null) {
                magicFilter.destroy();
            }
            magicFilter = MagicFilterFactory.initFilters(type);
            if (magicFilter != null) {
                magicFilter.init(getContext().getApplicationContext());
                magicFilter.onInputSizeChanged(previewWidth, previewHeight);
                magicFilter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
            }
        });
        requestRender();
        return true;
    }

    private void deleteTextures() {
        if (textureId != OpenGLUtils.NO_TEXTURE) {
            queueEvent(() -> {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = OpenGLUtils.NO_TEXTURE;
            });
        }
    }

    @CameraSelector.LensFacing
    public int toggleCameraFace() {
        if (camFacingId == CameraSelector.LENS_FACING_FRONT) {
            camFacingId = CameraSelector.LENS_FACING_BACK;
        } else {
            camFacingId = CameraSelector.LENS_FACING_FRONT;
        }
        return camFacingId;
    }

    public void setCameraId(@CameraSelector.LensFacing int id) {
        camFacingId = id;
    }

    protected int getRotateDegree() {
        try {
            int rotate = ((Activity) getContext()).getWindowManager().getDefaultDisplay().getRotation();
            switch (rotate) {
                case Surface.ROTATION_0:
                    return 0;
                case Surface.ROTATION_90:
                    return 90;
                case Surface.ROTATION_180:
                    return 180;
                case Surface.ROTATION_270:
                    return 270;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return -1;
    }

    @CameraSelector.LensFacing
    public int getCameraId() {
        return camFacingId;
    }

    public void enableEncoding() {
        if (isEncoding.getAndSet(true)) {
            return;
        }
        worker = Executors.newCachedThreadPool().submit((Callable<Void>) () -> {
            while (worker != null && !worker.isDone()) {
                while (!bufferCache.isEmpty()) {
                    try {
                        IntBuffer picture = bufferCache.poll();
                        bufferGLPreview.asIntBuffer().put(picture.array());
                        previewCallback.onGetRgbaFrame(bufferGLPreview.array(), previewWidth, previewHeight);
                    } catch (Exception e) {
                        Log.e(TAG, e.toString());
                        cameraCallbacksHandler.onError(e);
                        e.printStackTrace();
                        worker.cancel(true);
                        break;
                    }
                }
                // Waiting for next frame
                synchronized (writeLock) {
                    try {
                        // isEmpty() may take some time, so we set timeout to detect next frame
                        writeLock.wait(500);
                    } catch (InterruptedException ie) {
                        worker.cancel(true);
                    }
                }
            }
            return null;
        });
    }

    public void disableEncoding() {
        isEncoding.set(false);
        bufferCache.clear();
        if (bufferGLPreview != null) {
            bufferGLPreview.clear();
        }

        if (worker != null && !worker.isDone()) {
            worker.cancel(true);
            worker = null;
        }
    }

    public void stopCamera() {
        disableEncoding();

        if (camera != null) {
            camera = null;
        }
    }

    public interface PreviewCallback {

        void onGetRgbaFrame(byte[] data, int width, int height);
    }

    public static class CameraCallbacksHandler implements CameraCallbacks {

        @Override
        public void onCameraParameter(Camera camera) {

        }

        @Override
        public void onError(Exception e) {
            //stop publish
        }

    }

    public interface CameraCallbacks {
        void onCameraParameter(Camera camera);

        void onError(Exception e);
    }

    public void setCameraCallbacksHandler(CameraCallbacksHandler cameraCallbacksHandler) {
        this.cameraCallbacksHandler = cameraCallbacksHandler;
    }
}
