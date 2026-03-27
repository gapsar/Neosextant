<p align="center">
  <h1 align="center">Neosextant</h1>
  <p align="center">
    <strong>Turn your phone into a sextant — navigate by the stars, completely offline.</strong>
  </p>
  <p align="center">
    <a href="https://github.com/gapsar/Neosextant/releases">
      <img alt="Latest Release" src="https://img.shields.io/github/v/release/gapsar/Neosextant?style=for-the-badge&color=blue" />
    </a>
    <a href="https://github.com/gapsar/Neosextant/blob/main/LICENSE.md">
      <img alt="License" src="https://img.shields.io/github/license/gapsar/Neosextant?style=for-the-badge&color=green" />
    </a>
    <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%208.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  </p>
</p>

---

Neosextant revives the ancient art of celestial navigation for the smartphone era. Point your camera at the night sky, and the app identifies stars, measures altitudes, and calculates your geographic position on Earth — **no internet required, no GPS needed**.

It performs real **on-device astrometry** (commonly called "plate-solving") using a hybrid engine combining Rust, Python, and Kotlin, then runs an iterative mathematical solver to pinpoint your latitude and longitude from the stars alone.

---

## Key Features

| Feature | Description |
|---|---|
| **Offline Celestial Navigation** | Get a positional fix using just your phone's camera and sensors — no internet, no GPS. |
| **Iterative Position Solver** | An automated least-squares solver converges on your position through multiple iterations, eliminating the need for a precise initial estimate. |
| **Hybrid Rust / Python Engine** | Star detection via a blazing-fast Rust binary ([cedar-detect](https://github.com/smroid/cedar-detect)); plate-solving and astronomy via Python ([tetra3](https://github.com/esa/tetra3), [astropy](https://www.astropy.org/)). |
| **Offline Map Display** | View your calculated fix on an interactive map powered by [osmdroid](https://github.com/osmdroid/osmdroid). |
| **Multi-Language** | Full support for **English**, **French**, and **Spanish**. |
| **Sensor Calibration** | Built-in horizon-based calibration to correct device pitch errors for accurate altitude readings. |
| **Precision Time Sync** | Opportunistic NTP sync with GPS fallback — because accurate time is everything in celestial navigation. |
| **Observation History** | Persistent storage of all navigational fixes via a local Room database. |
| **Interactive Tutorial** | A first-launch onboarding experience to get new users up to speed quickly. |

---

## How It Works

Neosextant combines modern computer vision with time-tested celestial navigation mathematics. The end-to-end pipeline runs entirely on your device:

### Step 1 — Capture

Point your phone at a star-filled sky and take a photo. The app simultaneously records the phone's orientation (pitch angle) using its calibrated internal sensors to measure the observed altitude of the celestial body.

### Step 2 — Plate-Solve (Astrometry)

The captured image is analyzed on-device through a two-stage pipeline:

1. **Star Detection** — The [cedar-detect](https://github.com/smroid/cedar-detect) Rust binary rapidly extracts star positions (centroids) from the image.
2. **Pattern Matching** — The star pattern is matched against a bundled star catalog using [tetra3](https://github.com/esa/tetra3) (ESA) to determine exactly where the camera was pointing — yielding precise **Right Ascension** and **Declination** coordinates.

### Step 3 — Position Fix (Iterative Solver)

Once three observations are collected, the iterative solver kicks in:

1. **Geographic Position (GP)** of each observed star is calculated using sidereal time.
2. **Computed Altitude (Hc)** and **Azimuth** for each star are derived from the estimated position.
3. A **Least Squares** fit iteratively shifts the estimated position until it converges — typically within a few iterations — yielding your final **latitude and longitude**.

> The solver also supports a classic **Lines of Position (LOP)** mode using the Marcq St. Hilaire intercept method, selectable in settings.

---

## Architecture & Tech Stack

| Layer | Technology | Role |
|---|---|---|
| **Frontend** | Kotlin · [Jetpack Compose](https://developer.android.com/jetpack/compose) | UI, camera capture, sensor fusion, navigation |
| **Backend** | Python · [Chaquopy SDK](https://chaquo.com/chaquopy/) | Astrometry, celestial calculations, solver logic |
| **Star Detection** | Rust · [cedar-detect](https://github.com/smroid/cedar-detect) | Fast centroid extraction from sky images |
| **Astrometry** | [tetra3](https://github.com/esa/tetra3) · [astropy](https://www.astropy.org/) | Plate-solving, coordinate transforms, time handling |
| **Data** | Room Database | Persistent observation history |
| **Maps** | [osmdroid](https://github.com/osmdroid/osmdroid) | Offline-capable map rendering |

---

## Installation

### Download the APK (Recommended)

1. Go to the [**Releases**](https://github.com/gapsar/Neosextant/releases) page.
2. Download the latest `.apk` file.
3. Transfer it to your Android device (or download directly on your phone).
4. Open the APK to install.

> [!NOTE]
> Your phone will warn you that this app is from an "unknown source" — this is normal for any app installed outside the Play Store. You'll need to allow installation from unknown sources. The app is fully open-source and contains no malicious code.

### Build from Source

**Prerequisites:** [Android Studio](https://developer.android.com/studio) and **Java 17**.

```bash
git clone https://github.com/gapsar/Neosextant.git
```

1. Open the project in Android Studio.
2. Let Gradle sync — Chaquopy will automatically set up the Python environment and install pip dependencies.
3. Build and install on a **physical Android device**.

> [!IMPORTANT]
> Emulators lack realistic sensor outputs (gyroscope, accelerometer) and cannot produce meaningful celestial observations.

---

## Usage Guide

1. **Choose Your Language** — On first launch, pick English, French, or Spanish. You can also change this later in Settings → System Parameters.
2. **Follow the Tutorial** — The interactive onboarding walks you through every screen. It can be replayed from Settings → System Parameters.
3. **Calibrate Your Sensors** — Go to Settings → *Calibrate Sensors*. Align your phone with a flat, level horizon to correct for pitch sensor manufacturing errors. This is **critical** for accuracy.
4. **Take 3 Observations** — Aim your camera at different parts of the night sky. After each capture, the app plate-solves the image and displays the result. Spread your observations across different directions for the strongest fix.
5. **Get Your Position** — After 3 valid observations, the app automatically navigates to the map screen. Your calculated latitude, longitude, and estimated error are displayed, and your position is plotted on the map.
6. **Review History** — Access all past fixes from Settings → *View Position History*.
7. **Red Tint Mode** — Preserve your night vision by toggling Red Tint Mode in Settings → System Parameters.

---

## Disclaimer

> [!WARNING]
> This is an experimental project. Position fixes may not be perfectly accurate. The app is intended for **educational and experimental use only** — **do not rely on it for actual navigation.**

---

## Contributing & Feedback

This project is a work in progress and I'd love your input!

- **Found a bug?** → [Open an Issue](https://github.com/gapsar/Neosextant/issues)
- **Have feedback or ideas?** → [Join the Discussions](https://github.com/gapsar/Neosextant/discussions)
- **Want to contribute?** → Pull requests are welcome!

> [!NOTE]
> The core celestial navigation logic (the Python backend) was written entirely by me. Since then, I've used AI assistance to port the UI to Kotlin (a language I'm still learning) and to add some features. If you spot anything odd, please open an issue!

---

## Acknowledgements

This project builds on the work of incredible open-source projects:

- **[cedar-detect](https://github.com/smroid/cedar-detect)** — Fast, accurate star centroid detection in Rust.
- **[tetra3](https://github.com/esa/tetra3)** — Lost-in-space plate solver from ESA.
- **[astropy](https://www.astropy.org/)** — Astronomical computations and coordinate transforms.
- **[Chaquopy](https://chaquo.com/chaquopy/)** — Python SDK for Android.
- **[osmdroid](https://github.com/osmdroid/osmdroid)** — Offline map rendering for Android.

---

## 📄 License

Licensed under the **PolyForm Noncommercial License 1.0.0**. See [LICENSE.md](LICENSE.md) for details.

The included astrometry libraries ([tetra3](https://github.com/esa/tetra3), [cedar-detect](https://github.com/smroid/cedar-detect)) retain their respective Apache 2.0 terms.

---

## 📚 Documentation

For deep technical dives into the architecture, solver algorithms, and build system, see the [docs/](docs/) directory.
