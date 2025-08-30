# Carlink
Android App for Apple CarPlay and Android Auto Projection using Flutter.

For the Carlinkit CPC200-CCPA Adapter [Amazon US](https://a.co/d/d1eatDz).

## Recreation
This project is a rewrite of "Carplay" by Abuharsky. However, due to deprecated code, the original source could not be easily used.
I was able to get a stable 1: 1 APK built by updating parts of their code. [Update Project](https://github.com/lvalen91/abu-Carplay)  

Credit:
- [Carplay by Anuharsky](https://github.com/abuharsky/carplay)
- [Node-Carplay by Rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay)
- [Pi-Carplay by f-io](https://github.com/f-io/pi-carplay)
- [PyCarplay by Electric-Monk](https://github.com/electric-monk/pycarplay)


## Dependencies
This project was built using the following.

Android Studio Narwhal Feature Drop | 2025.1.2 Patch 1
macOS 26.0

Flutter & Dart:
  - Flutter: ≥3.24.0
  - Dart: ≥3.5.0 <4.0.0

  Gradle:
  - Gradle Wrapper: 8.13
  - Android Gradle Plugin (AGP): 8.12.1

  Java:
  - Java Version: 21 (JDK 21)
  - JVM Target: 21
    
Android Verions:
  - Target SDK: Android 14 (API 34)
  - Minimum SDK: Android 12 (API 31)
  - Compile SDK: Android 15 (API 35)

* Java 21 comes with Android Studio 2025.1.2
* Flutter and Dart must be installed as a plugin within Android Studio. AND [Flutter SDK](https://docs.flutter.dev/install/manual) installed in your OS. Follow official Documentations in pointing Android Studio to your Flutter SDK location.

### Rebuilding
You can build the apk or aab yourself by doing the following.

1. Change the ApplicationName and ID in [project_config.gradle](https://github.com/lvalen91/Carlink/blob/main/project_config.gradle). Also the versioncode and versionname for your own testing and Play Store Dev Console uploading.
2. With Flutter+Dart installed and this project downloaded. Go to carlink/examples/android and using a terminal 'flutter pub get' and './gradlew clean'
those commands will download the needed cache and sync the project. Without those Android Studio will give errors during compilation.
3. In Android Studio open this project from here carlink/examples/android then following the normal building apk or aab procedure.
4. Use Google Play Store Dev Console and upload your aab. Following proper Play Store Console procedures and push a test build to your vehicle.

I’ve tested this multiple times to ensure its functionality. The greater the differences between your setup and mine, the higher the likelihood of issues happening. Therefore, be prepared to troubleshoot and resolve any problems that arise. I can only help so much before its all on you.

### Intended Hardware

I tested this project and built it specifically for my Chevy Silverado and Apple Carplay. Therefore, my test bench was the Silverado, and that’s the device I wanted it to work on. My AAOS radio is a gminfo3.7-3.8 running on an Intel Atom x86_64 SOC. This project has H264 code specific for the Intel iGPU, but it shouldn't affect rendering too much if used on something else.

Your mileage may vary if its not the same radio. I have not tested this project on anything else.
The same goes if you're intended Projection is Android Auto.

### Limitations

Video-Only (...for now)
Similar to the original app, this project is built as video-only. This app will only provide Video Output from the CPC200-CCPA adapter. The adapter is intialized to not advertise audio support (audioTransferMode) to the phone. While the adapter itself can provide simultaneous low-latency audio and video, I’ve had no luck getting the app to process a stable audio output. This is a limitation of the app itself, not the adapter.

### Documents
II've included in this project (docs folder) a few documents. I've been researching this adapter and how to go about using it on my GM radio for a few months. I've fed all that I’ve learned from the other projects and my own research into ChatGPT and Claude. One document is a reference for you, and the other should be easily ingested into any generative AI model (formatted for low token use). It can then elaborate on this project and provide more technical details on the adapter itself.

### Some Advice

Unless you have knowledge of Dart, I would not mess around with the .Dart files in this project. They can easily break and cause issues if they're modified. There's a reason the other repos have kept to the original communication logic used by the adapter and developed from pycarplay and node-carplay. Carlinkit has obfuscated/encrypted the Autokit apk and Adapter firmware to prevent reverse engineering. So current features and adapter control logic is from several years ago when an engineer decrypted the firmware. We don't know what has changed since then.

At the time of this writing, it is impossible to add new features to the adapter itself. (Unless someone discovers otherwise.)

I have [another repo](https://github.com/lvalen91/CPC200-CCPA-Firmware-Dump) where I'm working on modifying the adapter firmware itself. The goa? idk yet, just pokinging and probing

# State of the Project

Consider this project as-is.

While I’ll continue working on this project on my own time, I won’t be able to provide updates. I’d like to add proper audio support eventually, but there’s no timeline for it, and this project could easily remain in its current state.
