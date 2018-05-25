package com.bowonlee.dearphotograph;


import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.Date;
import java.text.SimpleDateFormat;

public class BitmapSaver implements Runnable{

    private File mFile;
    private Context mContext;

    private FileIOHelper mFileIOHelper;
    private String mFileName;
    private Bitmap bitmap;
    public BitmapSaver(Bitmap bitmap, Context context){
        this.bitmap = bitmap;
        mContext = context;
    }

    @Override
    public void run() {


        long now = System.currentTimeMillis();
        Date date = new Date(now);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyymmddhhmmss");
        String getTime = sdf.format(date);
        mFileName = "dpf"+getTime +".jpg";


        mFileIOHelper = new FileIOHelper();
        mFile = new File(mFileIOHelper.getAlbumStorageDir("DearPhotograph"),mFileName);

        FileOutputStream output = null;

        try {
            output = new FileOutputStream(mFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG,100,output);
            output.close();
        } catch (IOException e) {
            e.printStackTrace();
        }


        MediaScannerConnection.scanFile(mContext, new String[]{mFile.toString()}, null, new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        Log.i("ExternalStorage","Scanned : " + path);
                        Log.i("ExternalStorage","URI : " + uri);
                    }
                }
        );

        Toast.makeText(mContext,String.format("Complete Save File : %s",mFile.toURI().getPath()),Toast.LENGTH_SHORT).show();
    }
}