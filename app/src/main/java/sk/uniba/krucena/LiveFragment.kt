package sk.uniba.krucena

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.VideoResolution
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.utils.common.NumberUtils
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.Imgproc.FONT_HERSHEY_COMPLEX
import org.opencv.imgproc.Imgproc.LINE_8
import java.io.File
import java.io.FileOutputStream
import kotlin.reflect.KMutableProperty0

class LiveFragment : Fragment() {

    private val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
    private val liveStreamVM: LiveStreamVM by viewModels()
    private lateinit var cameraIndex: ComponentIndexType
    private lateinit var rgProtocol: RadioGroup
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var tvLiveInfo: TextView
    private lateinit var tvLiveError: TextView
    private lateinit var svCameraStream: SurfaceView
    private var isLocalLiveShow: Boolean = true

    private var missionHasStarted : Boolean = false
    private var missionEmergenceActivated : Boolean = false

    private var cameraStreamSurface: Surface? = null
    private var cameraStreamWidth = -1
    private var cameraStreamHeight = -1
    private var cameraStreamScaleType: ICameraStreamManager.ScaleType =
        ICameraStreamManager.ScaleType.CENTER_INSIDE

    private var rootView: View? = null

    private var clicks : HashMap<Rect, Int> = HashMap()

    //click actions
    private val ACTION_CONFIG : Int = -1
    private val ACTION_RED : Int = -2
    private val ACTION_GREEN : Int = -3
    private val ACTION_BLUE : Int = -4
    private val ACTION_YELLOW : Int = -5
    private val ACTION_BKMAX : Int = -6
    private val ACTION_BKCHROMA : Int = -7
    private val ACTION_OK : Int = -8
    private val ACTION_DONE : Int = -9

    private val ACTION_MASTER : Int = -10
    private val ACTION_DRONEID : Int = -11
    private val ACTION_NUMDRONES : Int = -12
    private val ACTION_CONTOURS : Int = -13
    private val ACTION_CPPDEBUG : Int = -14
    private val ACTION_POSDEBUG : Int = -15

    private val ACTION_NONE : Int = -100000

    private var sliderVar : KMutableProperty0<Int> = ::sliderNothing
    private var sliderMin : Double = 0.0
    private var sliderMax : Double = 1.0
    private var sliderPos : Double = 0.0
    private var sliderNothing : Int = 0

