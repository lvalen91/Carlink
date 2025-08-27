** DO NOT MODIFTY DART FILES OR ANY CODE LOGIC INVOLVED IN DONLGE COMMUNICATION **
Trial and error has found the original implementation is successful. Modiftying that code is not worth the effort.
Things will begin to break very quickly.

Modify the app code around it. It's why other projects have resorted to using the original pycarplay and node-carplay communication logic.

Modify project_config.gradle with your own name and version number.
That will sync across the project upon building.

Import project in Android Studio from this location: carlink/example/android/ 

* Optimized for GM Radio containg x86_64 Intel Atom platform. (gminfo3.7 -3.8)
* Simple AAOS Distraction Optimization (Push via PlayStore so GM AAOS honors it).
* Has logcat debugging code still active.

 - Audio Output not supported. Video Only.
audioTransfer in carlink/lib/sendable.dart is set to 'false' which sets the Adapter to not forward
Carplay audio. Setting it to 'true' will cause raw PCM audio to be recieved by the app.
It is not currently processed/rendered.

Built upon https://github.com/lvalen91/abu-Carplay

Based on Work from:
https://github.com/electric-monk/pycarplay
https://github.com/rhysmorgan134/node-CarPlay
https://github.com/f-io/pi-carplay
https://github.com/abuharsky/carplay
