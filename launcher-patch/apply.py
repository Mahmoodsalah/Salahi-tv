#!/usr/bin/env python3
"""Adds the Salahi TV home screen to a CleanYourTV checkout: Hijri and Gregorian dates, the next prayer and the
weather beside the clock, no app name over the dock, and an Arabic prayer-times card on the Islamic background.

    python3 apply.py <cleanyourtv folder> <lat> <lon> <aladhan method> "<city name>"

The method is an Aladhan number, or custom angles such as "99&methodSettings=19.5,null,18" (Fajr, Maghrib, Isha).
Safe to run twice. Afterwards build and install the launcher and the prayer-times addon, then push a theme.
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
from xml.sax.saxutils import escape

if len(sys.argv) != 6:
    sys.exit(__doc__)
root, lat, lon, method, city = pathlib.Path(sys.argv[1]), *sys.argv[2:]
float(lat), float(lon)  # fail early on a typo
if not re.fullmatch(r"\d+(&methodSettings=[\d.]+,(null|[\d.]+),(null|[\d.]+( ?min)?))?", method):
    sys.exit(f"Bad Aladhan method: {method}")
here = pathlib.Path(__file__).resolve().parent
launcher = root / "launcher/app/src/main"
java = launcher / "java/com/gathin/tvhome"
addon = root / "addons/prayer-times/app/src/main"
ARABIC_NAME = "مواقيت الصلاة"


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def patch(path, old, new, done_marker):
    text = read(path)
    if done_marker in text:
        return
    eol = "\r\n" if "\r\n" in text else "\n"
    old, new = old.replace("\n", eol), new.replace("\n", eol)
    if old not in text:
        sys.exit(f"Could not find the spot to change in {path} - use the CleanYourTV commit named in SKILL.md.")
    write(path, text.replace(old, new, 1))


BANNER_PAGE = """<!doctype html><meta charset="utf-8"><style>html,body{margin:0;width:320px;height:180px;overflow:hidden}
.b{width:320px;height:180px;position:relative;direction:rtl;color:#fff;font-family:Cairo,"Segoe UI",Tahoma,sans-serif;
background:radial-gradient(circle at 72% 22%,#1f8a63,#0c3d2f 78%)}
.t{position:absolute;right:24px;top:38px;font-weight:700;font-size:36px;line-height:1.1;white-space:nowrap}
.s{position:absolute;right:24px;top:90px;font-weight:600;font-size:25px;line-height:1.2;white-space:nowrap}</style>
<div class="b"><div class="t">@NAME@</div><div class="s">@PLACE@</div></div>"""


def render_banner(name, place, out):
    """Draws the card with the city under its name. False when no browser or ffmpeg is there to draw it."""
    browsers = [shutil.which(b) for b in ("msedge", "google-chrome", "chromium", "chrome")] + [
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"]
    browser = next((b for b in browsers if b and pathlib.Path(b).exists()), None)
    ffmpeg = shutil.which("ffmpeg")
    if not browser or not ffmpeg:
        return False
    tmp = pathlib.Path(tempfile.mkdtemp())
    try:
        page, raw = tmp / "banner.html", tmp / "raw.png"
        page.write_text(BANNER_PAGE.replace("@NAME@", escape(name)).replace("@PLACE@", escape(place)), encoding="utf-8")
        # Headless browsers keep a minimum window height, so shoot a square and crop it to the banner.
        subprocess.run([browser, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
                        "--force-device-scale-factor=2",
                        "--window-size=320,320", f"--user-data-dir={(tmp / 'profile').as_posix()}",
                        f"--screenshot={raw.as_posix()}", page.as_uri()],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        for _ in range(10):  # the browser can return a moment before the file is written
            if raw.exists() and raw.stat().st_size:
                break
            time.sleep(1)
        subprocess.run([ffmpeg, "-nostdin", "-v", "error", "-y", "-i", str(raw), "-vf", "crop=640:360:0:0",
                        "-frames:v", "1", str(out)],
                       check=True, timeout=30)
        return True
    except (OSError, subprocess.SubprocessError):
        return False
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


noor = read(here / "Noor.java").replace("@LAT@", lat).replace("@LON@", lon).replace("@METHOD@", method)
write(java / "Noor.java", noor)

patch(launcher / "AndroidManifest.xml",
      '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
      '    <uses-permission android:name="android.permission.INTERNET" />\n'
      '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
      'android.permission.INTERNET')

patch(java / "MainActivity.java",
      "        if (shamsi) shamsiDate.setText(Shamsi.today(getResources()));",
      "        String noorGreeting = Noor.greeting();\n"
      "        if (noorGreeting != null && festiveGreeting.isEmpty()) greeting.setText(noorGreeting);\n"
      "        if (shamsi) {\n"
      "            shamsiDate.setText(Noor.line());\n"
      "            shamsiDate.setTextColor(Noor.whiteDay() ? 0xFFFFE08A : 0xE6FFFFFF);\n"
      "        }",
      "Noor.line()")

patch(java / "MainActivity.java",
      "            TextView date = findViewById(R.id.date);\n"
      "            date.setTextSize(11f);\n"
      "            lunarDate.setTextSize(11f);\n"
      "            date.setPadding(0, (int) (4 * density), 0, 0);\n",
      "            findViewById(R.id.date).setVisibility(View.GONE);\n"
      "            lunarDate.setTextSize(11f);\n"
      "            stretchDividerToDateColumn();\n",
      "stretchDividerToDateColumn();")

patch(java / "MainActivity.java",
      "    /** Centre the one-row header when the greeting and extra date lines are absent (#2265). */\n",
      "    /** Keeps the divider as tall as the date, next-prayer and weather lines beside it. */\n"
      "    private void stretchDividerToDateColumn() {\n"
      "        View divider = findViewById(R.id.clockDivider);\n"
      "        View column = (View) findViewById(R.id.date).getParent();\n"
      "        column.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->\n"
      "                divider.post(() -> {\n"
      "                    ViewGroup.LayoutParams lp = divider.getLayoutParams();\n"
      "                    int height = column.getHeight();\n"
      "                    if (height > 0 && lp.height != height) {\n"
      "                        lp.height = height;\n"
      "                        divider.setLayoutParams(lp);\n"
      "                    }\n"
      "                    divider.setTranslationY(column.getTop() - divider.getTop());\n"
      "                }));\n"
      "        divider.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->\n"
      "                divider.setTranslationY(column.getTop() - top));\n"
      "    }\n"
      "\n"
      "    /** Centre the one-row header when the greeting and extra date lines are absent (#2265). */\n",
      "private void stretchDividerToDateColumn")

patch(java / "MainActivity.java",
      "            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.\n",
      "            if (MainActivity.this.dockRow) want += (int) (AppAdapter.NEXT_ROW_HIDDEN_DP * density);\n"
      "            // No dock to push down: the grid starts at the top, with room for the first row's zoom and glass.\n",
      "NEXT_ROW_HIDDEN_DP")

patch(java / "AppAdapter.java",
      "    static final float BOTTOM_ROOM_DP = 30;\n",
      "    static final float BOTTOM_ROOM_DP = 30;\n"
      "    /** Lowers the dock so the row after the visible ones stays fully off screen until Down is pressed. */\n"
      "    static final float NEXT_ROW_HIDDEN_DP = 24;\n",
      "NEXT_ROW_HIDDEN_DP")

patch(launcher / "AndroidManifest.xml",
      '        <package android:name="com.tcl.settings" />',
      '        <package android:name="com.tcl.settings" />\n'
      '        <package android:name="com.google.android.katniss" />',
      'com.google.android.katniss')

# The Assistant has no launcher entry, so its top-bar button opens it through the system action.
patch(java / "MainActivity.java",
      "            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);\n",
      "            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);\n"
      "            if (launch == null && \"com.google.android.katniss\".equals(pkg)) {\n"
      "                launch = new Intent(Intent.ACTION_ASSIST).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);\n"
      "            }\n",
      "Intent.ACTION_ASSIST")

# The Assistant button: its own logo, first in the bar, and the left end for the remote's focus.
shutil.copyfile(here / "ic_assistant.xml", launcher / "res/drawable/ic_assistant.xml")

patch(java / "MainActivity.java",
      "                logo = info.loadIcon(pm);\n",
      "                logo = \"com.google.android.katniss\".equals(pkg)\n"
      "                        ? getDrawable(R.drawable.ic_assistant) : info.loadIcon(pm);\n",
      "R.drawable.ic_assistant")

patch(java / "MainActivity.java",
      "            bar.addView(button, bar.indexOfChild(getResources().getBoolean(R.bool.rtl_menu_bar) ? btnVpn : btnWifi));\n",
      "            if (\"com.google.android.katniss\".equals(pkg)) {\n"
      "                bar.addView(button, getResources().getBoolean(R.bool.rtl_menu_bar) ? bar.getChildCount() : 0);\n"
      "                assistantButton = button;\n"
      "            } else {\n"
      "                bar.addView(button, bar.indexOfChild(getResources().getBoolean(R.bool.rtl_menu_bar) ? btnVpn : btnWifi));\n"
      "            }\n",
      "assistantButton = button;")

patch(java / "MainActivity.java",
      "    private final List<View> topAppButtons = new ArrayList<>();\n",
      "    private final List<View> topAppButtons = new ArrayList<>();\n"
      "    private View assistantButton;\n",
      "private View assistantButton;")

patch(java / "MainActivity.java",
      "            int firstButton = btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();\n",
      "            int firstButton = assistantButton != null ? assistantButton.getId()\n"
      "                    : btnInputs.getVisibility() == View.VISIBLE ? R.id.btnInputs : firstTopButton().getId();\n",
      "assistantButton != null ? assistantButton.getId()")

patch(java / "MainActivity.java",
      "            stretchDividerToDateColumn();\n",
      "            stretchDividerToDateColumn();\n"
      "            alignButtonsWithDateLine();\n",
      "alignButtonsWithDateLine();")

patch(java / "MainActivity.java",
      "    /** Keeps the divider as tall as the date, next-prayer and weather lines beside it. */\n",
      "    /** Keeps the top buttons level with the date line, clear of the wallpaper's moon. */\n"
      "    private void alignButtonsWithDateLine() {\n"
      "        TextView dateLine = findViewById(R.id.shamsiDate);\n"
      "        View buttons = findViewById(R.id.topButtons);\n"
      "        ViewGroup header = (ViewGroup) buttons.getParent();\n"
      "        Runnable align = () -> {\n"
      "            if (dateLine.getLayout() == null) return;\n"
      "            int lineBottom = dateLine.getLayout().getLineBaseline(0) + dateLine.getLayout().getLineDescent(0);\n"
      "            Rect line = new Rect(0, dateLine.getLayout().getLineTop(0), 0, lineBottom);\n"
      "            header.offsetDescendantRectToMyCoords(dateLine, line);\n"
      "            buttons.setTranslationY(line.centerY() - buttons.getTop() - buttons.getHeight() / 2f);\n"
      "        };\n"
      "        dateLine.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());\n"
      "        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());\n"
      "    }\n"
      "\n"
      "    /** Keeps the divider as tall as the date, next-prayer and weather lines beside it. */\n",
      "private void alignButtonsWithDateLine")

# Cards draw their text live when the Cairo font is on this computer; otherwise they keep their banner images.
FONT_DIRS = (pathlib.Path.home() / "AppData/Local/Microsoft/Windows/Fonts", pathlib.Path("C:/Windows/Fonts"),
             pathlib.Path.home() / "Library/Fonts", pathlib.Path("/Library/Fonts"),
             pathlib.Path.home() / ".local/share/fonts", pathlib.Path.home() / ".fonts")
for font, asset in (("Cairo-Bold.ttf", "card_title.ttf"), ("Cairo-SemiBold.ttf", "card_subtitle.ttf")):
    found = next((d / font for d in FONT_DIRS if (d / font).exists()), None)
    if found:
        (launcher / "assets").mkdir(exist_ok=True)
        shutil.copyfile(found, launcher / "assets" / asset)
write(java / "CardArt.java", read(here / "CardArt.java"))
write(java / "HiRes.java", read(here / "HiRes.java"))

# Top-bar icons go through HiRes too.
patch(java / "MainActivity.java",
      "        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);\n",
      "        if (cfg.iconOnlyInputs) makeIconOnly((TextView) btnInputs);\n"
      "        HiRes.apply((TextView) btnInputs);\n"
      "        HiRes.apply(btnWifi);\n"
      "        HiRes.apply((TextView) btnSettings);\n",
      "HiRes.apply((TextView) btnSettings);")

patch(java / "MainActivity.java",
      "                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);\n",
      "                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);\n"
      "                HiRes.apply(btnWifi);\n",
      "                HiRes.apply(btnWifi);\n")

patch(java / "MainActivity.java",
      "            logo.setBounds(0, 0, size, size);\n",
      "            logo = new HiRes(logo);\n"
      "            logo.setBounds(0, 0, size, size);\n",
      "logo = new HiRes(logo);")

patch(java / "AppAdapter.java",
      "    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {\n",
      "    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {\n"
      "        Drawable art = CardArt.of(ctx, pm, ri.activityInfo.packageName);\n"
      "        if (art != null) return art;\n",
      "CardArt.of(")

# Top-bar icons: 27dp is 1.5x their 24-unit grid on a 720p TV, so their edges land on whole pixels.
for icon in ("ic_input", "ic_wifi", "ic_ethernet", "ic_settings"):
    patch(launcher / f"res/drawable/{icon}.xml", 'android:width="22dp" android:height="22dp"',
          'android:width="27dp" android:height="27dp"', 'android:width="27dp"')

patch(java / "MainActivity.java",
      "        int pad = Math.round(10 * density);\n        pill.setPaddingRelative(pad, 0, pad, 0);\n",
      "        int pad = Math.round(7.5f * density);\n        pill.setPaddingRelative(pad, 0, pad, 0);\n",
      "int pad = Math.round(7.5f * density);")

patch(java / "MainActivity.java",
      "        int size = Math.round(22 * density);\n        for (String pkg : packages) {\n",
      "        int size = Math.round(27 * density);\n        for (String pkg : packages) {\n",
      "int size = Math.round(27 * density);")

patch(java / "MainActivity.java",
      "            button.setCompoundDrawableTintList(null);\n",
      "            button.setCompoundDrawableTintList(null);\n"
      "            int inset = Math.round(7.5f * density);\n"
      "            button.setPaddingRelative(inset, 0, inset, 0);\n",
      "int inset = Math.round(7.5f * density);")

layout = launcher / "res/layout/activity_main.xml"
text = read(layout)
m = re.search(r'android:id="@\+id/shamsiDate".*?/>', text, re.S)
if not m:
    sys.exit(f"Could not find shamsiDate in {layout}")
block = m.group(0)
if 'android:maxLines="3"' not in block:
    eol = "\r\n" if "\r\n" in text else "\n"
    new = block.replace('android:maxLines="2"', 'android:maxLines="3"').replace(
        'android:singleLine="true"', f'android:maxLines="3"{eol}{" " * 28}android:lineSpacingExtra="4dp"')
    write(layout, text.replace(block, new))

cfg = addon / "res/values/config.xml"
text = read(cfg)
for name, value in (("latitude", lat), ("longitude", lon), ("place", escape(city)), ("method", escape(method))):
    text = re.sub(rf'(<string name="{name}"[^>]*>)[^<]*(</string>)',
                  lambda g, v=value: g.group(1) + v + g.group(2), text)
# The Kahf card plays the adhan; a second alert from this addon would interrupt whatever is on screen.
text = re.sub(r'(<bool name="reminders">)\w+(</bool>)', r"\g<1>false\g<2>", text)
write(cfg, text)

manifest = addon / "AndroidManifest.xml"
text = read(manifest).replace('android:label="Prayer Times"', f'android:label="{ARABIC_NAME}"')
text = re.sub(r'\s*<!-- Module card[^>]*>\s*<intent-filter>\s*<action[^>]*/>\s*'
              r'<category android:name="com\.gathin\.tvhome\.category\.DOCK_CARD" />\s*</intent-filter>\s*'
              r'<meta-data android:name="com\.gathin\.tvhome\.card"[^>]*/>', "", text)
write(manifest, text)

for strings in ("res/values/strings.xml", "res/values-ar/strings.xml"):
    path = addon / strings
    write(path, re.sub(r'(<string name="app_name">)[^<]*(</string>)',
                       lambda g: g.group(1) + ARABIC_NAME + g.group(2), read(path)))

patch(addon / "java/com/majnioui/tv/prayer/MainActivity.java",
      "        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,\n"
      "                new int[] {(accent & 0x00FFFFFF) | 0x55000000, 0xFF050607, 0xFF050607}));\n",
      "        root.setBackgroundResource(R.drawable.screen_bg);\n",
      "R.drawable.screen_bg")

(addon / "res/drawable-nodpi").mkdir(exist_ok=True)
shutil.copyfile(here.parent / "salahi-app/app/src/main/res/drawable-nodpi/player_bg.jpg",
                addon / "res/drawable-nodpi/screen_bg.jpg")
banner = addon / "res/drawable-xhdpi/banner.png"
if not render_banner(ARABIC_NAME, city, banner):
    shutil.copyfile(here / "prayer-banner.png", banner)
    print("Note: no browser or ffmpeg found, so the prayer card shows no city name.")
print("Salahi TV patch applied:", lat, lon, "method", method)
