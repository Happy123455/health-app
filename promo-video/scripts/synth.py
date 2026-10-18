"""Synthesizes the infomercial soundtrack (music + SFX) deterministically: assets/audio/infomercial.wav.
120 BPM, chords C-Am-F-G; music drops out for "BUT WAIT" at 24 s. Seeded noise, so every run is identical."""
import numpy as np, wave, sys

SR = 44100
DUR = 60.0
N = int(SR * DUR)
BEAT = 0.5
rng = np.random.default_rng(7)
mix = np.zeros(N)

def t_(d): return np.arange(int(SR * d)) / SR
def add(sig, at, gain=1.0):
    i = int(at * SR)
    if i >= N: return
    j = min(N, i + len(sig))
    mix[i:j] += sig[: j - i] * gain
def env(d, a=0.005, decay=8.0):
    t = t_(d); e = np.exp(-t * decay); k = int(a * SR)
    if k: e[:k] *= np.linspace(0, 1, k)
    return e
def noise(d): return rng.uniform(-1, 1, int(SR * d))
def hp(x, a=0.85):  # one-pole high-pass
    y = np.zeros_like(x); prev_x = prev_y = 0.0
    for i, v in enumerate(x): prev_y = a * (prev_y + v - prev_x); prev_x = v; y[i] = prev_y
    return y
def lp(x, a=0.15):
    y = np.zeros_like(x); p = 0.0
    for i, v in enumerate(x): p += a * (v - p); y[i] = p
    return y
def saw(f, d):
    t = t_(d); return sum(np.sin(2 * np.pi * f * k * t) / k for k in range(1, 8))
def note(m): return 440.0 * 2 ** ((m - 69) / 12)

# --- instruments
def kick():
    d = 0.32; t = t_(d); f = 50 + 110 * np.exp(-t * 25)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(d, 0.002, 12)
KICK = kick()
CLAP = hp(noise(0.18)) * env(0.18, 0.002, 22)
HAT = hp(noise(0.05), 0.95) * env(0.05, 0.001, 60)
def bass(m, d=0.22): return lp(saw(note(m), d), 0.08) * env(d, 0.005, 6)
def stab(ms, d=0.16): return sum(lp(saw(note(m), d), 0.25) for m in ms) * env(d, 0.003, 14) / len(ms)
def bell(f, d=0.9): t = t_(d); return (np.sin(2*np.pi*f*t) + 0.5*np.sin(2*np.pi*f*2.76*t) + 0.25*np.sin(2*np.pi*f*5.4*t)) * env(d, 0.002, 5)

CHORDS = [([60, 64, 67], 36), ([57, 60, 64], 33), ([53, 57, 60], 29), ([55, 59, 62], 31)]  # C Am F G (stab notes, bass root)
BASS_PAT = [0, 0, 12, 0, 7, 0, 12, 10]  # eighth-note funk offsets

def music_on(t): return 4.0 <= t < 24.0 or 25.5 <= t < 59.5

