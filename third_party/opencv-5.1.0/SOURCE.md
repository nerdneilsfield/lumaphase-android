# OpenCV 5.1.0 Android AAR (pinned)

Source: https://repo.maven.apache.org/maven2/org/opencv/opencv/5.1.0/opencv-5.1.0.aar
POM: https://repo.maven.apache.org/maven2/org/opencv/opencv/5.1.0/opencv-5.1.0.pom
License: Apache-2.0 (LICENSE from https://raw.githubusercontent.com/opencv/opencv/5.1.0/LICENSE)

Downloaded 2026-11-11. No build-time download is configured: `app/build.gradle.kts`
consumes the local AAR file.

AAR metadata checked with `unzip -l` / `javap`:

- size: 147,907,333 bytes
- SHA-1: 5c029fb7a2a2022b93d94101bfb2711885ad4254
- SHA-256: a4e60c59a08b693d18deeba20574e9f003182c42fc0d433fdeab8446300f037e
- POM SHA-256: 16a0e20d0a9226e86d3e109ba11e05c6946ecf6514f747c16586e70708d96293
- LICENSE SHA-256: cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30
- AndroidManifest: package `org.opencv`, minSdk 21
- native libraries: `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libopencv_java5.so`
  plus `libc++_shared.so`
- loader: `org.opencv.android.OpenCVLoader.initLocal()` and `initDebug()` exist
  in `classes.jar`
- detection: `org.opencv.objdetect.Objdetect.getPredefinedDictionary(int)` and
  `Objdetect.DICT_4X4_50`; `ArucoDetector.detectMarkers(Mat, List<Mat>, Mat)`
- homography: OpenCV 5.1 moved the Java binding to
  `org.opencv.geometry.Geometry.findHomography(MatOfPoint2f, MatOfPoint2f, int)`;
  it is not in `org.opencv.calib.Calib`.
