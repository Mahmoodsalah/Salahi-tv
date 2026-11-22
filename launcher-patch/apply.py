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
import sys
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
shutil.copyfile(here / "prayer-banner.png", addon / "res/drawable-xhdpi/banner.png")
print("Salahi TV patch applied:", lat, lon, "method", method)