# --- music: 4 s .. 59.5 s, break for BUT WAIT
for b in range(int(DUR / BEAT)):
    t = b * BEAT
    if not music_on(t): continue
    bar = int(t // 2); chord, root = CHORDS[bar % 4]
    add(KICK, t, 0.9)
    if b % 2 == 1: add(CLAP, t, 0.45)
    add(HAT, t + 0.25, 0.25); add(HAT, t, 0.12)
    for e in range(2):
        add(bass(root + BASS_PAT[(b * 2 + e) % 8] - 12 + 12), t + e * 0.25, 0.35)
    if b % 2 == 0: add(stab(chord), t + 0.25, 0.28)
    else: add(stab(chord), t + 0.25, 0.2); add(stab(chord), t + 0.375, 0.16)
# intro bed 0..4: TV hum + rising stabs on 2..4
add(np.sin(2*np.pi*60*t_(4.0)) * 0.05, 0)
for k, t in enumerate([2.0, 2.5, 3.0, 3.5]): add(stab([60 + k, 64 + k, 67 + k]), t, 0.3)

# --- SFX
def whoosh(d=0.45):
    x = noise(d); y = np.zeros_like(x); p = 0.0
    for i, v in enumerate(x):
        a = 0.02 + 0.5 * (i / len(x)); p += a * (v - p); y[i] = p
    e = np.sin(np.pi * np.linspace(0, 1, len(x)))
    return y * e
def thud(): d = 0.25; t = t_(d); return np.sin(2*np.pi*(70 + 60*np.exp(-t*30))*t) * env(d, 0.001, 14) + lp(noise(d), 0.3) * env(d, 0.001, 30) * 0.5
def stamp():
    out = thud() * 0.8
    click = hp(noise(0.08)) * env(0.08, 0.001, 50) * 0.6
    out[: len(click)] += click
    return out
def ding(): return bell(1568, 1.0) * 0.6
def kaching():
    out = bell(2093, 0.7) + np.pad(bell(2637, 0.7), (int(0.08 * SR), 0))[: int(0.7 * SR)]
    rattle = hp(noise(0.12)) * env(0.12, 0.001, 25) * 0.6
    out[: len(rattle)] += rattle
    return out * 0.5
def tick(): return hp(noise(0.02), 0.97) * env(0.02, 0.0005, 120)
def static(d): return hp(noise(d), 0.6) * 0.35
def scratch():
    d = 0.6; t = t_(d); f = 300 + 900 * np.abs(np.sin(2*np.pi*3.2*t))
    return np.sin(2*np.pi*np.cumsum(f)/SR) * lp(noise(d), 0.4) * 2.2 * env(d, 0.01, 2)
def boom(): d = 1.4; t = t_(d); return np.sin(2*np.pi*(40 + 30*np.exp(-t*4))*t) * env(d, 0.002, 2.5)
def pop(): d = 0.08; t = t_(d); return np.sin(2*np.pi*(900 - 6000*t)*t) * env(d, 0.001, 40)

add(static(0.6), 0.0, 0.8)
for t in [0.5, 1.0, 1.5, 2.0]: add(thud(), t, 0.9)
for t in [4.5, 6.0, 7.5]: add(stamp(), t, 0.8)
for t in [9.0, 12.0, 16.5, 20.0, 25.5, 30.5, 38.5, 42.5, 46.0, 49.0]: add(whoosh(), t - 0.15, 0.7)
for t in [9.9, 18.0, 22.1, 37.5, 45.0]: add(ding(), t, 0.7)
for t in [13.5, 14.6, 27.6, 29.0, 36.0, 52.8, 56.5]: add(stamp(), t, 0.8)
for k in range(15): add(tick(), 20.5 + k * 0.1, 0.5)          # battery count-up
add(scratch(), 23.85, 0.7); add(boom(), 24.1, 1.0)
add(whoosh(0.6), 27.0, 0.5); add(whoosh(0.5), 32.4, 0.6)
add(thud(), 34.5, 1.0)
for t in [39.5, 40.6, 55.0]: add(kaching(), t, 0.8)
for k in range(10): add(tick(), 43.0 + k * 0.12, 0.6)          # bezel clicks
for t in [46.6, 47.1, 47.6, 48.1]: add(pop(), t, 0.5)
for t in [50.0, 51.5]: add(pop(), t, 0.6)
add(boom(), 53.5, 0.9)
add(sum(lp(saw(note(m), 2.0), 0.2) for m in [48, 60, 64, 67, 72]) / 5 * env(2.0, 0.01, 1.5), 58.0, 0.6)  # final chord

# master: fades + soft clip
mix[: int(0.02 * SR)] *= np.linspace(0, 1, int(0.02 * SR))
mix[-int(1.0 * SR):] *= np.linspace(1, 0, int(1.0 * SR))
mix = np.tanh(mix * 1.1) * 0.85
pcm = (np.stack([mix, mix], 1) * 32767).astype(np.int16)
with wave.open(sys.argv[1] if len(sys.argv) > 1 else "assets/audio/infomercial.wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
print("ok", DUR, "s")
