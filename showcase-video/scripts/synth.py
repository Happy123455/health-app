"""Showcase soundtrack, synthesized deterministically (seeded noise): assets/audio/showcase.m4a via ffmpeg.
Cinematic pad (Am9-Fmaj7-Cmaj7-G6), soft 100 BPM pulse, a chiptune section for the pixel scene, and per-style SFX
cued to the scene schedule in scripts/build.py."""
import numpy as np, wave, sys, subprocess

SR = 44100; DUR = 58.0; N = int(SR * DUR)
rng = np.random.default_rng(11)
L = np.zeros(N); R = np.zeros(N)
SC = dict(s01=0.0, s02=5.6, s03=12.2, s04=18.8, s05=25.4, s06=32.0, s07=38.6, s08=45.2, s09=51.2)
PIX0, PIX1 = SC["s07"] + 1.45, SC["s08"]

def t_(d): return np.arange(int(SR * d)) / SR
def add(sig, at, gain=1.0, pan=0.0):
    i = int(at * SR)
    if i >= N or i + len(sig) <= 0: return
    j = min(N, i + len(sig)); s = sig[: j - i] * gain
    L[i:j] += s * (1 - max(0, pan)); R[i:j] += s * (1 + min(0, pan))
def env(d, a=0.01, decay=6.0):
    e = np.exp(-t_(d) * decay); k = max(1, int(a * SR)); e[:k] *= np.linspace(0, 1, k); return e
def adsr(d, a, r):
    e = np.ones(int(SR * d)); ka, kr = int(a * SR), int(r * SR)
    e[:ka] = np.linspace(0, 1, ka); e[-kr:] *= np.linspace(1, 0, kr); return e
def noise(d): return rng.uniform(-1, 1, int(SR * d))
def onepole(x, a):  # vectorised-ish one-pole low-pass via cumulative filter
    y = np.empty_like(x); p = 0.0
    for i in range(len(x)): p += a * (x[i] - p); y[i] = p
    return y
def note(m): return 440.0 * 2 ** ((m - 69) / 12)
def sine(f, d): return np.sin(2 * np.pi * f * t_(d))
def soft_saw(f, d, k=6): t = t_(d); return sum(np.sin(2 * np.pi * f * n * t) / n ** 1.4 for n in range(1, k))
def square(f, d, duty=0.5): t = t_(d); return np.where((t * f) % 1 < duty, 0.5, -0.5)
def tri(f, d): t = t_(d); return 2 * np.abs(2 * ((t * f) % 1) - 1) - 1

