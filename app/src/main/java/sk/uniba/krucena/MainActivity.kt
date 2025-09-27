package sk.uniba.krucena

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam //  .VirtualStickFlightControlData
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode

import dji.v5.manager.aircraft.virtualstick.VirtualStickManager

import androidx.fragment.app.commit
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.LEDsSettings

import org.opencv.android.OpenCVLoader
import kotlin.system.exitProcess

enum class GUIState {
    READY_TO_RUN,
    IN_CONFIG,
    BLUE, RED, GREEN, YELLOW, BKMAX, BKCHROMA,
    RUNNING }

class MainActivity : AppCompatActivity() {

    val flyingAllowed : Boolean = true
    var emergency : Boolean = false

    lateinit var config : Config
    var dances : MutableList<Dance> = mutableListOf()

    var guiState : GUIState = GUIState.READY_TO_RUN

    val comm = Communication(this)

    var selectedDance : Int = 1

    var timeStarted : Long = 0
    var performanceCompleted : Boolean = false
    var currentDance : Dance? = null
    var agendaIndex = 0
    var hasTakenOff = false
    var toPointGimbal = false
    var hasLanded = false
    var numberOfRemainingRepeatsOfThisCommand : Int = 0
    var commandRepeatDelay : Long = 0

    var saveAgendaIndex = -1
    var saveCurrentDance : Dance? = null
    var saveTimeStarted : Long = 0

    var cameraPosition = FloatArray(4)

    var lastArgs : PosArguments? = null    // used to determine if we call the same POS or a next one
    var currentAbsolutePosArgs = PosArguments(0.0f, 0.0f, 0.0f, 0.0f)

    var relativeON : Boolean = false
    var relativePos = PosArguments(0.0f, 0.0f, 0.0f, 0.0f)
    var referencePoint = PosArguments(0.0f, 0.0f, 0.0f, 0.0f)

