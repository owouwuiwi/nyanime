# Distribuzioni delle estensioni

Nyanime non identifica una distribuzione dal nome della fonte, dal dominio o
contenuto del catalogo. I nomi mostrati arrivano dagli APK e dai repository.
La provenienza e il contratto Home sono verifiche distinte.

## Descrittore APK facoltativo

Un'estensione adattata può includere `assets/nyanime/extension-v1.json`:

```json
{
  "version": 1,
  "id": "example-local",
  "label": "Edizione locale",
  "updatePolicy": "manual"
}
```

Il file è limitato a 64 KiB. La versione 1 accetta `manual` oppure
`repository`; quest'ultima richiede `repository` con l'indirizzo HTTPS del
catalogo. L'identificatore resta stabile fra versioni della stessa distribuzione.
Il descrittore non attribuisce fiducia all'APK e non sostituisce la firma Android.
Package, source ID e chiave di firma non devono cambiare per aggiungerlo.

## Home e provenienza

L'ispezione locale condivide i manifest e i filtri del contratto Home esistente,
fuori dal thread UI. Le lingue disabilitate e le fonti nascoste non tolgono il
supporto dichiarato. Il catalogo remoto senza prove sufficienti mostra Da verificare.
Un APK precedente con Home e senza un'origine verificata riceve aggiornamenti manuali.
Le estensioni Senza Home restano disponibili in Sfoglia e nella libreria, inclusi
i titoli nelle sezioni locali della Home.

## Aggiornamenti

Schermate, notifiche, contatori e aggiornamenti collettivi usano una sola politica:
versionCode più recente, API supportata, package reale, certificato attuale e
distribuzione consentita. Candidati di repository diversi rimangono separati;
una corrispondenza ambigua non viene risolta scegliendo il primo elemento.
Un repository offline non dimostra che l'APK sia obsoleto.

Prima di qualsiasi installazione, inclusa quella privata, il file viene copiato
in un'area interna, verificato con APK Signature Scheme e consegnato all'installatore
nella copia verificata. Sono ricontrollati package, versione, API, firma, discendenza
verificata della chiave e descrittore. Una distribuzione manuale non viene sostituita
silenziosamente da un download del catalogo.

Mantieni questa versione e le associazioni ai repository sono preferenze portabili
incluse nei backup. Dopo il ripristino sono utilizzabili solo con package e firmatari
corrispondenti agli APK presenti. Non installano, rimuovono o cambiano distribuzioni.

Gli aggiornamenti delle edizioni locali si installano mediante APK compatibili;
Nyanime non contiene un elenco di tali edizioni né un catalogo riservato.
