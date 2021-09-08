/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tzutalin.dlibtest

import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.io.FileOutputStream

/**
 * Utility class for manipulating images.
 */
object ImageUtils {

    private val TAG = ImageUtils::class.java.simpleName

    /**
     * Utility method to compute the allocated size in bytes of a YUV420SP image
     * of the given dimensions.
     */
    fun getYUVByteSize(width: Int, height: Int): Int {
        // The luminance plane requires 1 byte per pixel.
        val ySize = width * height

        // The UV plane works on 2x2 blocks, so dimensions with odd size must be rounded up.
        // Each 2x2 block takes 2 bytes to encode, one each for U and V.
        val uvSize = (width + 1) / 2 * ((height + 1) / 2) * 2
        return ySize + uvSize
    }

    /**
     * Saves a Bitmap object to disk for analysis.
     *
     * @param bitmap The bitmap to save.
     */
    fun saveBitmap(bitmap: Bitmap?) {
        val root = Environment.getExternalStorageDirectory().absolutePath + File.separator + "dlib"
        Log.d(TAG, String.format("Saving %dx%d bitmap to %s.", bitmap!!.width, bitmap.height, root))
        val myDir = File(root)
        if (!myDir.mkdirs()) {
            Log.e(TAG, "Make dir failed")
        }
        val fname = "preview.png"
        val file = File(myDir, fname)
        if (file.exists()) {
            file.delete()
        }
        try {
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 99, out)
            out.flush()
            out.close()
        } catch (e: Exception) {
            Log.e(TAG, "Exception!", e)
        }
    }

    /**
     * Converts YUV420 semi-planar data to ARGB 8888 data using the supplied width
     * and height. The input and output must already be allocated and non-null.
     * For efficiency, no error checking is performed.
     *
     * @param input    The array of YUV 4:2:0 input data.
     * @param output   A pre-allocated array for the ARGB 8:8:8:8 output data.
     * @param width    The width of the input image.
     * @param height   The height of the input image.
     * @param halfSize If true, downsample to 50% in each dimension, otherwise not.
     */
    external fun convertYUV420SPToARGB8888(
        input: ByteArray?, output: IntArray?, width: Int, height: Int, halfSize: Boolean
    )

    /**
     * Converts YUV420 semi-planar data to ARGB 8888 data using the supplied width
     * and height. The input and output must already be allocated and non-null.
     * For efficiency, no error checking is performed.
     *
     * @param y
     * @param u
     * @param v
     * @param uvPixelStride
     * @param width         The width of the input image.
     * @param height        The height of the input image.
     * @param halfSize      If true, downsample to 50% in each dimension, otherwise not.
     * @param output        A pre-allocated array for the ARGB 8:8:8:8 output data.
     */
    @Keep
    external fun convertYUV420ToARGB8888(
        y: ByteArray?,
        u: ByteArray?,
        v: ByteArray?,
        output: IntArray?,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        halfSize: Boolean
    )

    /**
     * Converts YUV420 semi-planar data to RGB 565 data using the supplied width
     * and height. The input and output must already be allocated and non-null.
     * For efficiency, no error checking is performed.
     *
     * @param input  The array of YUV 4:2:0 input data.
     * @param output A pre-allocated array for the RGB 5:6:5 output data.
     * @param width  The width of the input image.
     * @param height The height of the input image.
     */
    @Keep
    external fun convertYUV420SPToRGB565(
        input: ByteArray?, output: ByteArray?, width: Int, height: Int
    )

    /**
     * Converts 32-bit ARGB8888 image data to YUV420SP data.  This is useful, for
     * instance, in creating data to feed the classes that rely on raw camera
     * preview frames.
     *
     * @param input  An array of input pixels in ARGB8888 format.
     * @param output A pre-allocated array for the YUV420SP output data.
     * @param width  The width of the input image.
     * @param height The height of the input image.
     */
    @Keep
    external fun convertARGB8888ToYUV420SP(
        input: IntArray?, output: ByteArray?, width: Int, height: Int
    )

    /**
     * Converts 16-bit RGB565 image data to YUV420SP data.  This is useful, for
     * instance, in creating data to feed the classes that rely on raw camera
     * preview frames.
     *
     * @param input  An array of input pixels in RGB565 format.
     * @param output A pre-allocated array for the YUV420SP output data.
     * @param width  The width of the input image.
     * @param height The height of the input image.
     */
    @Keep
    external fun convertRGB565ToYUV420SP(
        input: ByteArray?, output: ByteArray?, width: Int, height: Int
    )
}