# ---- pad: 4 s per chord, slow attack, detuned pair for width
CH = [[57, 64, 67, 71, 74], [53, 60, 64, 67, 69], [48, 55, 59, 64, 67], [55, 59, 62, 64, 67]]
for k in range(int(DUR // 4) + 1):
    at = k * 4.0; ch = CH[k % 4]
    gain = 0.05 if PIX0 <= at < PIX1 else 0.11
    for m in ch:
        sig = (soft_saw(note(m) * 1.003, 4.6) + soft_saw(note(m) * 0.997, 4.6)) * adsr(4.6, 1.2, 1.4)
        add(sig, at, gain / len(ch) * 2.2, pan=(0.3 if m % 2 else -0.3))
    add(sine(note(ch[0] - 24), 4.4) * adsr(4.4, 0.4, 1.0), at, 0.16 if not PIX0 <= at < PIX1 else 0.04)

# ---- pulse: soft kick + shaker from s02 (not in the pixel section)
def kick():
    d = 0.35; t = t_(d); f = 45 + 70 * np.exp(-t * 22)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(d, 0.002, 9)
KICK = kick(); SHAKE = noise(0.05) * env(0.05, 0.002, 70)
b = SC["s02"]
while b < 54.0:
    if not PIX0 - 0.1 <= b < PIX1:
        add(KICK, b, 0.32)
        add(SHAKE, b + 0.3, 0.06, 0.4); add(SHAKE, b + 0.15, 0.03, -0.4); add(SHAKE, b + 0.45, 0.03, -0.4)
    b += 0.6

# ---- chiptune for the pixel market (square lead, triangle bass, noise hats), 150 BPM
beat = 0.4; t0 = PIX0
LEAD = [76, 79, 83, 79, 81, 84, 88, 84, 79, 83, 86, 83, 81, 79, 76, 79]
BASS = [45, 45, 41, 41, 48, 48, 43, 43]
i = 0
while t0 + i * beat / 2 < PIX1 - 0.2:
    at = t0 + i * beat / 2
    add(square(note(LEAD[i % 16]), 0.17, 0.25) * env(0.17, 0.003, 9), at, 0.07, 0.15)
    if i % 2 == 0: add(tri(note(BASS[(i // 2) % 8]), 0.36) * env(0.36, 0.004, 4), at, 0.16)
    if i % 2 == 1: add(noise(0.03) * env(0.03, 0.001, 90), at, 0.05)
    i += 1

# ---- SFX palette
def whoosh(d=0.5, bright=0.35):
    x = noise(d); a = np.linspace(0.02, bright, len(x)); y = np.empty_like(x); p = 0.0
    for k in range(len(x)): p += a[k] * (x[k] - p); y[k] = p
    return y * np.sin(np.pi * np.linspace(0, 1, len(x))) ** 1.5
def bell(f, d=1.2, dec=4): t = t_(d); return (np.sin(2*np.pi*f*t) + 0.4*np.sin(2*np.pi*f*2.76*t) + 0.15*np.sin(2*np.pi*f*5.4*t)) * env(d, 0.002, dec)
def pluck(f, d=0.25): return sine(f, d) * env(d, 0.001, 18) + sine(f * 2, d) * env(d, 0.001, 30) * 0.3
def pop(f=600, d=0.09): t = t_(d); return np.sin(2*np.pi*(f + 900*np.exp(-t*60))*t) * env(d, 0.001, 35)
def gloop(f=220, d=0.32): t = t_(d); return np.sin(2*np.pi*np.cumsum(f * (1 + 1.8*np.exp(-t*14)))/SR) * env(d, 0.004, 9)
def thud(): d = 0.3; t = t_(d); return np.sin(2*np.pi*(55 + 70*np.exp(-t*25))*t) * env(d, 0.001, 12)
def tick(): return noise(0.012) * env(0.012, 0.0005, 300)
def blip(f, d=0.06): return square(f, d, 0.5) * env(d, 0.001, 30)
def pen(d): x = noise(d); return (x - onepole(x, 0.3)) * 0.5 * (0.6 + 0.4 * np.sin(np.linspace(0, 9, len(x))) ** 2)
def riser(d): t = t_(d); return np.sin(2*np.pi*np.cumsum(200 + 900*(t/d)**2)/SR) * (t/d)**2 * 0.5 + whoosh(d, 0.5) * (t/d)

s = SC["s01"]; add(riser(1.2), s, 0.25); add(bell(1318, 1.6), s + 0.55, 0.22)
for k, dt in enumerate([2.0, 2.2, 2.4]): add(whoosh(0.6), s + dt, 0.5, [-0.5, 0.5, -0.3][k])
add(bell(1975, 1.4, 5), s + 2.8, 0.08)
s = SC["s02"]; add(whoosh(0.5), s + 0.45, 0.45)
add(pen(0.8), s + 0.9, 0.12); add(pen(0.9), s + 1.55, 0.1); add(pen(1.0), s + 2.35, 0.1)
add(bell(880, 2.0, 2.5), s + 3.05, 0.22); add(bell(1318, 1.6, 3), s + 3.1, 0.1)
for k in range(4): add(pluck(note(76 + [0, 3, 7, 12][k])), s + 4.15 + k * 0.16, 0.18)
s = SC["s03"]; add(whoosh(0.5), s + 0.5, 0.45)
for k, dt in enumerate([1.9, 2.6, 3.3]):
    add(gloop(260 + k * 40), s + dt + 0.55, 0.35); add(gloop(420 + k * 40, 0.2), s + dt + 0.62, 0.15)
add(whoosh(1.6, 0.12), s + 2.3, 0.18)
s = SC["s04"]; add(whoosh(0.5), s + 0.45, 0.45); add(pop(500), s + 1.7, 0.3)
add(whoosh(0.7, 0.25), s + 2.3, 0.35, -0.3); add(whoosh(0.75, 0.25), s + 3.0, 0.35, 0.3); add(whoosh(1.0, 0.18), s + 3.7, 0.3)
for k, dt in enumerate([4.5, 4.75, 5.0]): add(pluck(note(81 + k * 2)), s + dt, 0.15)
s = SC["s05"]; add(whoosh(0.5), s + 0.45, 0.45); add(whoosh(0.9, 0.2), s + 0.9, 0.3)
for k in range(6): add(pop(380 + k * 70), s + 2.0 + k * 0.16, 0.3, -0.5 + k * 0.2)
add(bell(1568, 0.8, 6), s + 3.6, 0.15)
s = SC["s06"]; add(whoosh(0.4), s + 0.4, 0.4); add(pop(900), s + 0.95, 0.3)
for dt in [1.5, 3.15, 5.0, 5.85]: add(whoosh(0.6, 0.3), s + dt, 0.35)
for dt in [1.85, 3.5]: add(pop(700), s + dt, 0.35)
add(thud(), s + 2.75, 0.6); add(tick(), s + 2.25, 0.4); add(tick(), s + 3.8, 0.4)
for k in range(3): add(pop(300 + k * 120, 0.12), s + 4.0 + k * 0.12, 0.2)
for dt in [4.35, 4.65]: add(pluck(note(84)), s + dt, 0.15)
s = SC["s07"]; add(whoosh(0.4), s + 0.4, 0.35)
for k, dt in enumerate([1.0, 1.15, 1.3]): add(blip(220 * (k + 1), 0.08), s + dt, 0.12)
add(blip(110, 0.25), s + 1.7, 0.1)
for k in range(5): add(blip(523 + k * 100), s + 1.95 + k * 0.13, 0.08)
add(blip(988, 0.05), s + 2.6, 0.06); add(blip(1318, 0.12), s + 2.68, 0.06)
for k in range(20): add(blip(880 + (k % 3) * 110, 0.025), s + 3.35 + k * 0.08, 0.05)
s = SC["s08"]; add(bell(1760, 2.0, 2.5), s + 0.3, 0.08)
for k in range(11): add(tick(), s + 1.0 + k * 0.127, 0.5)
add(bell(1046, 1.0, 4), s + 3.3, 0.12)
for k in range(3): add(pluck(note(79 + k * 4)), s + 3.3 + k * 0.18, 0.12)
s = SC["s09"]
for k in range(6): add(pop(500 + k * 60), s + 0.4 + k * 0.1, 0.2)
add(riser(1.0), s + 1.95, 0.3)
for k in range(6): add(whoosh(0.5, 0.4), s + 2.1 + k * 0.12, 0.15, -0.6 + k * 0.24)
add(thud(), s + 2.95, 0.6); add(bell(659, 3.0, 1.2), s + 2.95, 0.2)
for m in [45, 57, 64, 67, 71, 76]: add(soft_saw(note(m), 3.2) * adsr(3.2, 0.05, 2.0), s + 3.0, 0.035)

mix = np.stack([L, R], 1)
mix[-int(1.2 * SR):] *= np.linspace(1, 0, int(1.2 * SR))[:, None]
mix = np.tanh(mix * 1.4) * 0.8
pcm = (mix * 32767).astype(np.int16)
wav = "assets/audio/showcase.wav"
with wave.open(wav, "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-af", "loudnorm=I=-15:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "192k", "assets/audio/showcase.m4a"], check=True)
print("ok")
