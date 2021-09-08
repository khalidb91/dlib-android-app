/*
*  Copyright (C) 2015-present TzuTaLin
*/
package com.tzutalin.dlibtest

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import com.dexafree.materialList.card.Card
import com.dexafree.materialList.card.provider.BigImageCardProvider
import com.dexafree.materialList.view.MaterialListView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tzutalin.dlib.Constants
import com.tzutalin.dlib.FaceDet
import com.tzutalin.dlib.PedestrianDet
import com.tzutalin.dlib.VisionDetRet
import hugo.weaving.DebugLog
/*import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext*/
import java.io.File
import java.io.IOException
import java.util.*
import kotlin.math.roundToInt


open class MainActivity : AppCompatActivity() {

    // UI
    private var dialog: ProgressDialog? = null
    private var listView: MaterialListView? = null
    private var fabGalleryActionBt: FloatingActionButton? = null
    private var fabCameraActionBt: FloatingActionButton? = null
    private var toolbar: Toolbar? = null
    private var imgPath: String? = null
    private var faceDet: FaceDet? = null
    private var personDet: PedestrianDet? = null
    private val cards: MutableList<Card> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        setSupportActionBar(toolbar)

        listView = findViewById<View>(R.id.material_listview) as MaterialListView

        // Just use hugo to print log
        isExternalStorageWritable
        isExternalStorageReadable

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            verifyPermissions(this)
        }

        setupUI()
    }

    private val selectImageFromGalleryResult =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            try {
                // When an Image is picked
                if (null != uri) {
                    val testImgPath = getFilePath(this, uri)
                    if (testImgPath != null) {
                        runDemosAsync(testImgPath)
                        Toast.makeText(this, "Img Path:$testImgPath", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "You haven't picked Image", Toast.LENGTH_LONG).show()
                }
            } catch (e: java.lang.Exception) {
                Toast.makeText(this, "Something went wrong", Toast.LENGTH_LONG).show()
            }
        }

    @Throws(IOException::class)
    open fun getFilePath(context: Context, uri: Uri): String? {
        val filePathColumn = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = contentResolver.query(uri, filePathColumn, null, null, null)
        cursor!!.moveToFirst()
        val columnIndex = cursor.getColumnIndex(filePathColumn[0])
        val filePath = cursor.getString(columnIndex)
        cursor.close()
        return filePath
    }

    private fun selectImageFromGallery() = selectImageFromGalleryResult.launch("image/*")

    private fun setupUI() {
        listView = findViewById<View>(R.id.material_listview) as MaterialListView
        fabGalleryActionBt = findViewById<View>(R.id.fab) as FloatingActionButton
        fabCameraActionBt = findViewById<View>(R.id.fab_cam) as FloatingActionButton
        toolbar = findViewById<View>(R.id.toolbar) as Toolbar

        fabGalleryActionBt?.setOnClickListener {
            Toast.makeText(this@MainActivity, "Pick one image", Toast.LENGTH_SHORT).show()
            selectImageFromGallery()
        }

        fabCameraActionBt?.setOnClickListener {
            startActivity(Intent(this@MainActivity, CameraActivity::class.java))
        }

        toolbar?.title = getString(R.string.app_name)

        Toast.makeText(this@MainActivity, getString(R.string.description_info), Toast.LENGTH_LONG)
            .show()
    }

    /* Checks if external storage is available for read and write */
    @get:DebugLog
    private val isExternalStorageWritable: Boolean
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state
        }

    /* Checks if external storage is available to at least read */
    @get:DebugLog
    private val isExternalStorageReadable: Boolean
        get() {
            val state = Environment.getExternalStorageState()
            return Environment.MEDIA_MOUNTED == state || Environment.MEDIA_MOUNTED_READ_ONLY == state
        }

    @DebugLog
    protected fun demoStaticImage() {
        if (imgPath != null) {
            Log.d(TAG, "demoStaticImage() launch a task to det")
            runDemosAsync(imgPath!!)
        } else {
            Log.d(TAG, "demoStaticImage() mTestImgPath is null, go to gallery")
            Toast.makeText(this@MainActivity, "Pick an image to run algorithms", Toast.LENGTH_SHORT)
                .show()
            this.selectImageFromGallery()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSION) {
            Toast.makeText(this@MainActivity, "Demo using static images", Toast.LENGTH_SHORT).show()
            demoStaticImage()
        }
    }

    // ==========================================================
    // Tasks inner class
    // ==========================================================

    private fun runDemosAsync(imgPath: String) {
        demoPersonDet(imgPath)
        demoFaceDet(imgPath)
    }


    private fun demoPersonDet(imgPath: String) {
        object : AsyncTask<Void?, Void?, List<VisionDetRet>?>() {
            override fun onPreExecute() {
                super.onPreExecute()
            }

            override fun onPostExecute(personList: List<VisionDetRet>?) {
                super.onPostExecute(personList)
                if (personList!=null && personList.isNotEmpty()) {
                    val card = Card.Builder(this@MainActivity)
                        .withProvider(BigImageCardProvider::class.java)
                        .setDrawable(drawRect(imgPath, personList, Color.BLUE))
                        .setTitle("Person det")
                        .endConfig()
                        .build()
                    cards.add(card)
                } else {
                    Toast.makeText(applicationContext, "No person", Toast.LENGTH_LONG).show()
                }
                updateCardListView()
            }

            override fun doInBackground(vararg voids: Void?): List<VisionDetRet>? {
                // Init
                if (personDet == null) {
                    personDet = PedestrianDet()
                }
                Log.d(TAG, "Image path: $imgPath")
                return personDet!!.detect(imgPath)
            }

        }.execute()
    }

    private fun demoFaceDet(imgPath: String) {
        object : AsyncTask<Void?, Void?, List<VisionDetRet>?>() {
            override fun onPreExecute() {
                super.onPreExecute()
                showDialog("Detecting faces")
            }

            override fun onPostExecute(faceList: List<VisionDetRet>?) {
                super.onPostExecute(faceList)
                if (faceList!=null && faceList.isNotEmpty()) {
                    val card = Card.Builder(this@MainActivity)
                        .withProvider(BigImageCardProvider::class.java)
                        .setDrawable(drawRect(imgPath, faceList, Color.GREEN))
                        .setTitle("Face det")
                        .endConfig()
                        .build()
                    cards.add(card)
                } else {
                    Toast.makeText(applicationContext, "No face", Toast.LENGTH_LONG).show()
                }
                updateCardListView()
                dismissDialog()
            }

            override fun doInBackground(vararg params: Void?): List<VisionDetRet>? {
                // Init
                if (faceDet == null) {
                    faceDet = FaceDet(Constants.getFaceShapeModelPath())
                }
                val targetPath = Constants.getFaceShapeModelPath()
                if (!File(targetPath).exists()) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Copy landmark model to $targetPath", Toast.LENGTH_SHORT
                        ).show()
                    }
                    FileUtils.copyFileFromRawToOthers(
                        applicationContext,
                        R.raw.shape_predictor_68_face_landmarks,
                        targetPath
                    )
                }
                return faceDet?.detect(imgPath)
            }

        }.execute()
    }

    /*
    private fun demoPersonDet(imgPath: String) {
        lifecycleScope.executeAsyncTask(
            onPreExecute = {
                showDialog("Detecting persons")
            },
            doInBackground = {
                if (personDet == null) {
                    personDet = PedestrianDet()
                }
                Log.d(TAG, "Image path: $imgPath")
                return@executeAsyncTask personDet!!.detect(imgPath)
            },
            onPostExecute = { personList ->
                if (personList!=null && personList.isNotEmpty()) {
                    val card = Card.Builder(this@MainActivity)
                        .withProvider(BigImageCardProvider::class.java)
                        .setDrawable(drawRect(imgPath, personList, Color.BLUE))
                        .setTitle("Person det")
                        .endConfig()
                        .build()
                    this@MainActivity.card.add(card)
                } else {
                    showToast("No person")
                }
                updateCardListView()
            }
        )
    }

    private fun demoFaceDet(imgPath: String) {
        lifecycleScope.executeAsyncTask(
            onPreExecute = {
                showDialog("Detecting faces")
            },
            doInBackground = {
                if (faceDet == null) {
                    faceDet = FaceDet(Constants.getFaceShapeModelPath())
                }
                val targetPath = Constants.getFaceShapeModelPath()
                if (!File(targetPath).exists()) {
                    showToast("Copy landmark model to $targetPath")
                    FileUtils.copyFileFromRawToOthers(
                        applicationContext,
                        R.raw.shape_predictor_68_face_landmarks,
                        targetPath
                    )
                }
                faceDet?.detect(imgPath)
            },
            onPostExecute = { faceList ->
                if (faceList!!.isNotEmpty()) {
                    val card = Card.Builder(this@MainActivity)
                        .withProvider(BigImageCardProvider::class.java)
                        .setDrawable(drawRect(imgPath, faceList, Color.GREEN))
                        .setTitle("Face det")
                        .endConfig()
                        .build()
                    this@MainActivity.card.add(card)
                } else {
                    showToast("No face")
                }
                updateCardListView()
                dismissDialog()
            }
        )
    }*/
