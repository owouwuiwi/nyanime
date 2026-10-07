# Nyanime: codice privato e aggiornamenti pubblici

[owouwuiwi/nyanime](https://github.com/owouwuiwi/nyanime) contiene APK firmati,
checksum, changelog, documentazione, logo e attribuzioni. Il codice, la cronologia
completa, i test e le compilazioni sono conservati nella repository privata
`owouwuiwi/nyanime-source`.

## Aggiornamenti e vecchie installazioni

La repository pubblica conserva la stessa identità GitHub e gli stessi indirizzi
di download. Gli indirizzi storici continuano a reindirizzare alla distribuzione
pubblica: non vanno ricreati, rinominati o resi privati.

Ogni pubblicazione comprende la versione numerica `vX.Y.Z.W` e una release
permanente `rNNNN` con gli APK `app-<architettura>-preview.apk`. Anche i primi
updater possono raggiungere direttamente una versione attuale. La revisione
viene calcolata dalla cronologia privata completa, mai dai commit della
documentazione pubblica. Firma, identificativo Android e progressione dei
versionCode rimangono invariati; i canali consigliato e anteprima restano distinti.

## Pubblicazione

La CI privata compila e verifica il codice. Il publisher pubblico riceve soltanto
APK firmati, documenti selezionati e checksum verificati; non compila e non
pubblica il codice dell'app. Le release vengono create dall'automazione GitHub.
I commit pubblici usano l'identità `owouwuiwi`.

Le versioni già distribuite non vengono sovrascritte: i nuovi tentativi e le
promozioni di canale riutilizzano gli stessi APK. I tag pubblici puntano a documenti,
quindi gli archivi ZIP/TAR generati da GitHub non contengono sorgenti dell'app.
I tag privati conservano i commit del codice corrispondente.

La riscrittura rimuove il codice dai riferimenti pubblici. Vecchie copie, fork o
oggetti precedentemente memorizzati da GitHub non possono essere cancellati
con una normale riscrittura della cronologia.
