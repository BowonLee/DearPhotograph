package com.bowonlee.dearphotograph;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Created by bowon on 2018-03-28.
 */

public class CameraPreview {

    interface CameraInterface{
        void onPostTakePicture();
    }
    private CameraInterface anInterface;
    private static final String TAG = "Camera2Preview";

    /*Orientation of Camera*/
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private int mSensorOrientation;

    /*Camrara Status*/
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;
    private static final int STATE_WAITING_PRECAPTURE = 2;
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;
    private static final int STATE_PICTURE_TAKEN = 4;

    /*Device Size*/
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /*CameraComponent*/
    private String mCameraId;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
    private CaptureRequest mPreviewRequest;
    private CaptureRequest.Builder mPreviewRequestBuilder;


    /*PreviewComponent*/
    private Size mPreviewSize;
    private AutoFitTextureView mTextureView;

    /*For BackgroundThread*/
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    /*Syncronizing Semapore*/
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /*Camera Device Option*/
    private boolean mFlashSupported;

    /*For StillImageCapture */
    private ImageReader mImageReader;


    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            mBackgroundHandler.post(new ImageSaver(imageReader.acquireNextImage(),mContext));

        }
    };


    /*Use Activity */
    private Context mContext;

    /*Callback Method*/
    /*SurfaceTexture 관련 콜벡 Surface가 생성되면 호출되어 프리뷰세션을 생성한다.*/
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    /*
    * Device상태에 따른 카메라의 동작을 정의한 Callback Method이다.
    */

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            if(((Activity)mContext) != null){
            ((Activity)mContext).finish();
            }

        }
    };

    /*
    * imageCapture를 지원하는 callback method 이다.
    * 사진을 찍는 요청이 들어올 경우 작동한다.
    * */

    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        /**
         * AF : auto focus      : 자동 초점 조정
         * AE : auto exposure   : 자동 노출 - 빛 처리
         * */
        private void progress(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    //사진 촬영을 위한 요청이 없는 경우
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (afState == null) {
                        captureStillPicture();

                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                }
            }

        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            super.onCaptureProgressed(session, request, partialResult);
            progress(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            progress(result);



        }
    };

    /*CurrentState Parameter*/
    private int mState = STATE_PREVIEW;


    public CameraPreview(Context context, AutoFitTextureView mTextureView) {
        mContext = context;
        this.mTextureView = mTextureView;
        anInterface = (CameraInterface) context;
    }


    /*Camera Preview Session의 생성 관련 메소드*/
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            //assert를 이용하여 runtime에서 textureview의 상태를 점검한다.
            assert texture != null;

            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            Surface surface = new Surface(texture);

            //captureRequest를 통해 카메라 target surfave 설정
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            //실제 Preview 설정
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (null == mCameraDevice) {
                                return;
                            }
                            mCaptureSession = cameraCaptureSession;
                            try {
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                setAutoFlash(mPreviewRequestBuilder);

                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(TAG, "Failed In CreateCaptureSession");
                        }
                    }, null
            );


        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }


    /*
    * 카메라가 디바이스의 모든 화면을 사용하는 것이 아닌 경우 ImageCapture를 위한 메소드이다.
    * 일반적인 카메라로 찍은 사진의 Size = 디바이스의 사이즈 이다.
    *
    *
    * */
    private static Size chooseOptimalSize(Size[] choice, int textureViewWidth, int textureViewHeight
            , int maxWidth, int maxHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();

        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for (Size option : choice) {
          //  Log.e("chooing optimal",String.format("(%s), (%d,%d)",choice.length,option.getWidth(),option.getHeight()));

            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {

                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }

            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizeByArea());
        } else {
            Log.e(TAG, "Couldn' find any suitable preview size");
            return choice[0];
        }

    }


    /*Camera 사용에 있어 필요한 셋팅
    * open, close, setupoutput
    * */
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = (Activity) mContext;
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                //이 앱에서는 전방카라만을 사용한다.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }


                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                /*
                *
                * 해상도
                * 16 : 9 4160 2340
                *  4 : 3 4160 3120
                *  1 : 1 3120 3120
                * */
                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizeByArea());
                mImageReader = ImageReader.newInstance(4160,2340, ImageFormat.JPEG, 2);

                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();


                /*
                *   화면 회전시의 프리뷰사이즈변경 적용
                *   가로, 세로의 화면이 변경되면 각각 다른 프리뷰세션 크기를 설정한다.
                * */
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180: {
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    }
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid" + displayRotation);
                }

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                /*
                * 카메라의 한도를 넘어서 프리뷰사이즈를 지정하는 경우 프리뷰 자체에는 문제가 없을 수 있다.
                * 하지만 화면을 캡쳐해야 하는 경우 남은 공간에 쓰레기값이 들어가버려 이미지캡쳐가 제대로 작동하지 않는다.
                * */

                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                mPreviewSize = new Size(1280,720);
                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = mContext.getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                Boolean isAviable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = isAviable == null ? false : isAviable;

                mCameraId = cameraId;
                return;
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            e.printStackTrace();
        }

    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = (Activity) mContext;
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);

    }


    /*
    * Texture가 생성되 전
    * */
    public void setSurface() {
        mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
    }


    /*
    * 카메라 기기를 열고 닫는 쓰레드 부분
    * */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public void openCamera(int width, int height) {
        Activity activity = (Activity) mContext;

        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
           return;
        }


        setUpCameraOutputs(width, height);
        configureTransform(width, height);

        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening");
            }


            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.",e);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void closeCamera(){
        try{
            mCameraOpenCloseLock.acquire();
            if(null != mCaptureSession){
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if(null != mCameraDevice){
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if(null != mImageReader){
                mImageReader.close();
                mImageReader = null;
            }
        }catch (InterruptedException e){
            throw new RuntimeException("Interrupted while trying to lock camera closing.",e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }
    /*backgroundListener의 설정 및 시작*/
    public void startBackgroundThread(){
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

    }
    public void stopBackgroundThread(){
        mBackgroundThread.quitSafely();

        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /*
    * CaptureSession의 CaptureCallback에서 수행되는 내부 메서드들이다.
    * */

    /*
    * AF/AE를 지원하는 기기에서 AF/AE 설정이 되어있지 않은 경우 수행되며 설정 후
    * 디바이스의 상태를 WAITING_PRECAPTURE로 변경한 뒤 Callback을 호출한다.
    * */
    private void runPrecaptureSequence(){
        try{

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(),mCaptureCallback,mBackgroundHandler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }

    }




    /*
    * 디바이스의 사전 셋팅이 모두 끝난 뒤 captureBuilder를 호출하여 이미지를 저장하는 메서드이다.
    * CaptureBuilder의 호출 뒤에는 사진이 저장됨을 알리고, 촬영을 위한 셋팅을 모두 초기화시킨다.
    * */
    private void captureStillPicture(){
        try{
            final Activity activity = (Activity)mContext;
            if(null == activity || null == mCameraDevice){
                return;
            }
            /* 촬영한 사진은 여기서 CaptureRequest의 builder를 이용하여 사진을 저장한다. */
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    // 캡쳐가 완료되면 작업이 수행됨을 알려주거나, 후처리를 한다.
                    anInterface.onPostTakePicture();

                    unlockFocus();

                }
            };

            mCaptureSession.stopRepeating();
            mCaptureSession.abortCaptures();
            //captureBuiler 초기화
            mCaptureSession.capture(captureBuilder.build(),CaptureCallback,null);

        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    /* 사진의 저장을 위해 현제 화면의 상태를 알려준다*/
    private int getOrientation(int rotation){

        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    /*외부에서 사진 촬영요청이 오면 호출되어 state를 바꾸고 callback을 호출할 수 있게 해준다.*/
    public void takePicture(){
        lockFocus();
    }
    /*사진활영을 위한 화면 고정설정
    *
    * */
    private void lockFocus(){
      try{
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CameraMetadata.CONTROL_AF_TRIGGER_START);

          mState = STATE_WAITING_LOCK;
          mCaptureSession.capture(mPreviewRequestBuilder.build(),mCaptureCallback,mBackgroundHandler);
      }  catch (CameraAccessException e){
          e.printStackTrace();
      }
    }

    /*촬영이 끝나고 나서 촬영을 위해 한 셋팅을 다시 초기화 시킨다.*/
    private void unlockFocus(){
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(),mCaptureCallback,mBackgroundHandler);
            // 이 설정으로 인해 카메라는 원래 상태로 복귀한다.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest,mCaptureCallback,mBackgroundHandler);
        }catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    /* 카메라의 플레쉬 설정*/
    private void setAutoFlash(CaptureRequest.Builder requestBuilder){
        if(mFlashSupported){
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }




    /*카메라가 출력되는 Surface의 Size관련 Support */
    static class CompareSizeByArea implements Comparator<Size>{
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long)lhs.getWidth() * lhs.getHeight() - (long)rhs.getWidth() * rhs.getHeight());
        }
    }



}