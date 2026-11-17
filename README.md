# TWOM Live

Application Android **en lecture seule** qui affiche en direct ce que le serveur de
The World of Magic envoie à ton téléphone : mobs autour de toi (type, niveau, position),
mobs déjà en combat avec d'autres joueurs, morts et tueur, or au sol et ramassé, chat.
Elle affiche aussi ce que ton téléphone envoie au serveur : tes attaques (avec le mob visé),
tes compétences, et avec « Détails » tes déplacements et les messages techniques.

L'appli n'envoie rien au jeu et ne modifie aucune donnée : elle relaie les connexions
de TWOM telles quelles et lit au passage le flux du serveur de jeu.

## Installation

1. Ouvre la page **Releases** du dépôt et télécharge `TWOM-Live.apk` (dernière version).
2. Ouvre le fichier sur ton téléphone. Android demandera d'autoriser l'installation
   depuis ton navigateur ou ton gestionnaire de fichiers : accepte pour cette source.

## Utilisation

1. Ouvre **TWOM Live** et touche **▶ Démarrer**. Accepte la demande de connexion VPN
   (une clé s'affiche dans la barre d'état).
2. Touche **Ouvrir TWOM**. Si le jeu était déjà ouvert, ferme-le complètement et relance-le :
   une connexion déjà établie avant la capture ne peut pas être lue.
3. Reviens dans TWOM Live pour voir les mobs en vue et le journal.

- **Chat** : affiche ou masque les messages du chat.
- **Envoyés** : affiche ce que ton téléphone envoie (attaques 🗡, compétences ✨).
- **Détails** : ajoute les déplacements des mobs, les coups portés et les joueurs en vue.
- **Partager (CSV)** : envoie le journal des événements (morts, apparitions, or) au format CSV.

Seul TWOM passe par le VPN local ; le reste du téléphone n'est pas concerné.

## Limites

- Le serveur n'envoie que ce qui est proche de ton personnage : l'appli ne voit pas toute la map.
- Le protocole a été déduit de captures de novembre 2026 ; une mise à jour du jeu peut le changer.
- IPv4 uniquement.

## Fonctionnement

- `TwomVpnService` : VPN local limité à TWOM (`addAllowedApplication`).
- `Relay` : mini-pile TCP qui termine les connexions côté téléphone et les rouvre vers les
  vrais serveurs, plus un relais UDP (DNS).
- `TwomDecoder` : déchiffrement (XOR + rotation, clé par trame) et lecture des messages,
  dans les deux sens. Côté téléphone : `0x43` déplacement, `0x4f` attaque/compétence
  (en-tête + ~2 Ko de remplissage), `0x68` ping, `0xa8` actions d'interface, `0x27` identification.
- `GameState` : suivi des entités et journal.

Les tests (`app/src/test`) rejouent une vraie capture à travers le décodeur et le relais.
Chaque push sur `main` relance les tests, compile l'APK et le publie dans les Releases.
