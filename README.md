# Meal Tracker

**Showcase:** [docs/showcase.mp4](docs/showcase.mp4), a 58-second dark 3D app showcase. Each feature lifts out of the real app screen into its own motion style (line art, liquid, shape morph, isometric, sticker explainer, pixel art, glass). Made with HyperFrames; source in [showcase-video/](showcase-video/).

**Promo:** [docs/promo.mp4](docs/promo.mp4), a 60-second 3 AM infomercial about the app (made with HyperFrames; source in [promo-video/](promo-video/), soundtrack synthesized by `promo-video/scripts/synth.py`).

A personal Android app for the Galaxy S23 Ultra. Take a photo of a meal and the rest is automatic:

1. A new photo lands in the **Meals** album, via the "Log meal" icon, the Gallery or the share sheet.
2. The app notices it within about a minute, resizes it and sends it to **Gemini 3.8 Flash** with a short summary of your day.
3. Gemini returns calories, macros, 18 vitamins and minerals, a health score, "make it better" ideas and a next-meal suggestion.
4. Everything is saved on the phone (with Gemini's full answer, the photo and its location) and shown on the dashboard.
5. Watch data (workouts, steps, heart rate, sleep) comes from Samsung Health through Health Connect, and
   each analysed meal goes back the same way into Samsung Health's food log (Settings switch, on by default).

## Screens

- **Today**: totals vs targets, what to eat next, watch data, today's meals, and the "Log meal" button.
- **History**: every day with meals, with photos.
- **Insights**: 7-day charts, nutrient gaps, and Gemini's patterns plus a 7-day plan from the last 14 days.
- **Settings**: album name, profile (optional), Health Connect, and saving meals to Samsung Health.

## Smart alarm (Galaxy Watch)

Set a wake window in the phone's Settings (e.g. 06:30–07:00, weekdays). It's sent to the watch, which:

1. Starts sensing 20 minutes before the window (movement plus heart rate, scored every 30 seconds).
2. Rings as soon as it looks like light sleep inside the window, or at the window's end no matter what.
3. After you tap Stop, asks how groggy you feel. Turn the bezel to set 0–100 %.

The night goes back to the phone. When Samsung's sleep stages for that night reach Health Connect (after
you wake), the phone labels every 30-second slice, retrains a small logistic-regression model on all
labelled nights, and sends it to the watch. Your grogginess scores decide which stages count as a good
time to wake: light sleep at first, plus REM if waking from REM turns out to leave you clearly less groggy.
Until 2 nights are labelled, the watch uses simple starter rules.

The shared logic lives in `sleepcore/` and is unit-tested (`./gradlew :sleepcore:test`).

Install the watch app over Wi-Fi debugging (Galaxy watches have no Bluetooth debugging):

