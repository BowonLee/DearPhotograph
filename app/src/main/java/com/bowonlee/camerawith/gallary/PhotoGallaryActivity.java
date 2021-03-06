package com.bowonlee.camerawith.gallary;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.bowonlee.camerawith.Manifest;
import com.bowonlee.camerawith.PermissionHelper;
import com.bowonlee.camerawith.R;

import java.util.ArrayList;

public class PhotoGallaryActivity extends AppCompatActivity{

    private static final int REQUEST_PERMISSION = 3;

  public static final int REQUEST_CODE = 4001;

    private RecyclerView mGridPhotoGallary;
    private PhotoAdapter mPhotoAdapter;
    private ArrayList<String> albumList;
    private Spinner mSpinnerAlbumList;

    private PermissionHelper mPermissionHelper;

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallary);
        mGridPhotoGallary = (RecyclerView) findViewById(R.id.gallary_recyclerview);
        mSpinnerAlbumList = (Spinner)findViewById(R.id.spinner_gallary_albumlist);

        checkPermission();




    }

    private void setGridlayout(){
        mGridPhotoGallary.setLayoutManager(new GridLayoutManager(null,3, LinearLayoutManager.VERTICAL,false));


    }
    @Override
    protected void onResume() {
        super.onResume();
    }
    @Override
    protected void onPause() {
        super.onPause();
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void checkPermission(){
      mPermissionHelper = new PermissionHelper(this);

      if(!mPermissionHelper.checkAllPermissions()){
          mPermissionHelper.requestAllPermission();
      }else{
          setGridlayout();
          setPhotoAdapter();
          getBucketDisplayList();
          setSpinnerAlbumList();
      }

    }

    private void setPhotoAdapter(){
        mPhotoAdapter = new PhotoAdapter(this,getString(R.string.gallary_all_albums));
        getLoaderManager().initLoader(0,null,mPhotoAdapter);
        mGridPhotoGallary.setAdapter(mPhotoAdapter);
    }


    private void setSpinnerAlbumList(){

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,R.layout.support_simple_spinner_dropdown_item,albumList);
        mSpinnerAlbumList.setAdapter(adapter);
        mSpinnerAlbumList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mPhotoAdapter.setAlbumName(parent.getItemAtPosition(position).toString());
                getLoaderManager().restartLoader(0,null,mPhotoAdapter);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void getBucketDisplayList() {
        String bucket;
        albumList = new ArrayList<>();

        if(!albumList.contains(getString(R.string.gallary_all_albums)))
            albumList.add(getString(R.string.gallary_all_albums));

        Cursor cursor = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[] {
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.TITLE},null, null, null);

        if (cursor != null && cursor.moveToFirst()) {

            int bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            do {
                bucket = cursor.getString(bucketColumn);
                if(!albumList.contains(bucket))
                    albumList.add(bucket);
            } while (cursor.moveToNext());
            cursor.close();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
       if(mPermissionHelper.setRequestPermissionsResult()){
           setGridlayout();
           setPhotoAdapter();
           getBucketDisplayList();
           setSpinnerAlbumList();
       }
    }


}
