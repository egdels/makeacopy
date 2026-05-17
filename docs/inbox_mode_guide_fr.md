# MakeACopy – Mode Inbox

Ce guide explique étape par étape comment utiliser le mode Inbox dans MakeACopy. Ce mode est conçu pour les utilisateurs qui numérisent de nombreux documents à la suite et souhaitent les enregistrer automatiquement dans un dossier prédéfini — sans sélectionner un emplacement à chaque fois.

---

Sommaire
- Que fait le mode Inbox ?
- Activer le mode Inbox
- Workflow de numérisation avec le mode Inbox
- Noms de fichiers et gestion des collisions
- Inclure le texte OCR (export TXT automatique)
- Documents multi-pages
- Démarrer automatiquement un nouveau scan
- Comportement de repli
- Exemples d'intégration
- Confidentialité et fonctionnement hors ligne
- Foire aux questions (FAQ)

---

Que fait le mode Inbox ?
- Permet de configurer un **répertoire d'export par défaut** (la « Inbox »).
- Enregistre automatiquement les scans dans ce répertoire — aucun sélecteur de fichiers requis.
- Génère automatiquement les noms de fichiers avec une gestion des collisions.
- Si « Inclure le texte OCR » est activé, un fichier TXT séparé est également exporté automatiquement.
- Prend en charge les PDF multi-pages comme l'export normal.
- Démarre optionnellement un nouveau scan immédiatement après l'export.
- Fonctionne entièrement hors ligne — aucune connexion, aucun téléversement.

---

Activer le mode Inbox
1) Sur l'écran d'export, appuyez sur l'**icône d'engrenage** (Options d'export).
2) Activez la case **« Activer le répertoire d'export par défaut »**.
3) Appuyez sur **« Sélectionner un dossier »** et choisissez votre répertoire Inbox via le sélecteur de dossiers du système.
4) Configurez optionnellement le **format du nom de fichier** (par ex. `YYYY-MM-DD_scan`).
5) Activez optionnellement **« Démarrer automatiquement un nouveau scan après l'export »**.
6) Confirmez avec **« Confirmer »**.

À partir de maintenant :
- Lors de l'export d'un scan, le sélecteur de fichiers est ignoré et le document est enregistré directement dans votre dossier Inbox.
- Vous verrez une brève confirmation : **« ✓ Enregistré dans l'Inbox »**.

---

Workflow de numérisation avec le mode Inbox

### Sans le mode Inbox

    Numériser → Recadrer → OCR → Sélectionner le dossier d'export → Enregistrer

### Avec le mode Inbox

    Numériser → Recadrer → OCR → Enregistré automatiquement → Nouveau scan

La boîte de dialogue d'export est entièrement ignorée. Après l'enregistrement, vous pouvez immédiatement démarrer le scan suivant.

---

Noms de fichiers et gestion des collisions

Le mode Inbox génère automatiquement les noms de fichiers en fonction du modèle configuré :

| Modèle | Exemple |
|---|---|
| `YYYY-MM-DD_scan` (par défaut) | `2026-03-06_scan.pdf` |
| `YYYY-MM-DD_HHmmss_scan` | `2026-03-06_094500_scan.pdf` |
| `YYYY-MM-DD` | `2026-03-06.pdf` |

Si un fichier portant le même nom existe déjà, un suffixe est ajouté :

    2026-03-06_scan.pdf
    2026-03-06_scan_2.pdf
    2026-03-06_scan_3.pdf

---

Inclure le texte OCR (export TXT automatique)

Si **« Inclure le texte OCR »** est activé dans les options d'export, le mode Inbox exporte également un fichier TXT séparé contenant le texte reconnu — automatiquement, sans sélecteur de fichiers.

Le fichier TXT utilise le même nom de base que le PDF ou le JPEG :

    2026-03-06_scan.pdf
    2026-03-06_scan.txt

Cela est utile pour les workflows où vous souhaitez disposer du texte OCR brut à côté du document.

Remarque : Si le mode Inbox n'est **pas** actif, l'export TXT utilise toujours le sélecteur de fichiers normal.

---

Documents multi-pages

Le mode Inbox prend entièrement en charge les PDF multi-pages. Le workflow est :

1. Numérisez la première page.
2. Ajoutez d'autres pages avec « Ajouter une page ».
3. Réorganisez ou supprimez des pages selon vos besoins.
4. Appuyez sur « Exporter » — toutes les pages sont combinées en un seul PDF et enregistrées dans le dossier Inbox.

