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

## Images des boutons
Les 6 boutons du jeu (Victoire, Combattre, Déployer, Attaquer, Conquérir, Voir) sont intégrés à l'appli
(`app/src/main/assets/templates/`) : rien à importer. Seuil conseillé : 85 %.
Les pins déjà faits (coche verte) sont ignorés. Exemples : `exemples_images/pin_tente_bleu.png`,
`exemples_images/carte_pins_coches.png`.

## Enchaînement complet
pin (sans coche verte, si viande >= minimum) > Voir > Attaquer / Conquérir > Déployer / Combattre >
Victoire (touche sous la bannière) > boussole de la carte du monde (seulement juste après une victoire)
> retour à « Mission de renseignements » > pin suivant.
La viande est lue en haut à droite de l'écran des pins ; sous le minimum réglé (20 par défaut), l'appli se met en pause.

Tente : pin > Voir > Secourir > (on reste sur la carte du monde) > boussole > carte des pins.

Ours : après « Déployer » (troupes parties), boussole tout de suite (délai réglable) pour lancer le pin suivant pendant la marche.
Un pin déjà lancé n'est pas recliqué pendant 20 minutes (réglable) : sa coche verte n'apparaît qu'après la victoire.

Reprise : si rien ne se passe pendant 30 s (réglable) sur la carte du monde, l'appli clique la boussole pour rouvrir
la carte des pins (utile quand elle se ferme à l'actualisation). Quand aucun pin n'est cliquable, le journal indique
combien de pins sont vus, cochés ou déjà lancés.
