# Nova pour Android (téléphone et Android TV)

Ce dossier contient le projet complet de l'appli Nova.
GitHub fabrique l'APK automatiquement à chaque dépôt du projet.

## 1. Mettre le projet sur GitHub (une seule fois)

1. Crée un compte gratuit sur https://github.com si tu n'en as pas.
2. En haut à droite, clique sur **+** puis **New repository**.
3. Nom : `nova`. Choisis **Public** (le projet ne contient aucun identifiant), puis **Create repository**.
4. Sur la page qui s'affiche, clique sur le lien **uploading an existing file**.
5. Ouvre le dossier `nova-android` sur ton ordinateur, sélectionne **tout son contenu**
   (y compris le dossier `.github`) et fais-le glisser dans la page GitHub.
6. En bas, clique sur **Commit changes**.

> Si le dossier `.github` n'a pas été envoyé (il n'apparaît pas dans la liste des fichiers) :
> clique sur **Add file › Create new file**, tape le nom `.github/workflows/build.yml`,
> colle le contenu du fichier `build.yml` de ce dossier, puis **Commit changes**.

## 2. Récupérer l'APK

1. Ouvre l'onglet **Actions** du dépôt : la fabrication démarre toute seule (environ 5 minutes).
2. Quand la ligne passe au vert ✅, ouvre l'onglet **Releases** (colonne de droite de la page d'accueil du dépôt).
3. Le fichier **nova.apk** est là.

Lien direct, toujours vers la dernière version :
`https://github.com/TON-PSEUDO/nova/releases/latest/download/nova.apk`

## 3. Installer sur Android TV / Google TV

1. Sur la télé, installe l'appli **Downloader** (by AFTVnews) depuis le Play Store.
2. Autorise-la à installer des applis :
   **Paramètres › Applis › Sécurité et restrictions › Sources inconnues › Downloader**
   (sur Google TV : **Paramètres › Système › Options pour les développeurs**, ou bien
   **Paramètres › Applis › Sécurité** selon le modèle).
3. Dans Downloader, tape le lien direct ci-dessus, puis **Go** et **Installer**.
4. Nova apparaît dans la liste de tes applis, avec sa bannière.

## 4. Installer sur le téléphone

Ouvre le lien direct dans le navigateur du téléphone, télécharge `nova.apk`,
ouvre-le et accepte l'installation depuis cette source.

## Télécommande

| Où | Touche | Action |
|---|---|---|
| Menus | Flèches | Se déplacer |
| Menus | OK | Ouvrir |
| Menus | Retour | Revenir en arrière (2 fois sur l'accueil pour quitter) |
| Film / série | OK ou ⏯ | Pause / lecture |
| Film / série | ◀ ▶ | Reculer / avancer de 10 s (maintenir pour aller plus vite) |
| TV en direct | ▲ ▼ ou CH+ CH− | Chaîne suivante / précédente |
| TV en direct | Chiffres | Aller à une chaîne |

## Mettre à jour l'appli

Remplace les fichiers modifiés sur GitHub (**Add file › Upload files**) : un nouvel APK
se fabrique tout seul, et le lien direct pointe toujours vers la dernière version.
