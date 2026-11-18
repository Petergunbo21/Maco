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

## Floating panel
- **● Rec**: record taps and swipes (tap again to save). Recording replaces the script, so export first if you want to keep it.
- **▶ Play**: run the script. **■ Stop** ends it.
- **◎ Pick**: tap any spot on screen and it copies `X Y RRGGBB` to your clipboard. Paste those numbers into your script.
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

The repeat count and speed in the app wrap around the whole script.

### Reel minigame (`track`)
`track` looks inside the box (X1 Y1 X2 Y2) for the fish color and the bar color, and taps PRESSX PRESSY in short presses whenever the bar is on the wrong side of the fish. It stops after MAXMS or when both colors disappear for 1.5 seconds (minigame over).
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

## Notes
- Needs Android 8.0 or newer.
- Taps use fixed screen positions, so the game must be in the same orientation each time. Start screen capture with the game's orientation if you can; it follows rotation.
- Check each game's rules about automation before using it.
