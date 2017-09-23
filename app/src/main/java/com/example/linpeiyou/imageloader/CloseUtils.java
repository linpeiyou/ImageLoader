package com.example.linpeiyou.imageloader;

import java.io.Closeable;

/**
 * Created by linpeiyou on 2017/9/23.
 */

public class CloseUtils {

    public static void close(Closeable closeable) {
        try {
            if(closeable != null) {
                closeable.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
