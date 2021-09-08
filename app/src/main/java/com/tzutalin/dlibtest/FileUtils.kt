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

import android.content.Context
import androidx.annotation.RawRes
import java.io.FileOutputStream
import java.io.IOException

/**
 * Created by Tzutalin on 2016/3/30.
 */
object FileUtils {

    fun copyFileFromRawToOthers(context: Context, @RawRes id: Int, targetPath: String) {

        val inputStream = context.resources.openRawResource(id)
        var outputStream: FileOutputStream? = null

        try {
            outputStream = FileOutputStream(targetPath)
            val buff = ByteArray(1024)
            var read = 0
            while (inputStream.read(buff).also { read = it } > 0) {
                outputStream.write(buff, 0, read)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close()
                }
                outputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

}