    private fun getClickedAction(x: Int, y : Int) : Int
    {
        synchronized(clicks)
        {
            for ((rect, action) in clicks) {
                if ((x > rect.x - 30) &&
                    (x < rect.x + rect.width + 30) &&
                    (y > rect.y - 30) &&
                    (y < rect.y + rect.height + 30)
                )
                    return action
            }
        }
        return ACTION_NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        rootView = inflater.inflate(R.layout.frag_live, container, false)
        Log.i("OpenCV", "set rootView to ${rootView}")

        val touchOverlay = rootView!!.findViewById<View>(R.id.overlay)

        touchOverlay.setOnTouchListener { _, event ->
            val clicked = (event.action == MotionEvent.ACTION_DOWN)
            val dragged = (event.action == MotionEvent.ACTION_MOVE)
            if (clicked || dragged) {
                // Trigger your start signal here
                val act : MainActivity = (activity as MainActivity)
                if (!missionHasStarted) {
                    val action = getClickedAction(event.x.toInt(), event.y.toInt())
                    if (act.guiState == GUIState.READY_TO_RUN) {
                        if (dragged) return@setOnTouchListener true
                        if (action > 0)  // a dance was selected
                        {
                            missionHasStarted = true
                            act.guiState = GUIState.RUNNING
                            Log.d("OpenCV", "Screen touched: starting mission!")
                            startMission(action - 1)
                        } else if (action == ACTION_CONFIG) {
                            act.guiState = GUIState.IN_CONFIG
                            act.toPointGimbal = true
                        }
                    } else if (act.guiState == GUIState.IN_CONFIG) {
                        if (dragged) return@setOnTouchListener true
                        when (action) {
                            ACTION_RED -> act.guiState = GUIState.RED
                            ACTION_GREEN -> act.guiState = GUIState.GREEN
                            ACTION_BLUE -> act.guiState = GUIState.BLUE
                            ACTION_YELLOW -> act.guiState = GUIState.YELLOW
                            ACTION_BKMAX -> act.guiState = GUIState.BKMAX
                            ACTION_BKCHROMA -> act.guiState = GUIState.BKCHROMA
                            ACTION_DONE -> {
                                act.config.updateConfig()
                                act.toPointGimbal = false
                                act.guiState = GUIState.READY_TO_RUN
                            }
                        }
                        when (action) {
                            ACTION_RED, ACTION_GREEN, ACTION_BLUE -> NativeBridge.setMode(1, act.config.show_contours, act.config.cpp_debug, act.config.position_debug)
                            ACTION_YELLOW -> NativeBridge.setMode(3, act.config.show_contours, act.config.cpp_debug, act.config.position_debug)
                            ACTION_BKMAX, ACTION_BKCHROMA -> NativeBridge.setMode(2, act.config.show_contours, act.config.cpp_debug, act.config.position_debug)
                        }

                        when (action) {
                            ACTION_MASTER -> act.config.isServer = !act.config.isServer
                            ACTION_DRONEID -> act.config.droneId = (act.config.droneId % act.config.maxDroneID) + 1
                            ACTION_NUMDRONES -> act.config.expectedNumberOfDrones = (act.config.expectedNumberOfDrones % act.config.maxDroneID) + 1
                            ACTION_CONTOURS -> act.config.show_contours = 1 - act.config.show_contours
                            ACTION_CPPDEBUG -> act.config.cpp_debug = 1 - act.config.cpp_debug
                            ACTION_POSDEBUG -> act.config.position_debug = 1 - act.config.position_debug
                        }
                    } else if (act.guiState in setOf(GUIState.RED, GUIState.GREEN, GUIState.BLUE, GUIState.YELLOW, GUIState.BKMAX, GUIState.BKCHROMA)) {
                        if ((action == ACTION_OK) && clicked) {
                            NativeBridge.setMode(0, act.config.show_contours, act.config.cpp_debug, act.config.position_debug)
                            act.guiState = GUIState.IN_CONFIG
                        }
                        else if ((event.y > 40) && (event.y < 220))
                        {
                            var newVal = ((event.x - sliderMin) / (sliderMax - sliderMin) * 255.0).toInt()
                            if (newVal < 0) newVal = 0
                            else if (newVal > 255) newVal = 255
                            sliderVar.set(newVal)
                            sliderPos = sliderMin + (sliderMax - sliderMin) * sliderVar.get() / 255.0
                            NativeBridge.setupColors(act.config.black_maxRGB_t, act.config.black_chroma_t, act.config.red_t, act.config.green_t, act.config.blue_t, act.config.yellow_t)
                        }
                    }
                }
                else if (clicked && !missionEmergenceActivated) {
                    missionEmergenceActivated = true
                    emergencyStop()
                }
            }
            true  // Return true to consume the event
        }
        return rootView!!
    }

    fun startMission(danceToStart : Int)
    {
        (requireActivity() as MainActivity).startMission(danceToStart)
    }

    fun emergencyStop()
    {
        (requireActivity() as MainActivity).emergencyStop()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        svCameraStream = view.findViewById(R.id.sv_camera_stream)

        initRGCamera()
        initCameraStream()
        initLiveData()
        startGrabbing()
    }

