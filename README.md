# Clean Home

A minimal home screen for Google TV / Android TV: only the apps you choose, a clock and your own
wallpaper. No ads, no suggestions, no rows of recommended content.

The Google TV home screen stays installed in the background, so the remote's mic / assistant
button keeps working.

- Three themes: **Banners**, **Glass** (frosted dock, the focused app glows in its brand colour)
  and **Apple TV** (liquid-glass look with white app logos).
- Your first row of apps sits at the bottom of the screen; the rest are one press down.
- Wallpaper from your phone: scan a QR code on the TV and pick a photo.
- Rearrange apps Apple TV style: hold OK → **Move**, then use the arrows.
- The remote's Home button opens Clean Home (optional, via an accessibility service).

Tested on a Google TV Streamer (Android 14). Needs Android 9 or newer.

## Download

**[Download CleanHome.apk](https://github.com/kaustavgithub/clean-home-tv/releases/latest/download/CleanHome.apk)**
(latest release; all versions are on the [Releases](https://github.com/kaustavgithub/clean-home-tv/releases) page).

## Install on the TV

Clean Home isn't on the Play Store, so it has to be sideloaded. First turn on developer mode on
the TV: **Settings → System → About**, then click **Android TV OS build** 7 times.

### Option A: without a computer (Downloader app)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Allow it to install apps: **Settings → Apps → Security & restrictions → Unknown sources** →
   turn on **Downloader**.
3. Open Downloader and enter this address:
   `https://github.com/kaustavgithub/clean-home-tv/releases/latest/download/CleanHome.apk`
4. When it finishes downloading, choose **Install**.

### Option B: from a computer with adb

You need adb ([Android platform tools](https://developer.android.com/tools/releases/platform-tools);
on a Mac: `brew install android-platform-tools`).

1. On the TV: **Settings → System → Developer options** → turn on **Wireless debugging**.
2. Open **Wireless debugging → Pair device with pairing code**, then on the computer:
   ```
   adb pair <tv-ip>:<pairing-port>     # enter the 6-digit code shown on the TV
   adb connect <tv-ip>:<port>          # the port shown on the Wireless debugging screen
   adb install -r CleanHome.apk
   ```

On a Mac, `./tv-connect.sh install` does all of this for you (it finds the TV's port and only
pairs when needed). Run it from a normal Terminal / iTerm tab.

### First start

1. Open **Clean Home** from the TV's apps list.
2. Tick the apps you want on your home screen.
3. Press **Home button: Turn on** and switch on **Clean Home** in Accessibility, so the remote's
   Home button opens Clean Home. The service only watches the Home key and which app is on
   screen; nothing is read, stored or sent anywhere.

## Use

- **Pencil** button: choose apps, theme, clock position and wallpaper.
- **Grid** button: all installed apps. Hold OK on one to add it to or remove it from your home screen.
- **Gear** button, or hold Home: TV settings.
- Hold OK on an app on the home screen → **Move**: the tile jiggles; use the arrows to move it
  (up/down moves it a whole row, into or out of the first row), then press OK.
  Or choose **Remove from home**.
- Press down from the first row to reach the rest of your apps; Back scrolls up again.
- **Wallpaper from phone**: scan the QR code with a phone on the same Wi-Fi and pick a photo.
  The upload page only runs while that window is open, at a random secret address.
- After a voice search, Google's results stay on screen; press Home to come back.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 34).

```
git clone https://github.com/kaustavgithub/clean-home-tv.git
cd clean-home-tv
echo "sdk.dir=/path/to/your/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

The release build is signed with your local Android debug key, which is fine for sideloading.
Android only installs an update over an existing install when both are signed with the same key,
so to switch between the downloaded APK and your own build, uninstall the other one first.