```bash
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## Energy battery

`sleepcore/Energy.kt` simulates fuel stores over the last 48 hours in 10-minute steps: liver glycogen
("quick fuel", ~100 g, drains ~4.5 g/h when fasting) and muscle glycogen (~6 g/kg, drained by workouts by
heart-rate intensity, refilled by absorbed carbs). Protein and water are scored against today's
pro-rated targets; vitamins and minerals over 3 days (water-soluble) or 14 days (stored). The headline
energy blends fuel with sleep and resting-heart-rate recovery. After 5 check-ins ("How energetic do you
feel?", from the phone or the watch bezel), a ridge regression pulled towards the default weights
personalises it. The model knows how many meals should be logged by now and says so when the log is
short; "Ate without a photo?" adds an estimate based on your average meal.

## Muscles

Today shows a small body map; tap it (or "Muscle recovery & body map" on the Energy page) for the full screen.

- Workout names map to muscles (routines are named by muscle group; walks, runs and rides are recognised). How hard a session was comes from its length and heart rate.
- Recovery takes about 18 h for light work and up to ~60 h for hard work. It's slower with low protein or short sleep, and a little faster after the massage chair. This is an estimate: nothing on the watch measures muscle damage.
- On the body map, colours go red (just trained), amber, then green (ready). Recovering muscles pulse, and dots carry protein, carbs and vitamin C from the plate to them. Flip between front and back; tap a muscle for its details.
- The repair kit shows today's protein, carbs, vitamin C, zinc, magnesium and vitamin D. The "desk day vs training" chart compares each muscle's use on a study day with this week's training and suggests what to train.
- Logged meals appear as photo pins on the Energy page's 24-hour fuel chart; tap a pin for what it was.

## Digestion and pairing

- **Today › Digestion:** each meal from the last few hours gets a bar. The solid part is time in the stomach, the light part is absorption, and a line marks now. Above the bars are what-to-do-now tips: a 10-minute walk after eating, chai after an iron-rich meal, when to save a hard workout for, finishing dinner about 3 h before your usual bedtime (taken from your Samsung Health sleep), and a warning if you'll still be digesting at bedtime.
- **Meal page › Digestion & pairing:** when the meal leaves your stomach and is absorbed, plus pairing notes: chai/coffee near an iron-rich meal (cuts iron absorption), lemon/amla/tomato with iron, large dairy with iron, fat for vitamins A and K, dal + grain for complete protein, and vegetables or dal before carb-heavy plates.
- **Reminder:** about 15 minutes after a meal photo is analysed, one notification (it also appears on the watch) says to walk now and, for iron-rich meals, when to have chai. Turn it off under Settings › Notifications.
- Stomach time is estimated from carbs, protein, fat and fibre (fat slows it most): roughly 40 min for chai and 3–4 h for a heavy thali. These are typical figures, not measurements.

## Seasonal fruit

- **Today › Fruit for today** and the **Seasonal fruit** screen. Picks come from what's in season in Gujarat right now. They're ranked by what you've been short on this week, whether your last meal was iron-rich but low in vitamin C, whether you've just trained or are about to (carbs and potassium), hot weather (melons), and price. Tap a pick for what one serving gives as a share of your daily targets.
- **Season calendar** for 17 fruits: light means in season, strong means peak. This month is outlined.
- **Mandi prices** come from Agmarknet's daily price feed on data.gov.in. The app checks Rajkot district markets first (Gondal, Rajkot, …) and looks across Gujarat for in-season fruit they didn't report. Prices are wholesale (₹ per quintal ÷ 100 = ₹/kg) and are fetched at most every 6 h. Each fetch is saved, so trend lines appear after a few days.
- The public data.gov.in sample key is used by default (10 rows per request). To use your own free key, add `DATA_GOV_KEY=...` to `.env`.
- Meal analysis prompts now list the fruit in season, and the "Add lemon for iron" tip names an in-season vitamin C fruit.

## Fuel & digestion timeline

The Energy page chart is one 48-hour timeline. The last 24 h (solid) joins the next 24 h (dotted) at **Now**. Swipe through it, or jump with **Last 24 h / Now / Next 24 h**.

- **Lines:** quick (liver) fuel, muscle fuel, and **stomach**. The stomach is a shaded area showing how full it is while each meal digests: it rises when you eat and empties over about 1–5 h, depending on fat, fibre and protein.
- **Forecast:** assumes your usual meal times from the last 2 weeks (dashed 🍽 pins) and last week's workouts (shaded; back-to-back sessions merged).
- **Tappable dots:** meals eaten, stomach empty, lowest and highest quick fuel, usual meals, workouts, and now. Tap any other spot to see quick fuel, muscle fuel and stomach fullness at that moment.

## Water

Samsung Health doesn't share its water log with Health Connect (it doesn't even request hydration access), so water is logged in Meal Tracker itself. Use **+ Glass** / **+ 500 ml** on Today or the Energy page, or **Drink water** on the watch (one tap = 250 ml). It feeds the energy battery's water gauge. Water other apps write to Health Connect is counted too.

## Connections check

Settings › **Connections** runs live checks:
- **Health Connect:** permissions, plus steps, heart rate, sleep, workouts, water and weight, each with its latest reading and source app. It also shows whether meals are being written for Samsung Health.
- **Watch:** the link and the last data sent each way.
- **App:** Gemini, photo access, notifications, battery setting, the background job and mandi prices.

Results are also logged under the `Connections` tag.

## Motion

All animation uses six curves defined in `ui/Motion.kt`, each with one job: linear (endless shimmer and
pulses), ease-in-out (state changes, expanding cards, tab cross-fades), expo-out (arrivals: screens, card
cascades, count-ups, rings and bars), back-out (touch feedback, toggles), elastic (score rings, tab icons,
the watch's saved tick) and bounce (chart bars, history thumbnails). The meal photo is a shared element
between the list and the meal screen. Keyed counters remember their last value, so they only animate when a
number changes. A screen recording is in `docs/motion-demo.mp4`.

## Gemini limits

The free tier allows 20 requests per model per day, shared by every key in the AI Studio project. When
3.8 Flash is busy or out of quota, the app falls back to 3.7 Flash and then 3.5 Flash, and retries later
(after the daily reset if every model is out). On the free tier Google may use the photos and answers to
improve its products; turning on billing stops that and costs roughly half a US cent per meal.

## Build and install

The API key is read from `.env` (`GEMINI_API_KEY=...`, git-ignored) and compiled into the app, so don't
share the APK.

```bash
cd meal-tracker
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Code map

| File | What it does |
|---|---|
| `Importer.kt` | Finds new album photos, keeps GPS from EXIF, saves resized copies; "Log meal" capture |
| `Gemini.kt` | REST client with model fallback, prompts and JSON schemas |
| `Analyzer.kt` | Analysis queue, per-meal context, 14-day insights |
| `Health.kt` | Health Connect reads (and optional nutrition writes) |
| `Work.kt` | Background jobs: new-photo trigger, scans, retries, 15-minute safety net |
| `Db.kt` | SQLite: meals, daily watch data, insights |
| `ui/` | Compose screens |