    fun saveBitmapToFile(bitmap: Bitmap, filename: String, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG) {
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), filename)  // Or context?.cacheDir, context?.filesDir, or Environment.getExternalStoragePublicDirectory() if you have permission

        FileOutputStream(file).use { out ->
            bitmap.compress(format, 100, out)  // 100 = best quality
        }

        Log.i("VideoChannelFragment", "Saved image to: ${file.absolutePath}")
    }

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    fun startGrabbing()
    {
        Handler().postDelayed({
           if (!captureFrame(svCameraStream, ::receiveBitmap))
               Handler().postDelayed({startGrabbing()}, 200)
        }, 10)
    }

    fun pointGimbalDown() {
        Log.i("OpenCV", "pointGimbalDown()")

        val rotation = GimbalAngleRotation()
        rotation.setPitch(-90.0)
        rotation.setMode(GimbalAngleRotationMode.ABSOLUTE_ANGLE)
        rotation.setYaw(0.0) // Rotation.NO_ROTATION)
        rotation.setRoll(0.0) //Rotation.NO_ROTATION)
        rotation.setDuration(2.0) // duration in seconds (optional)

        val key = KeyTools.createKey(GimbalKey.KeyRotateByAngle, 0) // 0 = first gimbal

        KeyManager.getInstance().performAction(
            key,
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(po: EmptyMsg) {
                    Log.i("OpenCV", "Gimbal rotated to -90° pitch successfully.")
                }

                override fun onFailure(p0: IDJIError) {
                    Log.e("OpenCV", "Failed to rotate gimbal:")
                }
            }
        )
    }

    fun drawButton(img : Mat, x : Int, y : Int, label : String) : Rect
    {
        val width = 14 + label.length * 20
        val height = 52
        Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()), Point((x + width).toDouble(), (y + height).toDouble() ), Scalar(200.0, 220.0, 180.0), -1 )
        Imgproc.rectangle(img, Point(x.toDouble(), y.toDouble()), Point((x + width).toDouble(), (y + height).toDouble() ), Scalar(200.0, 0.0, 0.0), 2 )
        Imgproc.putText(img, label, Point(x + 7.0, y + 37.0), FONT_HERSHEY_COMPLEX, 1.0, Scalar(0.0, 40.0, 90.0), 2, LINE_8)
        return Rect(x, y, width, height )
    }

    fun drawSlider(img : Mat, width : Int, sliderVariable : KMutableProperty0<Int>)
    {
        sliderMin = 100.0
        sliderMax = width - 100.0
        sliderPos = sliderMin + (sliderMax - sliderMin) * sliderVar.get() / 255.0
        sliderVar = sliderVariable
        Imgproc.rectangle(img, Point(sliderMin, 100.0), Point(sliderMax, 130.0), Scalar(255.0, 255.0, 255.0), -1)
        Imgproc.rectangle(img, Point(sliderMin, 100.0), Point(sliderMax, 130.0), Scalar(255.0, 0.0, 0.0), 2)
        Imgproc.rectangle(img, Point(sliderPos - 10, 60.0), Point(sliderPos + 10, 170.0), Scalar(255.0, 0.0, 0.0), -1)
        Imgproc.rectangle(img, Point(sliderPos - 10, 60.0), Point(sliderPos + 10, 170.0), Scalar(255.0, 255.0, 255.0), 2)
        drawButton(img,(sliderPos - 50).toInt(), 220, sliderVar.get().toString())
    }

    fun rotateBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        return if (bitmap.width < bitmap.height) {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    fun receiveBitmap(btmp: Bitmap?) {
        btmp?.let {
            val activity = (requireActivity() as MainActivity)

            if (activity.toPointGimbal) pointGimbalDown()
            val btmpOK = it
            //var btmpOK = rotateBitmapIfNeeded(btmp)

            Log.i("OpenCV", "receiveBitmap() ${btmpOK.width} x ${btmpOK.height}, ${btmpOK.byteCount}")
            val frameAsMat = bitmapToMat(btmpOK)
            Log.i("OpenCV", "Mat size: ${frameAsMat.rows()} x ${frameAsMat.cols()}")

            var cameraPosition = FloatArray(4)



            val keyManager = KeyManager.getInstance()
            val attitudeK = KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude)
            val compassHeadingK = KeyTools.createKey(FlightControllerKey.KeyCompassHeading)
            val att = keyManager?.getValue(attitudeK)
            val cmps = keyManager?.getValue(compassHeadingK)
            if (att is Attitude)
            {
                val attitude = activity.config.northCorrection - att.yaw
                Log.i("heading", "att=${"%.2f".format(attitude)}, cmps=${"%.2f".format(cmps)}")
            }

            NativeBridge.localization(frameAsMat.nativeObjAddr,
                cameraPosition, activity.config.droneId)
                activity.cameraPosition = cameraPosition

            val yaw_deg = cameraPosition[3] / Math.PI * 180.0;

            Log.i("heading", "yaw=${"%.2f".format(yaw_deg)}")

            var Y1 : Int = 100
            var Y2 : Int = 300
            var Y3 : Int = 500
            var Y4 : Int = 700

            if (btmpOK.height < 800)
            {
                Y1 = 70
                Y2 = 180
                Y3 = 300
                Y4 = 500
            }

            var pose = ""
            if (Math.abs(cameraPosition[0] - 999.0) < 1)     // 999f means not found
                pose = "[unavailable]";
            else
                pose = "[x=%6.2f, y=%6.2f, z=%6.2f, a=%6.1f]".format(cameraPosition[0], cameraPosition[1], cameraPosition[2], yaw_deg)
            if (activity.config.isServer) pose += ", d: ${1 + activity.comm.dronesConnected.size}"
            Imgproc.putText(frameAsMat, pose, Point(5.0, 40.0), FONT_HERSHEY_COMPLEX, 1.0, Scalar(255.0, 255.0, 255.0), 2, LINE_8);
            Imgproc.putText(frameAsMat, "#${activity.agendaIndex} ${if (activity.emergency) "emergency" else ""}", Point(5.0, 70.0), FONT_HERSHEY_COMPLEX, 1.0, Scalar(255.0, 255.0, 255.0), 2, LINE_8);

            when (activity.guiState) {
                GUIState.READY_TO_RUN -> {
                    val n = activity.dances.size
                    if (n == 0)
                    {
                        Log.i("dance", "no dances. are configs present?")
                        return
                    }
                    val step = btmpOK.width / n
                    val offset = step / 2
                    synchronized(clicks) {
                        clicks.clear()
                        for (i in 0..n - 1)
                            clicks.put(drawButton(frameAsMat,i * step + offset,Y1,"Dance ${i + 1}"), i + 1)
                        clicks.put(drawButton(frameAsMat, btmpOK.width / 2 - 80, Y2, "Config"), ACTION_CONFIG)
                    }
                }
                GUIState.IN_CONFIG -> {
                    synchronized(clicks) {
                        clicks.clear()
                        clicks.put(drawButton(frameAsMat, 110, Y2, "Red  "), ACTION_RED)
                        clicks.put(drawButton(frameAsMat, 270, Y2, "Green"), ACTION_GREEN)
                        clicks.put(drawButton(frameAsMat, 440, Y2, "Blue "), ACTION_BLUE)
                        clicks.put(drawButton(frameAsMat, 600, Y2, "Yellow"), ACTION_YELLOW)
                        clicks.put(drawButton(frameAsMat, 775, Y2, "BkMax "), ACTION_BKMAX)
                        clicks.put(drawButton(frameAsMat, 945, Y2, "BkChroma "), ACTION_BKCHROMA)
                        clicks.put(drawButton(frameAsMat, btmpOK.width / 2 - 80, Y3, "   DONE   "), ACTION_DONE)

                        clicks.put(drawButton(frameAsMat, 110, Y4, if (activity.config.isServer) "Master" else "Slave"), ACTION_MASTER)
                        clicks.put(drawButton(frameAsMat, 280, Y4, "ID: ${activity.config.droneId.toString()}"), ACTION_DRONEID)
                        clicks.put(drawButton(frameAsMat, 450, Y4, "Drones: ${activity.config.expectedNumberOfDrones.toString()}"), ACTION_NUMDRONES)
                        clicks.put(drawButton(frameAsMat, 660, Y4, if (activity.config.show_contours == 1) "draw" else "noDraw"), ACTION_CONTOURS)
                        clicks.put(drawButton(frameAsMat, 825, Y4, if (activity.config.cpp_debug == 1) "cppDBG " else "noCppDbg"), ACTION_CPPDEBUG)
                        clicks.put(drawButton(frameAsMat, 1020, Y4, if (activity.config.position_debug == 1) "posDBG " else "noPosDbg"), ACTION_POSDEBUG)
                    }
                }
                GUIState.RED, GUIState.GREEN, GUIState.BLUE, GUIState.BKMAX, GUIState.BKCHROMA, GUIState.YELLOW -> {
                    synchronized(clicks) {
                        clicks.clear()
                        clicks.put(drawButton(frameAsMat, btmpOK.width / 2 - 80, Y2, "    OK    "), ACTION_OK)
                    }
                    when (activity.guiState) {
                        GUIState.RED -> drawSlider(frameAsMat, btmpOK.width, activity.config::red_t)
                        GUIState.GREEN -> drawSlider(frameAsMat, btmpOK.width, activity.config::green_t)
                        GUIState.BLUE -> drawSlider(frameAsMat, btmpOK.width, activity.config::blue_t)
                        GUIState.YELLOW -> drawSlider(frameAsMat, btmpOK.width, activity.config::yellow_t)
                        GUIState.BKMAX -> drawSlider(frameAsMat, btmpOK.width, activity.config::black_maxRGB_t)
                        GUIState.BKCHROMA -> drawSlider(frameAsMat, btmpOK.width, activity.config::black_chroma_t)
                        else -> {}
                    }
                }
                GUIState.RUNNING -> { }
            }

            val overlay = rootView?.findViewById<OverlayView>(R.id.overlay)
            overlay?.updateBitmap(frameAsMat)
        }
        startGrabbing()
    }

    fun captureFrame(surfaceView: SurfaceView, onFrameReady: (Bitmap?) -> Unit) : Boolean {
        //Log.i("VideoChannelFragment", "captureFrame()" )
        if (surfaceView.width <= 0) return false
        val rect = surfaceView.holder.surfaceFrame
        Log.i("OpenCV", "Surface frame = ${rect.width()}x${rect.height()}")
        Log.d("OpenCV", "surface: ${surfaceView.width} x ${surfaceView.height}" )

        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        //Log.d("OpenCV", "Rotation: ${requireActivity().windowManager.defaultDisplay.rotation}")
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    onFrameReady(bitmap)
                } else {
                    onFrameReady(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            Log.d("VideoChannelFragment", "IllegalArgumentException  in PixelCopy")
            return false
        }
        return true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLive()
    }

    @SuppressLint("SetTextI18n")
    private fun initLiveData() {
        liveStreamVM.liveStreamStatus.observe(viewLifecycleOwner) { status ->
            var liveStreamStatus = status
            if (liveStreamStatus == null) {
                liveStreamStatus = LiveStreamStatus(0, 0, 0, 0, 0, false, VideoResolution(0, 0))
            }
            val liveWidth = liveStreamStatus.resolution?.width ?: 0
            val liveHeight = liveStreamStatus.resolution?.height ?: 0
            val sourceWidth = liveStreamVM.getAircraftStreamFrameInfo(cameraIndex)?.width ?: 0
            val sourceHeight = liveStreamVM.getAircraftStreamFrameInfo(cameraIndex)?.height ?: 0
            val sourceFps = liveStreamVM.getAircraftStreamFrameInfo(cameraIndex)?.frameRate ?: 0
            val liveGCD = NumberUtils.gcd(liveWidth, liveHeight)
            val sourceGCD = NumberUtils.gcd(sourceWidth, sourceHeight)

            val statusStr = StringBuilder().append(liveStreamStatus)
                .append("source width = $sourceWidth\n")
                .append("source height = $sourceHeight\n")
                .append("source fps = $sourceFps\n")

            if (liveGCD != 0) {
                statusStr.append("live ratio = ${liveWidth / liveGCD}/${liveHeight / liveGCD}\n")
            } else {
                statusStr.append("live ratio = NA\n")
            }

            if (sourceGCD != 0) {
                statusStr.append("source ratio = ${sourceWidth / sourceGCD}/${sourceHeight / sourceGCD}\n")
            } else {
                statusStr.append("source ratio = NA\n")
            }
			if (::tvLiveInfo.isInitialized) {
				tvLiveInfo.text = statusStr.toString()
			}
				if (::rgProtocol.isInitialized) {
				rgProtocol.isEnabled = !liveStreamStatus.isStreaming
				for (i in 0 until rgProtocol.childCount) {
					rgProtocol.getChildAt(i).isEnabled = rgProtocol.isEnabled
				}
				btnStart.isEnabled = !liveStreamVM.isStreaming()
				btnStop.isEnabled = liveStreamVM.isStreaming()
			}
        }

        liveStreamVM.liveStreamError.observe(viewLifecycleOwner) { error ->
            if (error == null) {
                tvLiveError.text = ""
                tvLiveError.visibility = View.GONE
            } else {
                tvLiveError.text = "error : $error"
                tvLiveError.visibility = View.VISIBLE
            }
        }
    }

    private fun initRGCamera() {
        cameraIndex = ComponentIndexType.LEFT_OR_MAIN
        cameraStreamSurface = svCameraStream.holder.surface
        if (cameraStreamSurface != null && svCameraStream.width != 0) {
            putCameraStreamSurface()
        }
        liveStreamVM.setCameraIndex(cameraIndex)
    }

    private fun putCameraStreamSurface() {
        if (!isLocalLiveShow) {
            return
        }
        if (cameraIndex == ComponentIndexType.UNKNOWN) {
            return
        }
        cameraStreamSurface?.let {
            cameraStreamManager.putCameraStreamSurface(
                cameraIndex,
                it,
                cameraStreamWidth,
                cameraStreamHeight,
                cameraStreamScaleType
            )
        }
    }

    private fun initCameraStream() {
        svCameraStream.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                cameraStreamWidth = width
                cameraStreamHeight = height
                cameraStreamSurface = holder.surface
                putCameraStreamSurface()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraStreamManager.removeCameraStreamSurface(holder.surface)
            }
        })
    }

    private fun stopLive() {
        liveStreamVM.stopStream(null)
    }

}
