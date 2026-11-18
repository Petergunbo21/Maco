# Macro Recorder (Android)

Records taps and swipes in any app, saves them as an editable text script, and replays them.
Scripts can also react to what is on screen (colors), which is how you automate things like a fishing minigame.

## Get the APK from GitHub
1. Create a new GitHub repository and upload everything in this folder (keep the folder structure, including `.github/`).
2. Open the repo's **Actions** tab, choose **Build APK**, and run it (it also runs on every push).
3. Open the finished run and download the **MacroRecorder-apk** artifact. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "install unknown apps" when asked).

## First-time setup
1. Open the app, tap **1. Enable accessibility service**, find *Macro Recorder*, switch it on.
2. Tap **2. Start screen capture** and accept the prompt. Needed only for color commands (`waitcolor`, `findtap`, `ifcolor`, `track`, and the Pick button).
3. A floating panel appears. Drag it with the ≡ handle. Keep it away from the areas your script scans, because screen capture can see it.

## Fisch (Roblox) quick start
The Fisch rules allow macros as long as they don't go beyond human ability, but they don't recommend them. You use this at your own risk.

1. Do the first-time setup above, then open Fisch.
2. **Round 1/2**: cast, and when the **SHAKE button** is on screen tap **🐟 1/2** on the panel. The screen freezes. Tap where you press and hold to cast, then drag a box around the shake button (Skip if there isn't one).
3. **Round 2/2**: when the **reel bar** is on screen tap **🐟 2/2**. Tap the fish icon, tap your bar, then drag a box around the whole reel bar.
4. The app writes the script for you. Go to the idle screen and tap **▶ Play**. While it runs, the panel shrinks to Play/Stop so it stays out of the way of the color scans.

While it runs you get a live status line on the panel (Casting, Waiting for bite, Reeling, and a catch counter) and an overlay on the reel bar: a white outline around the reel area, a **green arrow above it where the app sees the fish** and a **red arrow below it where it sees your bar**. If the arrows aren't following the fish and bar, the colors or box need redoing (run the 🐟 setup again).

If the bar chases the fish the wrong way, open the script in the app and add `invert` at the end of the `track` line. If it reels badly, raise or lower the tolerance (30) or the cast hold time (1100).

## Floating panel
- **● Rec**: record taps and swipes (tap again to save). Recording replaces the script, so export first if you want to keep it.
- **▶ Play**: run the script. **■ Stop** ends it.
- **◎ Pick**: tap any spot on screen and it copies `X Y RRGGBB` to your clipboard. Paste those numbers into your script.
- **▭ Crop**: drag a box around a button or picture on screen. The app saves it and copies a ready-to-paste `findimg` line.
- **✕**: hide the panel (bring it back from the app).

## Script commands
One command per line. Lines starting with `#` or `//` are comments. Colors are `RRGGBB` hex. `TOL` is how different a color may be (default 20, higher = looser).

| Command | What it does |
|---|---|
| `tap X Y` | tap |
| `hold X Y MS` | press and hold for MS milliseconds |
| `swipe X1 Y1 X2 Y2 MS` | drag |
| `wait MS` | pause |
| `waitcolor X Y RRGGBB [TOL] [TIMEOUT_MS]` | wait until that pixel has that color |
| `findtap RRGGBB [TOL] [TIMEOUT_MS]` | find the color anywhere on screen and tap it |
| `ifcolor X Y RRGGBB [TOL]` ... `end` | run the lines inside only if the pixel matches |
| `loop N` ... `end` | repeat the lines inside N times (0 = forever) |
| `track FISH BAR X1 Y1 X2 Y2 PRESSX PRESSY MAXMS [TOL] [invert]` | reel minigame helper (below) |
| `findimg NAME [TIMEOUT_MS] [TOL]` | find a saved picture (made with Crop) on screen and tap its center |
| `waitimg NAME [TIMEOUT_MS] [TOL]` | wait until a saved picture is on screen (no tap) |
| `until RRGGBB X1 Y1 X2 Y2 [TOL] [TIMEOUT_MS]` ... `end` | repeat the lines inside until the color appears inside the box |

The repeat count and speed in the app wrap around the whole script.

### Reel minigame (`track`)
`track` looks inside the box (X1 Y1 X2 Y2) for the fish color and the bar color, works out how fast each is moving, and presses PRESSX PRESSY for longer the further the bar is from the fish (looking a little ahead so it doesn't overshoot). It stops after MAXMS or when both colors disappear for 1.5 seconds (minigame over).
- Use **Pick** on the fish and on the bar to get their colors, and to find the box corners.
- If the bar moves the wrong way, add `invert` at the end.
- It is a simple controller, so expect to tune the colors and TOL.

### Example
```
# cast
hold 1200 600 1000
# wait for the shake button, tap it
findtap FFFFFF 20 8000
# reel
track FFFFFF 3A6EA5 400 900 1500 960 1200 600 15000 25
wait 1000
```
(Coordinates and colors above are placeholders; use Pick to get yours.)

## Save and share scripts
Use **Export script file** and **Import script file** in the app to keep scripts as `.txt` files, like TinyTask recordings.

## Updating from version 1.0
Version 1.1 is signed with a fixed key, so later updates install over it and keep your scripts. Your 1.0 build used a different key, so uninstall it once first (export your script first if you want to keep it).

## Notes
- Needs Android 8.0 or newer.
- Taps use fixed screen positions, so the game must be in the same orientation each time. Start screen capture with the game's orientation if you can; it follows rotation.
- Check each game's rules about automation before using it.
