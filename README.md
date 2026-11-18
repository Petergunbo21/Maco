# Macro Recorder (Android)

Record taps and swipes in any app, save them as an editable text script, and replay them.
Scripts can also react to what's on screen (colors), so they can handle things like a fishing minigame.

## Install

1. Upload the contents of this project to a GitHub repo (keep the `.github` folder).
2. Open **Actions**, choose **Build APK**, and click **Run workflow**.
3. When it finishes, download the **MacroRecorder-apk** artifact and unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it. Allow "install unknown apps" when asked.

Needs Android 8.0 or newer.

## First-time setup

1. Open the app and tap **1. Enable accessibility service**. Find *Macro Recorder* and turn it on.
2. Tap **2. Start screen capture** and accept the prompt. This is only needed for color commands (`waitcolor`, `findtap`, `ifcolor`, `track`, and the Pick button).
3. Tap **3. Show floating panel**. Drag the panel with the ≡ handle. Keep it away from the spots your script checks, because screen capture can see it.

## Floating panel

- **● Rec**: record your taps and swipes. Tap again to stop and save. Recording replaces the current script, so export it first if you want to keep it.
- **▶ Play**: run the script. **■ Stop** ends it.
- **◎ Pick**: tap any spot on screen. The app copies `X Y RRGGBB` to your clipboard, so you can paste it into a script.
- **✕**: hide the panel. Bring it back from the app.

## Script commands

One command per line. Lines starting with `#` or `//` are comments. Colors are 6-digit hex (`RRGGBB`). `TOL` is how different a color can be (default 20; higher is looser).

| Command | What it does |
|---|---|
| `tap X Y` | Tap a spot |
| `hold X Y MS` | Press and hold for MS milliseconds |
| `swipe X1 Y1 X2 Y2 MS` | Drag from one spot to another |
| `wait MS` | Pause |
| `waitcolor X Y RRGGBB [TOL] [TIMEOUT_MS]` | Wait until a pixel shows that color |
| `findtap RRGGBB [TOL] [TIMEOUT_MS]` | Find that color anywhere on screen and tap it |
| `ifcolor X Y RRGGBB [TOL]` ... `end` | Run the lines inside only if the pixel matches |
| `loop N` ... `end` | Repeat the lines inside N times (0 = forever) |
| `track FISH BAR X1 Y1 X2 Y2 PRESSX PRESSY MAXMS [TOL] [invert]` | Reel minigame helper (see below) |

The repeat count and speed set in the app apply to the whole script. Speed affects `wait` only.

### Reel minigame (`track`)

`track` looks inside the box (X1 Y1 X2 Y2) for the fish color and the bar color. It taps PRESSX PRESSY in short presses whenever the bar is on the wrong side of the fish. It stops after MAXMS, or when both colors have been missing for 1.5 seconds.

- Use **Pick** to get the fish color, the bar color, and the box corners.
- If the bar moves the wrong way, add `invert` at the end.
- It's a simple controller, so you'll probably need to tune the colors and TOL.

### Example
