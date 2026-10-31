#!/usr/bin/env python3
"""
Visual Clicker version PC : lit l'écran du téléphone via ADB, reconnaît les images
et les pins (par couleur + silhouette) puis envoie des appuis avec `adb shell input tap`.

Prérequis : Python 3, `pip install opencv-python numpy`, ADB (platform-tools),
téléphone branché en USB avec le « débogage USB » activé.

Utilisation :
    python pc_clicker.py                 # boucle de clic (Ctrl+C pour arrêter)
    python pc_clicker.py --image cap.png # test à blanc sur une capture : affiche ce qu'il cliquerait
"""
import argparse
import glob
import os
import subprocess
import sys
import time

import cv2
import numpy as np

SCALE = 0.5  # on travaille en demi-résolution pour la vitesse
HERE = os.path.dirname(os.path.abspath(__file__))

# Plages HSV (OpenCV : H 0-179) des pins : bleu, mauve, or
PIN_RANGES = [
    ((95, 100, 100), (112, 255, 255)),
    ((118, 60, 90), (140, 255, 255)),
    ((8, 80, 140), (28, 255, 255)),
]
# Titre de l'écran des pins -> largeur d'écran sur laquelle l'image a été prise
GATES = {"titre_mission_fr.png": 1080.0, "titre_mission_en.png": 540.0}


def to_gray_small(img):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return cv2.resize(g, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA)


def load_templates(folder):
    out = []
    for f in sorted(glob.glob(os.path.join(folder, "*.png"))):
        img = cv2.imread(f)
        if img is not None:
            out.append((os.path.basename(f), to_gray_small(img)))
    return out


def load_gates(folder, screen_w):
    out = []
    for name, ref_w in GATES.items():
        img = cv2.imread(os.path.join(folder, name))
        if img is None:
            continue
        g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        f = SCALE * screen_w / ref_w
        out.append(cv2.resize(g, None, fx=f, fy=f, interpolation=cv2.INTER_AREA))
    return out


def best_match(screen, tpl):
    if tpl.shape[0] > screen.shape[0] or tpl.shape[1] > screen.shape[1]:
        return 0.0, (0, 0)
    res = cv2.matchTemplate(screen, tpl, cv2.TM_CCOEFF_NORMED)
    _, mx, _, loc = cv2.minMaxLoc(res)
    return (mx if np.isfinite(mx) else 0.0), loc


def find_pin(small_bgr):
    """Pin le plus haut : blobs bleu/mauve/or, taille et proportions d'un pin.
    Le pin hexagonal (tête de roi) est plus large et plus gros : écarté."""
    hsv = cv2.cvtColor(small_bgr, cv2.COLOR_BGR2HSV)
    sw = small_bgr.shape[1]
    best = None
    # pastilles « coche verte » = pin déjà fait : à éviter
    cm = cv2.inRange(hsv, (44, 110, 160), (58, 255, 255))
    nc, _, cst, ccen = cv2.connectedComponentsWithStats(cm)
    checks = [(ccen[j][0], ccen[j][1], cst[j][4]) for j in range(1, nc)]
    for lo, hi in PIN_RANGES:
        m = cv2.inRange(hsv, lo, hi)
        m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
        n, _, st, _ = cv2.connectedComponentsWithStats(m)
        for i in range(1, n):
            x, y, w, h, a = [int(v) for v in st[i]]
            if w < 0.085 * sw or w > 0.125 * sw or h < 0.11 * sw or h > 0.185 * sw:
                continue
            if h / w < 1.1 or h / w > 1.8 or a > 0.0105 * sw * sw:
                continue
            if any(abs(kx - (x + 0.81 * w)) < 0.35 * w and abs(ky - (y + 0.23 * w)) < 0.35 * w
                   and ka >= 0.004 * w * w for kx, ky, ka in checks):
                continue
            cx, cy = x + w / 2, y + 0.4 * h
            if best is None or cy < best[1]:
                best = (cx, cy)
    if best is None:
        return None
    return int(best[0] / SCALE), int(best[1] / SCALE)


def detect(img, templates, gates, threshold):
    """Renvoie (description, x, y) du clic à faire, ou None."""
    gray = to_gray_small(img)
    for name, tpl in templates:
        score, loc = best_match(gray, tpl)
        if score >= threshold:
            x = int((loc[0] + tpl.shape[1] / 2) / SCALE)
            y = int((loc[1] + tpl.shape[0] / 2) / SCALE)
            return f"{name} ({score:.2f})", x, y
    if any(best_match(gray, g)[0] >= 0.85 for g in gates):
        small = cv2.resize(img, None, fx=SCALE, fy=SCALE, interpolation=cv2.INTER_AREA)
        pin = find_pin(small)
        if pin:
            return "pin", pin[0], pin[1]
    return None


def adb_cmd(adb, serial):
    return [adb] + (["-s", serial] if serial else [])


def screencap(adb, serial):
    data = subprocess.run(adb_cmd(adb, serial) + ["exec-out", "screencap", "-p"],
                          capture_output=True, check=True).stdout
    return cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)


def tap(adb, serial, x, y):
    subprocess.run(adb_cmd(adb, serial) + ["shell", "input", "tap", str(x), str(y)], check=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--templates", default=os.path.join(HERE, "templates"))
    ap.add_argument("--gates", default=os.path.join(HERE, "gates"))
    ap.add_argument("--threshold", type=float, default=0.85)
    ap.add_argument("--interval", type=float, default=0.5, help="secondes entre deux analyses")
    ap.add_argument("--cooldown", type=float, default=1.0, help="secondes entre deux clics")
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--serial", default=None, help="numéro de série si plusieurs appareils")
    ap.add_argument("--image", default=None, help="test à blanc sur une capture (aucun clic)")
    a = ap.parse_args()

    templates = load_templates(a.templates)
    print(f"{len(templates)} image(s) chargée(s) : {[n for n, _ in templates]}")

    if a.image:
        img = cv2.imread(a.image)
        r = detect(img, templates, load_gates(a.gates, img.shape[1]), a.threshold)
        print("Cliquerait :", r if r else "rien")
        return

    last_click = 0.0
    gates = None
    print("En marche. Ctrl+C pour arrêter.")
    try:
        while True:
            img = screencap(a.adb, a.serial)
            if img is None:
                print("Capture impossible (téléphone verrouillé ou non autorisé ?)")
                time.sleep(2)
                continue
            if gates is None:
                gates = load_gates(a.gates, img.shape[1])
            if time.time() - last_click >= a.cooldown:
                r = detect(img, templates, gates, a.threshold)
                if r:
                    desc, x, y = r
                    tap(a.adb, a.serial, x, y)
                    last_click = time.time()
                    print(time.strftime("%H:%M:%S"), "clic", desc, (x, y))
            time.sleep(a.interval)
    except KeyboardInterrupt:
        print("Arrêté.")


if __name__ == "__main__":
    sys.exit(main())