Le mode Inbox ne change que **l'emplacement** où le fichier est enregistré, pas **la manière** dont il est créé.

---

Démarrer automatiquement un nouveau scan

Lorsque **« Démarrer automatiquement un nouveau scan après l'export »** est activé :

- Après un export Inbox réussi, l'application revient automatiquement à l'écran de la caméra.
- Cela crée une boucle fluide : Numériser → Enregistrer → Numériser → Enregistrer → …

C'est particulièrement utile lors de la numérisation d'une pile de documents.

---

Comportement de repli

Si le dossier Inbox devient inaccessible (par ex. permission révoquée, dossier supprimé) :

- L'application affiche un message expliquant le problème.
- Le mode Inbox est automatiquement désactivé.
- Le sélecteur de fichiers normal est affiché pour que vous puissiez quand même enregistrer votre document.

Vous pouvez réactiver le mode Inbox à tout moment en sélectionnant un nouveau dossier dans les options d'export.

---

Exemples d'intégration

### SambaLite

    Numériser → Dossier Inbox → SambaLite transfère vers le partage SMB → Archive réseau

Configurez le dossier Inbox vers un répertoire surveillé par [SambaLite](https://egdels.github.io/SambaLite/). Les documents numérisés sont automatiquement transférés vers votre partage réseau SMB/CIFS.

### paperless-ngx

    Numériser → Dossier Inbox → paperless-ngx importe → OCR → Archive consultable

Définissez le dossier Inbox comme le répertoire surveillé par paperless-ngx. Chaque scan est automatiquement détecté et traité.

### Nextcloud

    Numériser → Dossier synchronisé → Synchronisation Nextcloud → Archive de documents

Pointez le dossier Inbox vers un répertoire synchronisé Nextcloud sur votre appareil.

### Syncthing

    Numériser → Dossier Syncthing → Synchronisé vers le bureau → Archive

Utilisez un dossier partagé Syncthing comme répertoire Inbox.

---

Confidentialité et fonctionnement hors ligne
- MakeACopy traite toutes les images et l'OCR localement sur votre appareil.
- Le mode Inbox ne téléverse rien. Aucune connexion Internet requise.
- Le dossier Inbox est un répertoire local sur votre appareil (ou un dossier synchronisé géré par une autre application).

---

Foire aux questions (FAQ)

Q : Le dossier Inbox est-il le même que le dossier d'export normal ?
R : Non. Le dossier Inbox et le dossier d'export normal sont stockés indépendamment. La modification de l'un n'affecte pas l'autre.

Q : Puis-je utiliser le même dossier pour l'Inbox et l'export normal ?
R : Oui. Les deux peuvent pointer vers le même répertoire sans conflit, mais ils sont configurés séparément.

Q : Que se passe-t-il si je désactive le mode Inbox ?
R : Le sélecteur de fichiers normal est à nouveau affiché lors de l'export. Votre paramètre de dossier Inbox est conservé et peut être réactivé ultérieurement.

Q : Le mode Inbox fonctionne-t-il avec l'export JPEG ?
R : Oui. Le mode Inbox fonctionne aussi bien pour les exports PDF que JPEG.

Q : Puis-je modifier le format du nom de fichier ?
R : Oui. Dans les options d'export, vous pouvez choisir entre trois modèles : date + scan, date + heure + scan, ou date uniquement.

Q : J'ai exporté un document mais le fichier TXT est manquant.
R : Le fichier TXT n'est créé que lorsque « Inclure le texte OCR » est activé dans les options d'export. Vérifiez que cette option est active.

Q : L'application indique que mon dossier Inbox n'est plus accessible.
R : Cela peut se produire si le dossier a été supprimé ou si la permission a été révoquée (par ex. après une mise à jour système). Sélectionnez à nouveau le dossier dans les options d'export pour restaurer l'accès.

---

Note technique (pour les contributeurs)

- Le mode Inbox est contrôlé par le feature flag `FEATURE_INBOX_MODE` dans `BuildConfig`.
- Les préférences sont stockées dans `SharedPreferences` sous les clés `inbox_enabled`, `inbox_uri`, `inbox_filename_template` et `inbox_auto_new_scan`.
- La logique d'export se trouve dans `ExportFragment.java` ; l'utilitaire de création de fichiers est `InboxExporter.java`.

---

Contact
Si quelque chose n'est pas clair ou si vous avez des suggestions, nous apprécions vos retours dans les avis de l'app store ou dans le dépôt du projet.
