# Nyanime: sorgenti e aggiornamenti pubblici

[owouwuiwi/nyanime](https://github.com/owouwuiwi/nyanime) contiene il codice
pubblico dell'app, i contratti generici delle estensioni, le istruzioni di build,
le licenze, la documentazione e gli APK firmati. Ogni nuova release conserva un
archivio dei sorgenti verificato e la relativa revisione. La precedente
separazione tra codice privato e documentazione pubblica è stata superata.

## Continuità degli aggiornamenti

L'identità del repository pubblico e gli indirizzi di download restano invariati.
Le versioni già distribuite non vengono sovrascritte: i nuovi tentativi e le
promozioni di canale riutilizzano gli stessi APK e checksum.

Da 0.42.0.0 le nuove pubblicazioni comprendono soltanto la versione numerica
`vX.Y.Z.W` e gli APK `Nyanime-Production-<versione>-<architettura>.apk`, firmati
con il certificato di produzione per `it.owouwuiwi.nyanime`.

Le vecchie release e gli alias `rNNNN` conservano i loro APK. La 0.41.0.6 resta
l'ultimo ponte per trasferire i dati dal vecchio pacchetto alla nuova app.
Non vengono costruiti nuovi APK con la vecchia firma.

## Sorgenti e pubblicazione

La pipeline compila e verifica l'app, poi prepara uno snapshot pubblico e
`Nyanime-Source-<versione>.zip`. Il publisher verifica gli hash degli APK e
l'esatta corrispondenza tra sorgenti pubblici e archivio prima di pubblicare.
Chiavi di firma, credenziali dell'ambiente e dati locali non entrano nel bundle.

`releases/<versione>.json` registra versione, certificato, hash degli APK,
revisione dei sorgenti e hash dell'archivio. Per le versioni precedenti allo
snapshot pubblico consultare gli archivi aggiunti alle rispettive release:
i vecchi tag pubblici di documentazione non vengono riscritti.

Il publisher conserva il ponte leggibile dai primi updater usando date di
archivio precedenti al ponte per i tag numerici. Questa data di compatibilità
non è la data di pubblicazione: la revisione effettiva è indicata nei metadati.
«Consigliato» punta esplicitamente alla versione numerica corrente.

Consulta [licenze e copyright](licensing.md) e le [istruzioni di build](../CONTRIBUTING.md)
per ricompilare l'app e identificare le dipendenze native.
