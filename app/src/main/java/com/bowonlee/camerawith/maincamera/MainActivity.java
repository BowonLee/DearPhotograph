package com.bowonlee.camerawith.maincamera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.bowonlee.camerawith.Logger;
import com.bowonlee.camerawith.OrientationHelper;
import com.bowonlee.camerawith.models.ModifiedPhoto;
import com.bowonlee.camerawith.resultpreview.PreviewResultFragment;
import com.bowonlee.camerawith.R;
import com.crashlytics.android.Crashlytics;
import com.google.firebase.analytics.FirebaseAnalytics;

import io.fabric.sdk.android.Fabric;


@RequiresApi(api = Build.VERSION_CODES.M)
public class MainActivity extends AppCompatActivity implements CameraFragment.CameraInterface,
        OrientationHelper.OrientationChangeListener, PreviewResultFragment.PreviewResultInterface {

    private static final int REQUEST_PERMISSION_CAMERA = 3;
    private static final int REQUEST_PERMISSION_READ_STORAGE = 4;
    private static final int REQUEST_PERMISSION_WRITE_STORAGE = 5;

    private long mBackPressedTime = 0;

    private Sensor mAcellerometerSensor;
    private Sensor mMagneticSensor;
    private SensorManager mSensorManager;
    private OrientationHelper mSensorOrientation;
    private int currentOrientation;

    private FragmentManager mFragmentManager;
    private FragmentTransaction mFragmentTransaction;
    private CameraFragment mCameraFragment;
    private PreviewResultFragment mPreviewResultFragment;

    FirebaseAnalytics mFirebaseAnalytics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        Logger.checkDebuggable(this);
        setRequestCameraPermission();
        mSensorOrientation = new OrientationHelper();
        mSensorOrientation.setOnOrientationListener(this);
        startCameraFragment();
        setFireBase();
        Logger.e("tag","logtest");

    }

    private void setFireBase(){
        mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);
    }


    private void hideUi(){
                    getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideUi();
        setSensors();
        if(mCameraFragment!=null){ setSensorListener(); }

    }

    private void setSensors(){
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mAcellerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagneticSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

    }

    private void setSensorListener(){
        if(mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null&&mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)!=null) {
            mSensorManager.registerListener(mSensorOrientation.getEventListener(), mAcellerometerSensor, SensorManager.SENSOR_DELAY_NORMAL);
            mSensorManager.registerListener(mSensorOrientation.getEventListener(), mMagneticSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }else{

        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(mSensorOrientation.getEventListener());
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void setRequestCameraPermission(){

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED||
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ||checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){

            new AlertDialog.Builder(this).setMessage(R.string.request_caemra_permission)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int which) {
                            requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_PERMISSION_CAMERA);
                            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_PERMISSION_WRITE_STORAGE);
                            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},REQUEST_PERMISSION_READ_STORAGE);
                        }
                    }).setCancelable(false).show();
        }



    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {


        switch (requestCode){
            case REQUEST_PERMISSION_CAMERA :
            case REQUEST_PERMISSION_READ_STORAGE :
            case REQUEST_PERMISSION_WRITE_STORAGE :
            default : {
                try {
                 if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                 } else{
                     dialogPermissionDenied(permissions[0]); }
                }catch (ArrayIndexOutOfBoundsException e){
                    e.printStackTrace();
                }
            }
        }

    }

    private void dialogPermissionDenied(String permission){

        if(checkCallingOrSelfPermission(permission)==PackageManager.PERMISSION_DENIED) {

            new AlertDialog.Builder(this).setMessage(R.string.request_permission_re_needed).setPositiveButton("확인", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            }).setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    finish();
                }
            }).setCancelable(false).show();
        }
    }

    @Override
    public void onPostTakePicture(Bitmap captureBitmap,ModifiedPhoto modifiedPhoto) {
        startPreviewResultFragment(captureBitmap,modifiedPhoto);
    }

    private void startPreviewResultFragment(Bitmap captureBitmap,ModifiedPhoto modifiedPhoto){

        mPreviewResultFragment = new PreviewResultFragment();
        mFragmentManager = getSupportFragmentManager();
        mFragmentTransaction = mFragmentManager.beginTransaction();

        mPreviewResultFragment.setCapturedBitmap(captureBitmap);
        mPreviewResultFragment.setModifiedPhoto(modifiedPhoto);
        mPreviewResultFragment.setOrientation(currentOrientation);

        mFragmentTransaction.replace(R.id.main_container,mPreviewResultFragment).commit();
        mPreviewResultFragment.setPreviewResultInterface(this);
    }


    @Override
    public void onFinishPreviewResult(ModifiedPhoto photo) {

        restartCameraFragment(photo);
    }

    private void restartCameraFragment(ModifiedPhoto photo){

        mCameraFragment = CameraFragment.newInstance();

        mFragmentManager = getSupportFragmentManager();
        mFragmentTransaction = mFragmentManager.beginTransaction();

        mFragmentTransaction.replace(R.id.main_container,mCameraFragment).commit();
        mCameraFragment.setOnCameraInterface(this);


        mCameraFragment.setPostPhoto(photo);

    }

    private void startCameraFragment(){

        mCameraFragment = CameraFragment.newInstance();

        mFragmentManager = getSupportFragmentManager();
        mFragmentTransaction = mFragmentManager.beginTransaction();

        mFragmentTransaction.replace(R.id.main_container,mCameraFragment).commit();
        mCameraFragment.setOnCameraInterface(this);
    }


    @Override
    public void OnOrientationChanged(int orientation) {
        int itemOrientation;
        if(orientation == OrientationHelper.ORIENTATION_PORTRAIT || orientation == OrientationHelper.ORIENTATION_PORTRAIT_REVERSE){
            if(orientation == OrientationHelper.ORIENTATION_PORTRAIT){

                itemOrientation = OrientationHelper.ORIENTATION_PORTRAIT_ITEM;
            }else{

                itemOrientation = OrientationHelper.ORIENTATION_PORTRAIT_REVERSE_ITEM;
            }
        }else {
            if(orientation == OrientationHelper.ORIENTATION_LANDSCAPE){

                itemOrientation = OrientationHelper.ORIENTATION_LANDSCAPE_ITEM;
            }else{

                itemOrientation = OrientationHelper.ORIENTATION_LANDSCAPE_REVERSE_ITEM;
            }
        }
        rotateItemsByOrientation(itemOrientation);
    }
    public void rotateItemsByOrientation(int roation){
        if(mCameraFragment.isVisible()){
            currentOrientation = roation;
        }
    }

    @Override
    public void onBackPressed() {

        if(System.currentTimeMillis()>mBackPressedTime+2000){
                mBackPressedTime = System.currentTimeMillis();
                Toast.makeText(this,getString(R.string.main_back_button),Toast.LENGTH_SHORT).show();
                return;
        }
        if(System.currentTimeMillis()<=mBackPressedTime+2000){
            this.finish();
        }
    }




}