/*
    private fun <R> CoroutineScope.executeAsyncTask(
        onPreExecute: () -> Unit,
        doInBackground: () -> R,
        onPostExecute: (R) -> Unit
    ) = launch {
        onPreExecute()
        val result = withContext(Dispatchers.IO) {
            doInBackground()
        }
        onPostExecute(result)
    }
*/
    private fun updateCardListView() {
        listView?.clearAll()
        for (each in cards) {
            listView?.add(each)
        }
    }

    private fun showDialog(title: String) {
        dismissDialog()
        dialog = ProgressDialog.show(this@MainActivity, title, "process..", true)
    }

    private fun dismissDialog() {
        dialog?.let{
            dialog?.dismiss()
            dialog = null
        }
    }

    @DebugLog
    private fun drawRect(path: String, results: List<VisionDetRet>, color: Int): BitmapDrawable {
        val options = BitmapFactory.Options()
        options.inSampleSize = 1
        var bitmap = BitmapFactory.decodeFile(path, options)
        var bitmapConfig = bitmap.config
        // set default bitmap config if none
        if (bitmapConfig == null) {
            bitmapConfig = Bitmap.Config.ARGB_8888
        }
        // resource bitmaps are imutable,
        // so we need to convert it to mutable one
        bitmap = bitmap.copy(bitmapConfig, true)

        val width = bitmap.width
        val height = bitmap.height

        // By ratio scale
        val aspectRatio = bitmap.width / bitmap.height.toFloat()
        var resizeRatio = 1.0f
        val newHeight = (MAX_SIZE / aspectRatio).roundToInt()

        if (bitmap.width > MAX_SIZE && bitmap.height > MAX_SIZE) {
            Log.d(TAG, "Resize Bitmap")
            bitmap = bitmap.getResizedBitmap(MAX_SIZE, newHeight)
            resizeRatio = bitmap.width.toFloat() / width.toFloat()
            Log.d(TAG, "resizeRatio $resizeRatio")
        }

        // Create canvas to draw
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = color
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE

        // Loop result list
        for (ret in results) {
            val bounds = Rect()
            bounds.left = (ret.left * resizeRatio).toInt()
            bounds.top = (ret.top * resizeRatio).toInt()
            bounds.right = (ret.right * resizeRatio).toInt()
            bounds.bottom = (ret.bottom * resizeRatio).toInt()
            canvas.drawRect(bounds, paint)
            // Get landmark
            val landmarks = ret.faceLandmarks
            for (point in landmarks) {
                val pointX = (point.x * resizeRatio).toInt()
                val pointY = (point.y * resizeRatio).toInt()
                canvas.drawCircle(pointX.toFloat(), pointY.toFloat(), 2f, paint)
            }
        }

        return BitmapDrawable(resources, bitmap)
    }

    @DebugLog
    private fun Bitmap.getResizedBitmap(newWidth: Int, newHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
    }

    private fun showToast(text: String) {
        this.runOnUiThread {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {

        private const val REQUEST_CODE_PERMISSION = 2
        private const val MAX_SIZE = 512
        private const val TAG = "MainActivity"

        // Storage Permissions
        private val PERMISSIONS_REQ = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )

        /**
         * Checks if the app has permission to write to device storage or open camera
         * If the app does not has permission then the user will be prompted to grant permissions
         *
         * @param activity
         */
        @DebugLog
        private fun verifyPermissions(activity: Activity): Boolean {
            // Check if we have write permission
            val writePermission = ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            val readPermission = ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

            val cameraPermission = ActivityCompat.checkSelfPermission(
                activity,
                Manifest.permission.CAMERA
            )

            return if (
                writePermission != PackageManager.PERMISSION_GRANTED ||
                readPermission != PackageManager.PERMISSION_GRANTED ||
                cameraPermission != PackageManager.PERMISSION_GRANTED
            ) {
                // We don't have permission so prompt the user
                ActivityCompat.requestPermissions(activity,
                    PERMISSIONS_REQ,
                    REQUEST_CODE_PERMISSION
                )
                false
            } else {
                true
            }
        }
    }
}