package com.example.linpeiyou.imageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.annotation.NonNull;
import android.support.v4.util.LruCache;
import android.util.Log;
import android.widget.ImageView;

import com.example.linpeiyou.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by linpeiyou on 2017/9/16.
 *
 * 图片加载类
 */

public class ImageLoader {

    private static final String         TAG                     = "ImageLoader";

    private LruCache<String, Bitmap>    mMemoryCache;
    private DiskLruCache                mDiskLruCache;

    private static final int            CPU_COUNT               = Runtime.getRuntime().availableProcessors();
    private static final int            CORE_POOL_SIZE          = CPU_COUNT + 1;
    private static final int            MAXIMUM_POOL_SIZE       = CPU_COUNT * 2 + 1;
    private static final long           KEEP_ALIVE              = 10L;

    private static final long           DISK_CACHE_SIZE         = 1024 * 1024 * 50;
    private static final int            DISK_CACHE_INDEX        = 0;
    private static final int            IO_BUFFER_SIZE          = 8 * 1024;
    private static final int            TAG_KEY_URI             = R.id.imageloader_uri;
    private static final int            MESSAGE_POST_RESULT     = 1;

    private boolean                     mIsDiskLruCacheCreated  = false;
    private ImageResizer                mImageResizer           = new ImageResizer();
    private Context                     mContext;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);
        @Override
        public Thread newThread(@NonNull Runnable r) {
            return new Thread(r, "ImageLoader#" + mCount.getAndIncrement());
        }
    };

    private static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAXIMUM_POOL_SIZE,
            KEEP_ALIVE,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_POST_RESULT:
                {
                    LoaderResult loaderResult = (LoaderResult) msg.obj;
                    ImageView imageView = loaderResult.imageView;
                    if(imageView.getTag(TAG_KEY_URI).equals(loaderResult.uri)) {
                        imageView.setImageBitmap(loaderResult.bitmap);
                    } else {
                        Log.d(TAG, "set image bitmap,but url has changed, ignored!");
                    }
                } break;
            }
            return false;
        }
    });



    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if(!diskCacheDir.exists()) {
            diskCacheDir.mkdir();
        }
        // 判断磁盘剩余空间
        if(getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * build a new instance of ImageLoader
     * @param context
     * @return a new instance of ImageLoader
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }

    /**
     * load bitmap from memory cache or disk cache or network
     * @param url           http url
     * @param reqWidth      the width ImageView desired
     * @param reqHeight     the height ImageView desired
     * @return              bitmap, maybe null
     */
    public Bitmap loadBitmap(String url, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemoryCache(url);
        if(bitmap != null) {
            Log.d(TAG, "loadBitmap: load bitmap from memory cache, url:" + url);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(url, reqWidth, reqHeight);
            if(bitmap != null) {
                Log.d(TAG, "loadBitmap: load bitmap from disk, url:" + url);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(url, reqWidth, reqHeight);
            Log.d(TAG, "loadBitmap: load bitmap form http, url:" + url);
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(bitmap == null && !mIsDiskLruCacheCreated) {
            Log.w(TAG, "loadBitmap: encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(url);
        }
        return bitmap;
    }

    /**
     * load bitmap from memory cache or disk cache or network async, then bind imageView and bitmap.
     * NOTE THAT: should run in UI Thread
     * @param uri
     * @param imageView
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    private List<Bitmap> testBitmap = new ArrayList<>();
    /**
     * 异步加载图片
     */
    public void bindBitmap(final String uri, final ImageView imageView,
                           final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, uri);
        final Bitmap bitmap = loadBitmapFromMemoryCache(uri);
        if(bitmap != null) {
            imageView.setImageBitmap(bitmap);
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap2 = loadBitmap(uri, reqWidth, reqHeight);
                testBitmap.add(bitmap2);
                if(bitmap2 != null) {
                    LoaderResult loaderResult = new LoaderResult(imageView, uri, bitmap2);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, loaderResult).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }


    /**
     * 从内存中加载bitmap
     */
    public Bitmap loadBitmapFromMemoryCache(String url) {
        String key = hashKeyFormUrl(url);
        return mMemoryCache.get(key);
    }

    /**
     * 从URL下载图片
     */
    private Bitmap downloadBitmapFromUrl(String urlString) {
        Bitmap bitmap = null;
        HttpURLConnection urlConnection = null;
        BufferedInputStream in = null;

        try {
            URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(in);
        } catch (IOException e) {
            Log.e(TAG, "Error in downloadBitmap: " + e);
        } finally {
            if(urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtils.close(in);
        }
        return bitmap;
    }

    /**
     * 获取磁盘缓存文件夹
     */
    public File getDiskCacheDir(Context context, String uniqueName) {
        String cachePath;
        if(Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) ||
                !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 获取可用空间大小
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File path) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return path.getUsableSpace();
        }
        final StatFs statFs = new StatFs(path.getPath());
        return statFs.getBlockSizeLong() * statFs.getAvailableBlocksLong();
    }

    /**
     * 从网络下载图片
     */
    private Bitmap loadBitmapFromHttp(String url, int reqWidth, int reqHeight) throws IOException {
        if(Looper.myLooper() == Looper.getMainLooper()) {
            throw new RuntimeException("can not visit network from UI thread.");
        }
        if(mDiskLruCache == null) {
            return null;
        }

        String key = hashKeyFormUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if(editor != null) {
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            if(downloadUrlToStream(url, outputStream)) {
                editor.commit();
            } else {
                editor.abort();
            }
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url, reqWidth, reqHeight);
    }

    /**
     * 从磁盘缓存中加载bitmap
     */
    private Bitmap loadBitmapFromDiskCache(String url, int reqWidth, int reqHeight) throws IOException {
        if(Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "loadBitmapFromDiskCache: load bitmap from UI thread, it's not recommended!");
        }
        if(mDiskLruCache == null) {
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFormUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if(snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampledBitmapFromFileDescriptor(fileDescriptor, reqWidth, reqHeight);
            if(bitmap != null) {
                // 将图片加载到内存缓存中
                addBitmapToMemoryCache(key, bitmap);
            }
        }
        return bitmap;
    }

    /**
     * 从网络下载图片到流
     */
    public boolean downloadUrlToStream(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;

        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

            int b;
            while((b = in.read()) != -1) {
                // TODO: 一个字节一个字节写，效率如何？
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "downloadUrlToStream: downloadBitmap failed." + e);
        } finally {
            if(urlConnection != null) {
                urlConnection.disconnect();
            }
            MyUtils.close(out);
            MyUtils.close(in);
        }
        return false;
    }


    /**
     * 添加Bitmap到内存
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if(getBitmapFromMemoryCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * 从内存中获取Bitmap
     */
    private Bitmap getBitmapFromMemoryCache(String key) {
        return mMemoryCache.get(key);
    }

    private String hashKeyFormUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            messageDigest.update(url.getBytes());
            cacheKey = bytesToHexString(messageDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < bytes.length; ++i) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if(hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    private static class LoaderResult {
        public ImageView    imageView;
        public String       uri;
        public Bitmap       bitmap;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }
}












