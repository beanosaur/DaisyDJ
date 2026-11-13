# DaisyDJ – mobile jukebox 🎵

Guests scan a QR code, type a song, and it plays on the Bluetooth speaker through
**Amazon Music** on the host phone, in the order people asked for them.

```
Guests' phones (any browser)  ──Wi-Fi──►  Host Android phone  ──►  Amazon Music  ──►  🔊 speaker
     scan QR, add songs                 Group Jukebox app:
                                        • guest web page
                                        • the queue
                                        • starts each song
```

- **Guests need nothing installed.** Any phone with a browser works, iPhone included.
- **The host phone must be Android.** iPhones don't let one app control another's music.
- Everyone must be on the same Wi-Fi, **or** guests join the host phone's hotspot.

## How it works
Amazon Music has no public "add to queue" feature, so the app keeps its own queue.
It starts each song by asking Amazon Music to "play from search" (the same request
Google Assistant uses), and watches Amazon Music's now-playing info. When the
requested song ends, it starts the next one. When the queue is empty, Amazon's own
autoplay carries on.

## 1. Get the app (APK)

**Option A – no computer setup (GitHub builds it for you)**
1. Create a free GitHub account and a new **private** repository.
2. Upload this whole folder (keep the `.github` folder; it holds the build instructions).
3. Open the **Actions** tab → *Build APK* → the latest run → download **GroupJukebox-apk**.
4. Unzip it on the host phone and tap `app-debug.apk` to install
   (allow "install unknown apps" when asked).

**Option B – Android Studio**
Open this folder in Android Studio, plug in the phone, and press ▶ Run.

## 2. Set up the host phone (once)
Open **Group Jukebox**. The Setup list shows ✅ or ⚠️ with a **Fix** button for each:
1. **Amazon Music installed.** Open it once, sign in and play anything.
2. **Notification access.** Lets the app see and control what's playing.
3. **Display over other apps.** Lets it start songs while it's in the background.
4. **Battery: unrestricted.** Stops Android closing it mid-party.
5. **Bluetooth speaker** paired and connected.

Then use **Test it → Add song as host** to check a song plays.

## 3. Party time
1. Tap **Start jukebox**.
2. Guests scan the QR code (or type the address under it) and add songs.
3. The host screen has **Pause/Play**, **Skip**, **Clear** and ✕ to remove a song.

Rules built in: up to 3 waiting songs per person, plus a short cooldown between requests from the same phone.

## Known limits (v0.1)
- **Amazon picks the best match** for what's typed, so "song + artist" works best.
  What's available depends on the host's plan (Prime vs Unlimited); on some plans
  Amazon may play a station or shuffle instead of the exact track.
- **Pressing next on the phone or speaker** counts as "song finished", so the next request starts.
- **Local network only** for now. A link that works from anywhere needs a small
  online relay service; that's a possible next step.
- Not yet tested on a real phone. The queue logic and web server have automated
  tests (`gradle testDebugUnitTest`), but the Amazon Music side needs a real-world try.

## Troubleshooting
- **Nothing plays:** open Amazon Music by hand and play something once, re-check
  "Display over other apps", then try again with the test box.
- **Never moves on to the next request:** re-grant notification access (turn it off and on).
- **Guests can't open the page:** they must be on the same Wi-Fi. Some public, office
  or mesh Wi-Fi blocks phones talking to each other; use the host's hotspot instead.

## Project layout
| File | What it does |
|---|---|
| `JukeboxQueue.kt` | The queue and "song finished → play next" logic (unit tested) |
| `TinyHttpServer.kt` | Small built-in web server for the guest page |
| `JukeboxService.kt` | Runs in the background: server, queue, notification |
| `AmazonMusic.kt` | Starts songs in and reads now-playing from Amazon Music |
| `MainActivity.kt` | Host screen: setup checklist, QR code, controls |
| `assets/index.html` | The guest page |
