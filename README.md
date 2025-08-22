# Krucena (an android multiple drone controller)

This Android app allows an indoor coordinated flight of multiple DJI Mini 3 drones based 
on flight instructions specified in text files and localization mat placed on the floor.
It can also start synchronized music from VLC music server.
Limited range - the mat must always remain visible to the drone's downward-facing camera

## Demo videos

- [some wild classics](https://kempelen.dai.fmph.uniba.sk/files/tanec1.mp4)
- [folk music](https://kempelen.dai.fmph.uniba.sk/files/tanec2.mp4)
- [fairy tale](https://kempelen.dai.fmph.uniba.sk/files/tanec3.mp4)
- [most advanced](https://kempelen.dai.fmph.uniba.sk/files/tanec4.mp4)

They are with H.265 codec that probably only Chrome browser can play.

(For the proper starting locations for these demo dances, see files 
[start_dance_1.jpg](https://github.com/Robotics-DAI-FMFI-UK/Krucena/blob/main/start_dance_1.jpg), 
[start_dance_2.jpg](https://github.com/Robotics-DAI-FMFI-UK/Krucena/blob/main/start_dance_2.jpg), 
[start_dance_3.jpg](https://github.com/Robotics-DAI-FMFI-UK/Krucena/blob/main/start_dance_3.jpg), 
[start_dance_4.jpg](https://github.com/Robotics-DAI-FMFI-UK/Krucena/blob/main/start_dance_4.jpg))


## Localization mat

See [floor/floor_new_merged.png](https://kempelen.dai.fmph.uniba.sk/files/floor/)

There is also a version split into 2 parts which are easier to print, handle and store, 
but must be aligned properly - usual transparent tape is ok.
The outer edge of all squares is exactly 1m (total banner size 250 x 262.65 cm, 150 dpi)


## Running

After installing the app on your Android device that will be connected to DJI N1 remote 
control, you need to copy the config and dance files. From command prompt:

```
adb push config.txt /data/local/tmp/
adb shell "run-as sk.uniba.krucena cp /data/local/tmp/config.txt files/"

adb push dance_K.txt /data/local/tmp/
adb shell "run-as sk.uniba.krucena cp /data/local/tmp/dance_K.txt files/"   - where K is each drone's number 1..6 (dances with 4 drones use 1,2,5,6).
```

Then the app can be run when remote is connected, but before flying we need to calibrate 
the color detection, because your light conditions and color tones of the mat printout 
will be for sure different than mine and my attempts to do this automatically failed.

You also will need to confirm all the pop-up dialogs about permissions, etc.

When the app starts, press `[Config]` button, and calibrate each color - while holding 
the drone by hand above the mat so that the selected color is detected properly.
If you do not see `[Config]` button, only view from drone's camera, it probably means
you did not manage to copy the config files (or `drone_idi` is set to `K` and 
`files/dance_K.txt` is missing). Your remote control needs to be already
bound to the drone using the standard DJI Fly app, logged in, updates downloaded, if
they are available. Whenever something is not working, check with official DJI Fly app
first to see if connection with the drone is working there.
Make sure yellow is not detected as red, that blue is not detected as black. Black 
detection is composed of two (logical AND) phases, so 
only regions showing WHITE represent black. It's okay if blue or other colors appear RED
while tuning black detection, but they must not appear WHITE. Always test from various
heights and angles since the illumination will be different in different locations. Some
imperfections (e.g. unavoidable reflections) are acceptable. They will decrease the
localization reliability, but there is redundance - especially when you fly high enough.
Flying lower than 1.6 m may always be unreliable. Ideal range is probably 1.7 - 5 m
(our room was limited to 3.6 m high),

In the bottom options of the config, it is important to specify how many drones fly - only 
one of them should be "master". It is recommended to have all logs and drawing OFF, 
unless you want to debug it. I have left tons of debug info in both Kotlin and C++ files 
for the sake of future modifications, but feel free to strip all those. 

Pressing `[Done]` saves the settings on the device. If you install a new config.txt 
(as shown above) they will be overwritten. Logs are also saved in the `files/` folder.

When something goes wrong, you can touch the screen of the master, and the drones will 
stop moving and land soon. You can prevent landing if needed by placing a hand underneath 
a drone, which will be detected by sensors and postpone landing.

For format of the `dance_K.txt` files, see the files and the `Dance.kt` source file.

All drones must be on-line to fly (DJI SDK sort of requires that) and connected to 
the same Wifi that does not block their mutual TCP communication.


## Music Server

You can use VLC on any machine in local network to play music (such as to speakers) 
that will be started synchronized with the drone dance.  
The VLC on that machine must be started using the following command:

```
vlc -I rc --rc-host 0.0.0.0:4212
```

If you need to use different port, change it in `config.txt`, and also put the IP address of the 
machine where VLC runs into `config.txt`.
The music files must be named `tanecK.mp3`  (for K=1..number_of_performances). 
Also check that VLC plays them normally from GUI interface. If still in trouble, check connecting
to VLC from putty (raw at port 4212) and issue commands `add tanecK.mp3`, `play`, check with
`status` and `is_playing`.



## Building

### Dependencies

1. OpenCV

We use both Java version for Kotlin code (for manipulation with OpenCV data structures in Kotlin)
and C++ version for C++ library that does the actual localization work and uses some OpenCV functions, 
such as thresholding...

But both are included in the same SDK package, so only download OpenCV Android SDK  [opencv.org/releases](https://opencv.org/releases)
Look for a file named like: `OpenCV-android-sdk-<version>.zip`  (we used version 4.11.0)

Unzip it and locate the folder `OpenCV-android-sdk/sdk/java/`

Copy the contents of entire java folder to project under   `openCVLibrary/`
After copying, it should look like:

```
openCVLibrary/
├── src/
├── build.gradle  (it was there already)
├── AndroidManifest.xml   (also was there)
└── ... (other OpenCV files)
```

Also copy the entire contents of `OpenCV-android-sdk/sdk`  folder into this project at:

```
app/opencv/
```

So the structure will look like:

```
app/opencv/sdk/native/  (and its contents)
```

2. DJI Mobile SDK v5  (UXSDK)

Clone the SDK repo in some other folder:

```
   git clone https://github.com/dji-sdk/Mobile-SDK-Android-V5.git
```

Copy the full contents of the android-sdk-v5-uxsdk module into the project's `android-sdk-v5-uxsdk/` folder, 
which you already have. We used version 5.11.


### DJI Developer Account & API Key

To build and run this application, you need your own DJI API key:

1. Visit https://developer.dji.com/ and create a developer account.
2. Create a new application in the DJI Developer Console.
3. Add your application's package name and SHA-1 signature.
4. Put it into gradle.properties file.

After doing all this, sync and build the project in Android Studio.


### Optional: Release Version

The debug version runs fast enough for most purposes and is easier to work with.
If you desire to build a release version, you can try, but be ready for a lot of trouble...:

Why use the release version?
On an good old Samsung Galaxy S8, debug version:  localization runs with about 11 FPS, 
whereas release version with about 13 FPS. For faster platforms, the debug version
will already be even faster.

1. uncomment the line `//#define RELEASE_VERSION` in `fastimglib.cpp`
2. change the build type to release (Build - Select Build Variant - release)
3. edit file proguard-rules.pro to uncomment the rules to strip the logging (near end of file)
4. Build - clean project; Build - Rebuild Project; Build - Build APKs
5. if a debug version of app is already installed, you need to uninstall it, because
   it is using different keys (for example by long tap on icon + uninstall)
6. now install this version of app (not done yet, but one step at a time)
```
     adb install -r app/build/outputs/apk/release/app-release.apk
```
7. The release build is configured to be debuggable, so you can still use adb to copy config files:
```
    cd to Krucena\app\src\main\assets
    adb push config.txt /data/local/tmp/
    adb push dance_K.txt /data/local/tmp/   (for K being that drone ID 1..6)
    adb shell "run-as sk.uniba.krucena cp /data/local/tmp/config.txt files/"
    adb shell "run-as sk.uniba.krucena cp /data/local/tmp/dance_K.txt files/"
```
8. now we need to build the real release, but for that:
     in Krucena - app - build.graddle, change debuggable to false
9. rebuild the project again, also build APKs
10. finally install the release version:
```
     adb install -r app/build/outputs/apk/release/app-release.apk
```

Before attempting this, you may also need to create keystore:

```
 keytool -genkey -v -keystore release.keystore -alias your-key-alias -keyalg RSA -keysize 2048 -validity 10000
```

Place the `release.keystore` file in the `app/`
and set the keystore password in the app's `build.gradle` (signingConfigs),
note the storePassword and keyPassword must be the same (because we use PKCS12 keystore)

To go back to the debug version, undo the steps above.


## Other Info

App's icon credits: [Vector image by VectorStock / Nasturzia](https://www.vectorstock.com/royalty-free-vector/decorative-folk-bird-on-blooming-tree-branch-vector-52826783)

This software was created by transforming one of the original DJI MSDK v5 samples, some remaining unneeded files (in resources) are still there.
It was created with a great help and endless patience of ChatGPT - big thanks!


## Contact

Pavel (`pavel.petrovic@uniba.sk`)
Let me know if you like to setup your own flying demo and need help. 

Developed at Department of Applied Informatics, Faculty of Mathematics and Physics, Comenius University Bratislava
for SteelPark Kosice (and K13 - Kosicke kulturne centra) November 2024 - July 2025.
