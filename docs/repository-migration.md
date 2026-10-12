# Nyanime: codice privato e aggiornamenti pubblici

[owouwuiwi/nyanime](https://github.com/owouwuiwi/nyanime) contiene APK firmati,
checksum, changelog, documentazione, logo e attribuzioni. Il codice, la cronologia
completa, i test e le compilazioni sono conservati nella repository privata
`owouwuiwi/nyanime-source`.

## Aggiornamenti e vecchie installazioni

La repository pubblica conserva la stessa identità GitHub e gli stessi indirizzi
di download. Gli indirizzi storici continuano a reindirizzare alla distribuzione
pubblica: non vanno ricreati, rinominati o resi privati.

Da 0.42.0.0 le nuove pubblicazioni comprendono soltanto la versione numerica
`vX.Y.Z.W` e gli APK `Nyanime-Production-<versione>-<architettura>.apk`, firmati con
il certificato di produzione per `it.owouwuiwi.nyanime`. Il nome dei file conserva
la compatibilità con gli updater della nuova app già installati.

Le vecchie release e gli alias `rNNNN` rimangono disponibili senza sostituire i loro
APK. La 0.41.0.6 è l'ultimo ponte per aggiornare il vecchio pacchetto e avviare il
[trasferimento guidato](identity-transfer.md) alla nuova app. Non vengono costruiti
né pubblicati nuovi APK con la vecchia firma. La numerazione Android continua a
crescere e i canali consigliato e anteprima restano distinti.

## Pubblicazione

La CI privata compila e verifica il codice. Il publisher pubblico riceve soltanto
APK firmati, documenti selezionati e checksum verificati; non compila e non
pubblica il codice dell'app. Le release vengono create dall'automazione GitHub.
I commit pubblici usano l'identità `owouwuiwi`.

Le versioni già distribuite non vengono sovrascritte: i nuovi tentativi e le
promozioni di canale riutilizzano gli stessi APK. I tag pubblici puntano a documenti,
quindi gli archivi ZIP/TAR generati da GitHub non contengono sorgenti dell'app.
I tag privati conservano i commit del codice corrispondente.

Il publisher conserva l'ultimo ponte nella posizione leggibile dai primi updater:
i nuovi tag di documentazione usano una data di archivio anteriore al ponte.
Le date dei commit privati e di pubblicazione delle release non cambiano.
«Consigliato» punta esplicitamente alla versione numerica corrente. Gli updater
più recenti selezionano solo gli asset della propria identità, senza tentare di
installare la nuova firma sopra il vecchio pacchetto. Il publisher rifiuta bundle
con la firma precedente, pacchetti inattesi o checksum non corrispondenti.

La riscrittura rimuove il codice dai riferimenti pubblici. Vecchie copie, fork o
oggetti precedentemente memorizzati da GitHub non possono essere cancellati
con una normale riscrittura della cronologia.
