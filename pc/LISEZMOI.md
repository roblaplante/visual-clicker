# Version PC (via ADB)

1. Sur le téléphone : Paramètres > À propos > appuyer 7 fois sur « Numéro de build », puis
   Options pour les développeurs > activer « Débogage USB ». Branchez le téléphone, acceptez la clé.
2. Sur le PC : installez Python 3, puis `pip install opencv-python numpy`, et Android platform-tools (adb).
3. Vérifiez `adb devices` (l'appareil doit apparaître en « device »).
4. Lancez le jeu sur l'écran des pins, puis : `python pc_clicker.py`
   (Ctrl+C pour arrêter). Test à blanc : `python pc_clicker.py --image capture.png`

Options : `--threshold 0.85` `--interval 0.5` `--cooldown 1.0` `--serial NUMERO` `--adb chemin/adb`.
Les images à reconnaître sont dans `templates/` (les 6 boutons), les titres d'écran dans `gates/`.
Écran du téléphone allumé et déverrouillé pendant l'exécution.
