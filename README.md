# Visual Clicker

Application Android qui analyse l'écran, reconnaît les images que vous lui donnez
(template matching OpenCV) et clique à leur place via le service d'accessibilité.

## Compiler l'APK (sans Android Studio)
1. Créez un dépôt GitHub et poussez ce dossier.
2. Onglet **Actions** > *Build APK* > exécutez > téléchargez l'artefact `VisualClicker-apk` (app-debug.apk).
3. Installez l'APK (autoriser les sources inconnues).
(Ou `gradle assembleDebug` avec Android Studio / SDK installé.)

## Utilisation
1. Activer le service d'accessibilité « Visual Clicker ».
2. Autoriser l'affichage par-dessus les autres apps.
3. Ajouter des images à détecter : faites une capture d'écran, recadrez serré sur l'élément
   (dans la galerie), puis importez-la. Des exemples sont dans `exemples_images/`.
4. Régler le seuil (80 % par défaut), Démarrer, puis ouvrir le jeu et toucher le bouton flottant ▶.

Plusieurs images : elles sont testées dans l'ordre d'ajout, la première trouvée est cliquée.
Pour une séquence (pin > bouton « Explorer » > ...), ajoutez les images dans cet ordre.

## Limites
- Les formes identiques de couleurs différentes se ressemblent (seuil ≥ 95 % pour les distinguer).
- L'orientation de l'écran est gérée, mais l'échelle de l'image doit rester celle de l'écran.

## Pins : détection par couleur + silhouette (rien à importer)
Sur l'écran des pins (« Mission de renseignements » en français, « Intel Mission » en anglais,
reconnu grâce à son titre intégré), l'appli repère les pins bleu / mauve / or (ours, épées, tente),
quel que soit le dessin, et clique sur le plus haut. Le pin hexagonal (tête de roi) est ignoré :
plus large et plus gros. Test sur 4 captures : tous les pins trouvés (tente et épées mauves incluses),
hexagone jamais retenu.

## Images à importer, dans cet ordre (dossier `a_importer/`)
01 Victoire · 02 Combattre · 03 Déployer · 04 Attaquer · 05 Conquérir · 06 Voir
Seuil conseillé : 85 %. Les titres intégrés sont mis à l'échelle de l'écran. Les 6 boutons existent seulement en français : en anglais il faut refaire ces 6 recadrages.
