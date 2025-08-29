# Carlink
Android App for Apple Carplay and Android Auto Projection using Flutter

using the Carlinkit CPC200-CCPA Adapter [Amazon US](https://a.co/d/d1eatDz).

## Recreation
This project is a rewrite of "Carplay" by Abuharsky. However, due to deprecated code, the original source could not be easily used.
I was able to get a stable 1 for 1 apk built by updating parts of their code. [Update Project](https://github.com/lvalen91/abu-Carplay)  

Credit:
- [Carplay by Anuharsky](https://github.com/abuharsky/carplay)
- [Node-Carplay by Rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay)
- [Pi-Carplay by f-io](https://github.com/f-io/pi-carplay)
- [PyCarplay by Electric-Monk](https://github.com/electric-monk/pycarplay)


## Dependencies
This project was build using the following.

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

### Rebuilding
You can build the apk or aab yourself by doing the following.

1. Change the ApplicationName and ID in [project_config.gradle](https://github.com/lvalen91/Carlink/blob/main/project_config.gradle). Also the versioncode and versionname for your own testing and Play Store Dev Console uploading.
2. With Flutter+Dart installed and this project downloaded. Go to carlink/examples/android and using a terminal 'flutter pub get' and './gradlew clean'
those commands will download the needed cache and sync the project. Without those Android Studio will give errors during compilation.
3. In Android Studio open this project from here carlink/examples/android then following the normal building apk or aab procedure.

I've tested this several times so it will work. The more differences between your setup and mind the higher the chances something will fail. So, be ready to figure it out.

### Intended Hardware

I tested this project and built it around my Chevy Silverado and Apple Carplay. Therefore, my test bench was the Silverado, and that’s what I wanted it to work on. My AAOS radio is a gminfo3.7-3.8 running on an Intel Atom x86_64 SOC, so it has been optimzed for the Intel iGPU.

Your mileage may vary if its not the same radio. I have not tested this project on anything else.
The same goes if youre inteded Projection is Android Auto.

### Limitations

Video-Only (...for now)
Similar to the original app this is project is build video-only. This app will only provide Video Output from the CPC200-CCPA adapter. It's configured to not advertise audio support to the phone. While the adapter itself can provide simultaneous low-latency audio and video, I’ve had no luck getting a stable audio output. This is a limitation of the app itself, not the adapter.

### Documents
I've included in this project (docs folder) a few documents. I've been researching this adapter and how to go about using it on my GM radio for a few months. I've fed all that i've learned from the other projects and my own research into chatGPT and Claude. One Document is reference for you and the other should be easily ingested into any generative AI model (formatted for low token use). They can then elaborate on this project and more technical details on the adapter itself.

### Some Advice

Unless youre knowledage, I would not mess around with the .Dart files in this project. They can easily break and cause issues if theyre modified. There's a reason the other repos have kept to the original communication logic used by the adapter developed from pycarplay and node-carplay. Carlinkit has obfuscated/encrypted the Autokit apk and Adapter firmware to prevent reverse engineering.

Adding new features to the adapter itself is at the time of this writing, impossible.

I have [another repo](https://github.com/lvalen91/CPC200-CCPA-Firmware-Dump) where I'm working on modifying the adapter firmware itself.

### State of the Project

Consider this project as-is.

While I will continue to work on this project on my personal time, don't expect updates. I would like to eventually add proper audio support but there is no ETA and this project can very easily not improve from it's current state.
