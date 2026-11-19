# LecteurMusique

Un mini lecteur audio Android pour retrouver et écouter facilement les morceaux stockés sur son appareil.

## L'idée

Ce projet est du **vibes coding** : une petite application développée de manière exploratoire et itérative, en privilégiant une expérience simple, directe et agréable plutôt qu'un lecteur audio complexe. L'objectif est de rendre la lecture de musique plus simple et plus agréable sur les appareils Android.

Le projet est volontairement modeste et sert aussi de terrain d'expérimentation. Il peut évoluer au fil des essais et des retours ; il ne prétend pas remplacer une application musicale complète.

## Fonctionnalités actuelles

- Affiche les fichiers audio locaux détectés dans le dossier `Music` de l'appareil.
- Trie les morceaux par titre et affiche le nom de l'artiste lorsqu'il est disponible.
- Permet de sélectionner un morceau dans la liste.
- Fournit les commandes lecture/pause, morceau précédent et morceau suivant.
- Permet de déplacer la tête de lecture avec une barre de progression et affiche le temps écoulé et la durée.
- Propose les modes lecture aléatoire, répétition de la file et répétition du morceau.
- Continue la lecture en arrière-plan grâce à un service de session multimédia.
- Adapte sa palette au thème clair ou sombre du système.

## Prérequis

- Android Studio avec le SDK Android **API 35** installé.
- JDK **17**.
- Un appareil ou un émulateur Android **8.0 (API 26) ou ultérieur**.
- Des fichiers audio locaux dans le dossier `Music` de l'appareil.

## Ouvrir et lancer l'application

Le projet Gradle Android se trouve dans le dossier `LecteurMusique` :

1. Ouvrir ce dossier dans Android Studio.
2. Laisser Android Studio synchroniser le projet et installer les composants du SDK manquants si nécessaire.
3. Connecter un appareil Android ou démarrer un émulateur.
4. Lancer la configuration `app`.
5. À la première ouverture, autoriser l'accès aux fichiers audio pour afficher les morceaux.

Pour compiler un APK de débogage depuis PowerShell, avec Gradle installé et disponible dans le `PATH` :

```powershell
cd .\LecteurMusique
gradle :app:assembleDebug
```

L'APK est généré dans `LecteurMusique\app\build\outputs\apk\debug\app-debug.apk`.

## Utilisation

La liste des morceaux apparaît sous les commandes du lecteur. Touchez un titre pour démarrer sa lecture. Utilisez les boutons pour mettre en pause, passer au morceau précédent ou suivant, activer la lecture aléatoire ou faire défiler les modes de répétition.

L'application demande l'autorisation Android de lire les fichiers audio. Sur Android 13 et ultérieur, elle demande également l'autorisation de notification afin que la lecture multimédia puisse être accompagnée de sa notification.

## Périmètre et limites

- La bibliothèque est constituée de fichiers audio locaux repérés dans `Music` ; l'application ne fournit ni catalogue en ligne ni streaming.
- Les fichiers doivent être visibles par le lecteur multimédia Android (MediaStore).
- Il n'y a pas encore de recherche, de gestion de playlists, d'égaliseur ou d'affichage de pochettes.
- L'interface et les fonctionnalités peuvent changer : ce projet reste une expérimentation de petite taille.

## Technologies

- Kotlin et Jetpack Compose
- Material 3
- AndroidX Media3 et ExoPlayer
- Gradle avec le plugin Android Gradle 8.7.3

## Structure du projet

```text
LecteurMusique/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/lucas/musique/
│           ├── MainActivity.kt
│           └── PlaybackService.kt
├── build.gradle.kts
└── settings.gradle.kts
```

- `MainActivity.kt` gère l'interface, les autorisations et le chargement de la bibliothèque locale.
- `PlaybackService.kt` gère le lecteur et la session multimédia en arrière-plan.

## Licence

Consultez le fichier [LICENSE](LICENSE).
