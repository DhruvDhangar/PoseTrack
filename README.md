# 🎯 PoseTrack

**PoseTrack** is an Android indoor navigation application that uses **Dead Reckoning** and **Visual SLAM** to track position without GPS. Perfect for indoor environments where GPS signals are unavailable.

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)

## 📱 Features

### Dead Reckoning Mode
- **Sensor Fusion**: Combines accelerometer, gyroscope, magnetometer, and step detector
- **Advanced Filtering**: 
  - Complementary filter for gravity compensation
  - Madgwick filter for orientation estimation
  - Kalman filter for position estimation
- **Zero-Velocity Updates (ZUPT)**: Detects stationary periods to correct drift
- **Dynamic Step Detection**: Adaptive stride length estimation
- **Real-time Visualization**: Live path plotting with quality indicators

### Visual SLAM Mode
- **EKF-SLAM Implementation**: Extended Kalman Filter for mapping and localization
- **Feature Detection**: FAST corner detection with BRIEF descriptors
- **Data Association**: Robust landmark matching with Hamming distance
- **Loop Closure**: Automatic drift correction when revisiting areas
- **Camera Integration**: Real-time processing using CameraX

## 🏗️ Architecture

```
PoseTrack/
├── Dead Reckoning Module
│   ├── Sensor fusion (IMU + Step Detection)
│   ├── Kalman filtering
│   └── ZUPT drift correction
├── Visual SLAM Module
│   ├── Feature detection (FAST)
│   ├── Descriptor extraction (BRIEF)
│   ├── EKF-SLAM algorithm
│   └── Map management
└── UI Components
    ├── Splash screen with animations
    ├── Tab-based navigation
    └── Real-time visualization
```

## 🚀 Getting Started

### Prerequisites

- **Android Studio**: Arctic Fox (2020.3.1) or newer
- **Minimum SDK**: API 26 (Android 8.0)
- **Target SDK**: API 36
- **Kotlin**: 1.9+

### Required Permissions

The app requires the following permissions:
- `CAMERA` - For Visual SLAM
- `ACCESS_FINE_LOCATION` - For sensor access
- `ACTIVITY_RECOGNITION` - For step detection (API 29+)
- `POST_NOTIFICATIONS` - For foreground service (API 33+)
- `FOREGROUND_SERVICE` - For background tracking
- `FOREGROUND_SERVICE_LOCATION` - Location-based foreground service

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/yourusername/posetrack.git
cd posetrack
```

2. **Open in Android Studio**
   - File → Open → Select the `posetrack` directory
   - Wait for Gradle sync to complete

3. **Build the project**
```bash
./gradlew build
```

4. **Run on device/emulator**
   - Connect an Android device or start an emulator
   - Click Run (Shift + F10) or use:
```bash
./gradlew installDebug
```

## 📖 Usage

### Dead Reckoning Mode

1. **Launch the app** - You'll see a beautiful splash screen
2. **Select "Dead Reckoning" tab**
3. **Grant permissions** when prompted
4. **Calibration**: Keep the device stationary for 2 seconds
5. **Press START** to begin tracking
6. **Move around** - The app will plot your path in real-time
7. **Quality indicators**:
   - 🟢 Green: High accuracy
   - 🟡 Yellow: Medium accuracy
   - 🔴 Red: Low accuracy (recalibrate)

### Visual SLAM Mode

1. **Select "Visual SLAM" tab**
2. **Grant camera permission**
3. **Press START** to begin tracking
4. **Move camera slowly** - Point at textured surfaces for best results
5. **Blue dots** represent detected landmarks
6. **Red line** shows your trajectory

### Controls

- **START**: Begin tracking
- **STOP**: Pause tracking (preserves current state)
- **RESET**: Clear path and return to origin

## 🛠️ Technical Details

### Dependencies

```kotlin
// Core Android
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("com.google.android.material:material:1.13.0")

// UI Components
implementation("androidx.constraintlayout:constraintlayout:2.2.1")
implementation("androidx.viewpager2:viewpager2:1.1.0")
implementation("androidx.cardview:cardview:1.0.0")

// Camera
val cameraxVersion = "1.5.1"
implementation("androidx.camera:camera-core:$cameraxVersion")
implementation("androidx.camera:camera-camera2:$cameraxVersion")
implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
implementation("androidx.camera:camera-view:$cameraxVersion")

// Computer Vision
implementation("org.opencv:opencv:4.12.0")
```

### Algorithms

#### Dead Reckoning
- **Motion Model**: Constant velocity with acceleration updates
- **Measurement Model**: Step detection with adaptive stride length
- **State Vector**: [x, y, vx, vy] (position and velocity)
- **Update Rate**: 50 Hz (20ms intervals)

#### Visual SLAM
- **State Vector**: [x, y, θ, α₀, r₀, ..., αₙ, rₙ]
  - Robot pose: (x, y, θ)
  - Landmarks: (αᵢ, rᵢ) in polar coordinates
- **Feature Detection**: FAST with adaptive threshold
- **Descriptor**: 128-bit BRIEF
- **Data Association**: Mahalanobis distance < 2.5σ
- **Max Landmarks**: 100 (configurable)

### Performance

- **Dead Reckoning Accuracy**: ±0.5m drift per 10m traveled
- **SLAM Accuracy**: ±0.3m with good features
- **Processing Time**: 
  - Dead Reckoning: <1ms per update
  - SLAM: 30-50ms per frame
- **Memory Usage**: ~50MB typical

## 🐛 Troubleshooting

### Dead Reckoning Issues

**Problem**: Excessive drift
- **Solution**: Recalibrate by keeping device stationary for 2 seconds
- Ensure device is on a stable surface during calibration

**Problem**: No step detection
- **Solution**: Check that ACTIVITY_RECOGNITION permission is granted
- Walk with phone in pocket or hand (natural motion)

### SLAM Issues

**Problem**: No features detected
- **Solution**: Point camera at textured surfaces (avoid blank walls)
- Ensure adequate lighting

**Problem**: Poor tracking quality
- **Solution**: Move camera slowly and smoothly
- Avoid rapid rotations or sudden movements
- Keep camera focused (tap to focus if needed)

### General Issues

**Problem**: App crashes on startup
- **Solution**: Check all permissions are granted
- Verify device has required sensors (accelerometer, gyroscope)

**Problem**: Foreground service error
- **Solution**: Grant notification permission (Android 13+)
- Check that foreground service permission is allowed

## 📊 Sensor Requirements

### Minimum Sensor Requirements
- Accelerometer (required)
- Gyroscope (required)
- Magnetometer (optional, improves heading accuracy)
- Step Detector (optional, improves DR accuracy)
- Camera (required for SLAM)

### Recommended Devices
- Any device with Android 8.0+ and IMU sensors
- Camera with autofocus for best SLAM results
- Gyroscope update rate: ≥50 Hz

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

### Code Style
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable names
- Add comments for complex algorithms
- Write unit tests for new features

## 👥 Authors

**Group 12**
- Dhurv Dhangar (202201144)
- Sahil Chaudhari (202201171)
- Yash Chaudhari (202201229)
- Harsh Baraiya (202201233)
  
## 🙏 Acknowledgments

- **IE415 Course Material** - EKF-SLAM theory and implementation
- **Android Open Source Project** - CameraX and sensor APIs
- **OpenCV** - Computer vision algorithms
- Inspired by indoor navigation research

## 📧 Contact

For questions or support, please open an issue on GitHub.

---

**Note**: This app is designed for educational and research purposes. Accuracy may vary depending on device sensors and environmental conditions.
