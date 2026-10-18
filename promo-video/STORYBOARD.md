---
format: 1080x1920
duration: 60s
message: "One app that photographs your food, runs your body like a dashboard and nags you about chai — on your phone and Galaxy Watch."
arc: Hook → Problem → Reveal → Feature blitz (BUT WAIT) → Testimonials → CTA
audience: friends, classmates, GitHub visitors
mode: autonomous
music: none
bpm: 120
---

Satirical 3 AM TV-shop infomercial. 120 BPM grid (beat 0.5 s, bar 2 s); every cut lands on a beat.
Persistent infomercial chrome: "LIVE 3:07 AM" bug top-left, scrolling CALL NOW ticker along the bottom.
Music + SFX are synthesized locally (scripts/synth.py) and marked host audio. Built as one monolithic
composition (index.html) — one section per frame below.

## Frame 1 — Cold open

- scene: TV static snaps to a pink ground; "ARE YOU TIRED OF..." slams in word by word
- duration: 4s
- transition_in: cut
- asset_candidates: none
- status: animated
- src: index.html#f01

## Frame 2 — The problems

- scene: Three tilted complaint cards drop on the beat: guessing your thali, forgetting lunch exists, stomach leaves you on read
- duration: 5s
- transition_in: cut
- asset_candidates: none
- status: animated
- src: index.html#f02

## Frame 3 — Introducing

- scene: Phone slides up showing Today screen; starburst "AS SEEN ON YOUR OWN PHONE"
- duration: 3s
- transition_in: cut
- asset_candidates: assets/screens/01-today-top.png
- status: animated
- src: index.html#f03

## Frame 4 — Snap a photo, AI judges it

- scene: Meal page with real khichdi photo; stamps "904 KCAL" and "CHURMA: 6/10, WE SAW THAT"
- duration: 4.5s
- transition_in: cut
- asset_candidates: assets/screens/15-meal.png
- status: animated
- src: index.html#f04

## Frame 5 — Samsung Health sync

- scene: Calorie ring screen; "AUTO-SYNCS TO SAMSUNG HEALTH*" with footnote "*whenever Samsung Health wakes up"
- duration: 3.5s
- transition_in: cut
- asset_candidates: assets/screens/03-today-more.png
- status: animated
- src: index.html#f05

## Frame 6 — Your body has a battery now

- scene: Energy page; giant "63%" count-up; "YOUR BODY. NOW WITH A BATTERY %."
- duration: 4s
- transition_in: cut
- asset_candidates: assets/screens/06-energy.png
- status: animated
- src: index.html#f06

## Frame 7 — BUT WAIT

- scene: Full-yellow interstitial, "BUT WAIT — THERE'S MORE!" shakes
- duration: 1.5s
- transition_in: cut
- asset_candidates: none
- status: animated
- src: index.html#f07

## Frame 8 — Fuel & digestion timeline

- scene: Timeline screen pans to forecast; "PREDICTS YOUR 12:00 CRASH" + "KNOWS WHEN YOUR KHICHDI LEAVES: 00:52"
- duration: 5s
- transition_in: cut
- asset_candidates: assets/screens/07-timeline.png, assets/screens/08-forecast.png
- status: animated
- src: index.html#f08

## Frame 9 — Muscle map

- scene: Body map flips front→back; "LEG DAY? IT KNOWS." / "DESK DAY? IT REALLY KNOWS."
- duration: 4s
- transition_in: cut
- asset_candidates: assets/screens/09-muscles.png, assets/screens/10-muscles-back.png
- status: animated
- src: index.html#f09

## Frame 10 — Wanted: Chai

- scene: WANTED poster "CHAI — for stealing your iron"; pairing screen; "ADD LEMON. JUSTICE."
- duration: 4s
- transition_in: cut
- asset_candidates: assets/screens/16-pairing.png
- status: animated
- src: index.html#f10

## Frame 11 — Seasonal fruit + mandi prices

- scene: Fruit screen + price tag stickers "GUAVA ₹65/KG — LIVE FROM GONDAL MANDI"; "your nani's market wisdom, now an API"
- duration: 4s
- transition_in: cut
- asset_candidates: assets/screens/12-fruit.png, assets/screens/14-fruit-prices.png
- status: animated
- src: index.html#f11

## Frame 12 — Water + Galaxy Watch

- scene: CSS watch with spinning bezel; "WAKES YOU IN LIGHT SLEEP" / "RATE YOUR GROGGINESS BY SPINNING A BEZEL"; water glasses fill
- duration: 3.5s
- transition_in: cut
- asset_candidates: assets/screens/01-today-top.png
- status: animated
- src: index.html#f12

## Frame 13 — Connections check

- scene: Connections screen; checkmarks stamp in; "IT EVEN CHECKS ITS OWN HOMEWORK"
- duration: 3s
- transition_in: cut
- asset_candidates: assets/screens/17-connections.png
- status: animated
- src: index.html#f13

## Frame 14 — Testimonials

- scene: Fake testimonial cards from "Me, 3 AM" and "Also me"; disclaimer "Results not typical. Results not even measured."
- duration: 4.5s
- transition_in: cut
- asset_candidates: none
- status: animated
- src: index.html#f14

## Frame 15 — CTA

- scene: Black close frame; "MEAL TRACKER" + price tag "₹0.00" starburst "FREE*"; "*I built it. Operators are standing by (it's a watch)."
- duration: 6.5s
- transition_in: cut
- asset_candidates: assets/screens/02-today-cards.png
- status: animated
- src: index.html#f15

## Video direction

Blockframe neobrutalism dialled to TV-shop volume: pastel grounds cycle every frame (pink, blue, green,
yellow, cream), 4px borders + hard 8px shadows on every card and phone, tilted stickers and 10-point
starbursts. Motion is snappy: BackOut slams for headlines, expo-out slides for phones, stamp-scale for
stickers, one shake per "BUT WAIT". Copy is deadpan-satirical; the user is the hero, never the joke.