    var horizontalSpeedLimit : Double = 0.3
    var verticalSpeedLimit : Double = 1.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        requestStoragePermission(this)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, LiveFragment())
            }
        }

        config = Config(this)

        // Initialize Virtual Stick Mode
        Handler(Looper.getMainLooper()).postDelayed({
            config.readConfig()
            NativeBridge.setMode(0, config.show_contours, config.cpp_debug, config.position_debug)
            NativeBridge.setupColors(config.black_maxRGB_t, config.black_chroma_t, config.red_t,
                                     config.green_t, config.blue_t, config.yellow_t)
            comm.setupCommunication()
            dances = Dance.load(this, config.droneId)
            proceed()
        }, 5000)

        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "Failed to initialize")
        } else {
            Log.i("OpenCV", "OpenCV initialized!")
        }
    }

    /** called by the client communication thread when server says START, and by master on button click */
    fun startChoreography(danceNumber : Int)
    {
        selectedDance = danceNumber
        performanceCompleted = false
        agendaIndex = 0
        saveAgendaIndex = -1
        timeStarted = System.currentTimeMillis()
        if (config.isServer)
            comm.startPlayingMusic(danceNumber) {
                Log.i("Dance", "time master start t=${timeStarted}")
            }
        else {
            Log.i("Dance", "time slave start t=${timeStarted}")

        }
        //guiState = GUIState.RUNNING;
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.i("Config", "All permissions granted!")
            } else {
                Log.e("Config", "Not all permissions granted.")
            }
        }
    }

    fun requestStoragePermission(activity: MainActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsNeeded = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (permissionsNeeded.isNotEmpty()) {
                ActivityCompat.requestPermissions(activity,
                    permissionsNeeded.toTypedArray(),
                    100)  // Request code (any number)
            }
        }
    }

    fun emergencyStop()
    {
        timeStarted = System.currentTimeMillis() - (currentDance?.landingTime ?: 86400000)
        if (config.isServer)
        {
            emergency = true
            comm.sendEmergencySignal()
        }
        else emergency = true
    }

    fun startMission(danceToStart : Int)
    {
        if (config.isServer)
        {
            Log.i("Dance", "dance to start: ${1 + danceToStart} (of 1..${dances.size})")
            comm.sendStartSignalWhenReady(danceToStart)
        }
        else
            Toast.makeText(this, "Please touch screen on the master!", Toast.LENGTH_SHORT).show()
    }

    private fun proceed()
    {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (timeStarted == 0L) handler.postDelayed(this, 10)
                else {
                    handler.removeCallbacks(this)
                    Handler(Looper.getMainLooper()).post( { startDancing() } )
                }
            }
        })
    }

    /** for commands that are not repeated, this function is called after the current command
     *    has been performed and should return the time to wait until the next command on the agenda
     *    (possibly 0, if we are "behind" the agenda)
     *  for commands that are repeated, we detect that with numberOfRemainingRepeatesOfThisCommand > 0
     *    and then either proceed to the next instruction if it is already time before next repetition would be done,
     *    or we just return the wait time commandRepeatDelay */
    private fun howMuchToWaitForNextCommand() : Long {
        var currentSimulationTime = System.currentTimeMillis() - timeStarted
        var advanceToNextInstruction = false
        var mayBeEndOfProcedure = false
        var delayToReturn = 500L  // when no more instructions, we just continue to idle slowly

        if (numberOfRemainingRepeatsOfThisCommand > 0)
        {
            if (agendaIndex < (currentDance?.instructions?.size ?: 0) - 1) {
                val nextInstruction = currentDance?.instructions?.getOrNull(agendaIndex + 1);
                if (nextInstruction != null) {
                    if (currentSimulationTime + commandRepeatDelay >= nextInstruction.time) {
                        // even though this is repeated command, now is time to advance to the next instruction
                        advanceToNextInstruction = true
                    } else return commandRepeatDelay
                } else
                {
                    // we may be at the end of procedure or program
                    delayToReturn = commandRepeatDelay
                    mayBeEndOfProcedure = true
                    //Log.d("Dance","maybe ret because nextInstr null")
                }
            } else // we are at the last instruction of program/procedure
            {   // we may be need to return, if time is over, check below
                delayToReturn = commandRepeatDelay
                mayBeEndOfProcedure = true
                //Log.d("Dance","maybe ret because agendaIndex overflow")
            }
        } else if (agendaIndex < (currentDance?.instructions?.size ?: 0) - 1) advanceToNextInstruction = true
        else
        {
            mayBeEndOfProcedure = true
            //Log.d("Dance","maybe ret because nRRoTC=0")
        }

        if (mayBeEndOfProcedure && (saveAgendaIndex > -1))
        {
            //Log.d("Dance","maybe return, numRRoTC=${numberOfRemainingRepeatsOfThisCommand}")
            var returnFromProcedure = false
            if (numberOfRemainingRepeatsOfThisCommand > 0)
            {
                val currentMainSimulationTime = System.currentTimeMillis() - saveTimeStarted
                val nextInstructionAfterProcedure = saveCurrentDance?.instructions?.getOrNull(saveAgendaIndex + 1);
                if (nextInstructionAfterProcedure != null) {
                    if (currentMainSimulationTime + commandRepeatDelay >= nextInstructionAfterProcedure.time) {
                        // even though this is repeated command, now is time to advance to the next instruction
                        //Log.d("Dance","ret because saveTmSt=${saveTimeStarted}, curMainSimTm=${currentMainSimulationTime}, comRepDel=${commandRepeatDelay}, next.time=${nextInstructionAfterProcedure.time}")
                        returnFromProcedure = true
                    }
                }
            }
            else {
                //Log.d("Dance","ret because numRRoTC=${numberOfRemainingRepeatsOfThisCommand}")
                returnFromProcedure = true
            }

            // end of procedure
            if (returnFromProcedure)
            {
                //Log.d("Dance","return")
                currentDance = saveCurrentDance
                agendaIndex = saveAgendaIndex
                timeStarted = saveTimeStarted
                saveAgendaIndex = -1
                advanceToNextInstruction = true
                if (emergency)
                    timeStarted = System.currentTimeMillis() - (currentDance?.landingTime ?: 86400000)
            }
        }

        if (advanceToNextInstruction)
        {
            agendaIndex++;
            numberOfRemainingRepeatsOfThisCommand = 1
            currentSimulationTime = System.currentTimeMillis() - timeStarted
            Log.d("Dance", "agendaIndex=${agendaIndex}")
            val nextInstruction = currentDance?.instructions?.getOrNull(agendaIndex);
            if (nextInstruction != null)
            {
                if (currentSimulationTime >= nextInstruction.time) {
                    return 0   // we are behind the agenda
                }
                else return nextInstruction.time - currentSimulationTime
            }
        }

        return delayToReturn
    }

    private fun ledCommand(state : Boolean)
    {
        val keyManager = KeyManager.getInstance()
        val ledKey = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)

        val ledSet = LEDsSettings()
        ledSet.frontLEDsOn = state
        ledSet.navigationLEDsOn = state
        ledSet.statusIndicatorLEDsOn = state

        keyManager?.setValue(ledKey, ledSet, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d("Dance", "LEDs successfully turned")
            }
            override fun onFailure(error: IDJIError) {
                Log.e("Dance", "could not turn leds: ${error.description()}")
            }
        })
    }

    private fun takeoffCommand()
    {
        Log.d("Dance","takeoff command" )
        if (emergency) return
        val keyManager = KeyManager.getInstance()
        val takeoffKey = KeyTools.createKey(FlightControllerKey.KeyStartTakeoff)
        keyManager?.performAction(takeoffKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(nil: EmptyMsg) {
                toPointGimbal = true
                Log.d("Dance","taking off now (5 seconds wait)" )
                Handler(Looper.getMainLooper()).postDelayed({
                    initAfterTakeoff()
                }, 5000)
            }
            override fun onFailure(error: IDJIError) {
                Log.d("Dance","taking off failed $error" )
            }
        })
    }

    private fun initAfterTakeoff()
    {
        VirtualStickManager.getInstance().init()
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                // Handle success case
                Log.i("Dance", "virtualstick enabled, taking off completed")
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                hasTakenOff = true
            }

            override fun onFailure(error: IDJIError) {
                // Handle failure case
                Log.e("Dance", "Failed to enable Virtual Stick mode: ${error?.description()}")
            }
        })
    }

    private var landingStarted = false
    private fun landCommand()
    {
        landingStarted = false
        Log.d("Dance","land command" )
        val keyManager = KeyManager.getInstance()
        val autoLandingKey = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding)
        val flightModeKey = KeyTools.createKey(FlightControllerKey.KeyFlightMode)
        if (flyingAllowed)
            keyManager?.performAction(autoLandingKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(nil: EmptyMsg) {
                    Log.d("Dance","landing now" )
                    landingStarted = true
                    Handler(Looper.getMainLooper()).postDelayed({
                       val currentFlightMode = keyManager.getValue(flightModeKey)
                       Log.i("Dance","flightMode=${currentFlightMode}" )
                        if (currentFlightMode == FlightMode.AUTO_LANDING)
                            landCommand()
                        else
                        {
                            hasLanded = true
                            hasTakenOff = false
                            Log.i("Dance","landing completed?" )
                            landCommand()

                        }
                    }, 150)
                }
                override fun onFailure(error: IDJIError) {
                    Log.d("Dance","landing failed $error" )
                    if (emergency) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            confirmForceLanding(keyManager)
                        }, 150)
                    }
                    else {
                        val currentFlightMode = keyManager.getValue(flightModeKey)
                        if (currentFlightMode == FlightMode.AUTO_LANDING)
                        {
                            landCommand()
                        }
                        else if (landingStarted)
                        {
                            hasLanded = true
                            hasTakenOff = false
                            landCommand()
                            Log.i("Dance","but landing completed? (${currentFlightMode})" )
                        }
                    }
                }
            })
    }

    private fun posCommand(args: PosArguments?)
    {
        if (!flyingAllowed || emergency || !hasTakenOff || (args == null)) return

        val handler = Handler(Looper.getMainLooper())
        commandRepeatDelay = 100 // we repeat the pos command every 100 ms until the next command
        numberOfRemainingRepeatsOfThisCommand ++;  // pos command is repeated again and again

        if (args !== lastArgs)
        {
            if (relativeON)
            {
                currentAbsolutePosArgs = relativePos.copy()
                currentAbsolutePosArgs.yaw += args.yaw - referencePoint.yaw
                currentAbsolutePosArgs.z += args.z - referencePoint.z

                val dx = args.x - referencePoint.x
                val dy = args.y - referencePoint.y

                val deltaYaw = relativePos.yaw - referencePoint.yaw
                val cosTheta = kotlin.math.cos(deltaYaw)
                val sinTheta = kotlin.math.sin(deltaYaw)

                // Rotate the displacement into the relative frame
                val rotated_dx = dx * cosTheta - dy * sinTheta
                val rotated_dy = dx * sinTheta + dy * cosTheta

                // Apply to the relative starting point
                currentAbsolutePosArgs.x += rotated_dx
                currentAbsolutePosArgs.y += rotated_dy
            }
            else currentAbsolutePosArgs = args

            lastArgs = args
        }
        Log.d("Dance","pos command [${cameraPosition[0]}, ${cameraPosition[1]}, ${cameraPosition[2]}, ${cameraPosition[3]}] -> [${currentAbsolutePosArgs.x}, ${currentAbsolutePosArgs.y}, ${currentAbsolutePosArgs.z}, ${currentAbsolutePosArgs.yaw}]" )

        if (cameraPosition[0] > 900.0f) return  // position not available

        // our coordinate system is alpha positive CCW, but for SDK, yaw is positive CW, invert:
        var delta_yaw = cameraPosition[3] - currentAbsolutePosArgs.yaw
        if (delta_yaw > Math.PI * 2) delta_yaw -= (Math.PI * 2).toFloat()
        if (delta_yaw < 0) delta_yaw += (Math.PI * 2).toFloat()
        if (delta_yaw > Math.PI) delta_yaw = delta_yaw - (Math.PI * 2).toFloat();
        delta_yaw = delta_yaw / Math.PI.toFloat() * 180.0f

        var delta_alt = currentAbsolutePosArgs.z - cameraPosition[2]
        if (delta_alt > verticalSpeedLimit) delta_alt = verticalSpeedLimit.toFloat()
        else if (delta_alt < -verticalSpeedLimit) delta_alt = -verticalSpeedLimit.toFloat()

        val worldVely = (currentAbsolutePosArgs.y - cameraPosition[1])
        val worldVelx = (currentAbsolutePosArgs.x - cameraPosition[0])

        Log.d("Dance","pos command before rotate: dx=${worldVelx}, dR=${worldVely}")

        var delta_pitch = worldVely * Math.cos(-cameraPosition[3].toDouble()) + worldVelx * Math.sin(-cameraPosition[3].toDouble())
        var delta_roll = -worldVely * Math.sin(-cameraPosition[3].toDouble()) + worldVelx * Math.cos(-cameraPosition[3].toDouble())

        Log.d("Dance","pos command: dP=${delta_pitch}, dR=${delta_roll}")

        if (delta_pitch > horizontalSpeedLimit)
            delta_pitch = horizontalSpeedLimit
        else if (delta_pitch < -horizontalSpeedLimit)
            delta_pitch = -horizontalSpeedLimit
        if (delta_roll > horizontalSpeedLimit)
            delta_roll = horizontalSpeedLimit
        else if (delta_roll < -horizontalSpeedLimit)
            delta_roll = -horizontalSpeedLimit

        if ((Math.abs(delta_pitch) > horizontalSpeedLimit / 2.0) ||
            (Math.abs(delta_roll) > horizontalSpeedLimit / 2.0))
        {
            if (delta_yaw > 30.0f) delta_yaw = 30.0f
            else if (delta_yaw < -30.0f) delta_yaw = -30.0f
        }
        else if (delta_yaw > 60.0f) delta_yaw = 60.0f
        else if (delta_yaw < -60.0f) delta_yaw = -60.0f

        handler.post(object : Runnable {
            override fun run() {
                // Create and send control data
                val controlParam = VirtualStickFlightControlParam(
                    delta_roll / 1.0,
                    delta_pitch / 1.0,
                    delta_yaw.toDouble() / 1.0,
                    delta_alt.toDouble() / 1.0,   // can adjust these to 2.0 for example
                    VerticalControlMode.VELOCITY,
                    RollPitchControlMode.VELOCITY,
                    YawControlMode.ANGULAR_VELOCITY,
                    FlightCoordinateSystem.BODY)
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(controlParam)
                Log.d("Dance","pos command: P: w=[${worldVelx}, ${worldVely}], dP=${delta_pitch}, dR=${delta_roll}, dY=${delta_yaw}, dA=${delta_alt}")
            }
        })
    }

    private fun flyCommand(args : FlyArguments?)
    {
        Log.d("Dance","fly command" )
        if (!flyingAllowed || emergency || !hasTakenOff || (args==null)) return
        val handler = Handler(Looper.getMainLooper())
        commandRepeatDelay = 100 // we repeat the fly command every 100 ms until the next command
        numberOfRemainingRepeatsOfThisCommand ++;  // fly command is repeated again and again

        handler.post(object : Runnable {
            override fun run() {
                // Create and send control data
                val controlParam = VirtualStickFlightControlParam(
                        args.roll.toDouble(),
                        args.pitch.toDouble(),
                        args.yaw.toDouble(),
                        args.altitude.toDouble(),
                        when (args.altMode) {
                            InstructionPosValue.POS -> VerticalControlMode.POSITION
                            InstructionPosValue.VELOCITY -> VerticalControlMode.VELOCITY
                        },
                        when (args.RPMode) {
                            InstructionAngularValue.VELOCITY -> RollPitchControlMode.VELOCITY
                            InstructionAngularValue.DEG -> RollPitchControlMode.ANGLE
                            InstructionAngularValue.POS -> RollPitchControlMode.POSITION
                        },
                        when (args.yawMode) {
                            InstructionAngularValue.DEG -> YawControlMode.ANGLE
                            InstructionAngularValue.POS -> YawControlMode.ANGLE
                            InstructionAngularValue.VELOCITY -> YawControlMode.ANGULAR_VELOCITY
                        },
                        when (args.coordSystem) {
                            InstructionCoordSystem.GROUND -> FlightCoordinateSystem.GROUND
                            InstructionCoordSystem.BODY -> FlightCoordinateSystem.BODY
                        }
                )
                VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(controlParam)
                Log.d("Dance","fly command: P: ${args.pitch}, R: ${args.roll}, Y: ${args.yaw}, A: ${args.altitude}, AM: ${args.altMode}, RPM: ${args.RPMode}, YM: ${args.yawMode}, CS: ${args.coordSystem}")
            }
        })
    }

    private fun runCommand(args : RunArguments?)
    {
        if (emergency) return
        Log.d("Dance","run command " + args?.name )
        saveCurrentDance = currentDance
        saveAgendaIndex = agendaIndex
        saveTimeStarted = timeStarted
        timeStarted = System.currentTimeMillis()
        agendaIndex = 0
        currentDance = currentDance?.procedures?.get(args?.name)
    }

    private fun performCommand()
    {
        val cmd = currentDance?.instructions?.getOrNull(agendaIndex)
        if (numberOfRemainingRepeatsOfThisCommand > 0) {
            numberOfRemainingRepeatsOfThisCommand--;
            when (cmd?.kind) {
                DanceInstructionKind.LEDS -> ledCommand(
                    (cmd.arguments as? LedsArguments)?.show ?: true
                )
                DanceInstructionKind.TAKEOFF -> takeoffCommand()
                DanceInstructionKind.LAND -> landCommand()
                DanceInstructionKind.POS -> posCommand(cmd.arguments as? PosArguments)
                DanceInstructionKind.RUN -> runCommand(cmd.arguments as? RunArguments)
                DanceInstructionKind.RELATIVE -> {
                    relativePos = cmd.arguments as PosArguments
                    relativeON = true
                    Log.d("Dance", "RelativeON [${relativePos.x},${relativePos.y},${relativePos.z},${relativePos.yaw}]")
                }
                DanceInstructionKind.REFPOINT -> {
                    referencePoint = cmd.arguments as PosArguments
                    Log.d("Dance", "refPoint [${referencePoint.x},${referencePoint.y},${referencePoint.z},${referencePoint.yaw}]")
                }
                DanceInstructionKind.ABSOLUTE -> {
                    relativeON = false
                    Log.d("Dance", "RelativeOFF")
                }
                DanceInstructionKind.HSPEED -> horizontalSpeedLimit = (cmd.arguments as SpeedArguments).limit
                DanceInstructionKind.VSPEED -> verticalSpeedLimit = (cmd.arguments as SpeedArguments).limit
                DanceInstructionKind.END -> {
                    if (hasTakenOff && !hasLanded) landCommand()
                    performanceCompleted = true
                    Log.i("Dance", "Performance completed")

                    runOnUiThread {
                        finishAffinity() // Closes all activities
                        exitProcess(0)
                    }
                }

                DanceInstructionKind.FLY -> flyCommand(cmd.arguments as? FlyArguments)
                DanceInstructionKind.NONE -> return
                null -> return
            }
        }
    }

    private fun startDancing()
    {
        currentDance = dances?.get(selectedDance) ?: return

        // todo: publish this handler so that its events can be descheduled from emergency
        //       handler.removeCallbacks(runnable)
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                performCommand()
                if (!performanceCompleted)
                    handler.postDelayed(this, howMuchToWaitForNextCommand())
            }}, howMuchToWaitForNextCommand())
    }

    private fun land() {
        Log.i("mainactivity", "land()")

        val keyManager = KeyManager.getInstance()
        val autoLandingKey = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding)
        if (flyingAllowed)
            keyManager?.performAction(autoLandingKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(nil: EmptyMsg) {
                    Log.d("Landing action","landing now" )
                    Handler(Looper.getMainLooper()).postDelayed({
                        land()
                    }, 150)
                }
                override fun onFailure(error: IDJIError) {
                    Log.d("Landing action","landing failed $error" )
                    if (emergency) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            confirmForceLanding(keyManager)
                        }, 150)
                    }
                }
            })
    }

    private fun confirmForceLanding(keyManager: KeyManager?) {
        val confirmKey = KeyTools.createKey(FlightControllerKey.KeyConfirmLanding)
        keyManager?.performAction(confirmKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(m: EmptyMsg) {
                Log.d("Landing", "Forced landing confirmed")
                land()
            }

            override fun onFailure(error: IDJIError) {
                Log.d("Landing", "Forced landing also failed: $error")
                land()
            }
        })
    }
}