# Nyanime 0.49.0.0 — migrazione dalla prova NyanimeB

## Perimetro della versione ordinaria

La versione usa lo stesso pacchetto e la stessa firma delle installazioni correnti,
con numero 0.49.0.0 e codice 280, superiori a NyanimeB 0.48.2.23 / 279.
Non serve disinstallare o cancellare dati per aggiornare da quella prova.
La distribuzione avviene nel canale **Consigliato**, con updater interno attivo.
Il nome tecnico `preview` della variante Gradle non indica il canale OTA:
quest'ultimo è dichiarato in `release.properties` e nei metadati pubblicati.

| Area | Versione ordinaria |
| --- | --- |
| Studio delle fonti | Canvas, vista guidata, Assistita, prove, import/export e backup |
| Scambio fonti | Sessioni esplicite locali/Internet, revisione e importazione separate |
| Backup | Locali e Telegram conservati; WebDAV cifrato e Drive con OAuth proprio |
| Player | MPV esistente con lo stile grafico FLOW isolato e attribuito |
| Ricerca e Home | Contratti generici, Atlante e gestione ordinaria delle fonti |
| Attenzione | Facoltativa, spenta inizialmente, fotocamera locale e limiti documentati |
| Sigle | Metadati e collegamenti esterni, senza client YouTube incorporato |
| Musica | Icona conservata con schermata «in arrivo nelle prossime settimane» |
| FLOW/YouTube e MELD | Motori, moduli, submoduli, estrattori, account e servizi assenti |
| Android TV e traduzione manga | Esclusi dalla migrazione |

Il campo storico 513 dei backup resta riservato e non viene reinterpretato.
L’eventuale codice di addon installati separatamente continua a usare i contratti
generici preesistenti; l’app non include o preconfigura un addon musicale/video.

## Provenienza e distribuzione

La base ordinaria è `d8a8509cea75a093577c22a5914e5851335dd32d`; le prove B
sono state confrontate fino a `034ab04f478e06c25de5ff9c9e9501f7a5e74c09`.
I repository condividono l’antenato `b5134d696f0842e3b45093f460857449e94bdcaf`.
La migrazione è selettiva: le correzioni ordinarie e le personalizzazioni del player
sono conservate, senza una fusione indiscriminata del ramo sperimentale.

Il codice originale del ponte grafico è Apache-2.0; la grafica adattata da FLOW
resta GPL-3.0-only. Il player nativo mantiene le licenze GPL e i materiali già
previsti nella distribuzione ordinaria. Il nuovo inventario comprende 152 librerie
native su quattro ABI, contro le 136 precedenti, con hash originali del player
invariati. Le aggiunte sono scambio fonti, MediaPipe e helper CameraX.

I cinque archivi nativi dichiarati in `tools/native/distribution.json` accompagnano
il sorgente dell’app. Le due nuove chiusure sono già verificate per hash; la copia
privata usata dalla CI non sostituisce i materiali consegnati al destinatario.
I limiti di provenienza dei binari Maven precompilati sono espliciti e non vengono
nascosti da un risultato positivo del verificatore. [Licenze](licensing.md).

## Cronologia

Il changelog conserva integralmente le note ordinarie precedenti e tutte le 23
voci documentate della prova NyanimeB. Il suffisso `[storico sperimentale]`
impedisce che quelle voci vengano riproposte come nuove funzionalità dell’OTA.
Non vengono inventate release per numeri interni privi di una nota documentata.

## Verifiche

La compilazione dei sorgenti ordinari e del renderer isolato è riuscita.
Gli otto test JVM della timeline e i controlli delle dipendenze del renderer
sono passati. I test di rilascio verificano anche dipendenze escluse, classi in DEX
secondari, attribuzioni Apache/GPL, cronologia OTA e hash degli archivi.
Le prove native Rust comprendono otto unit test e una prova di arresto: nove passati.

WorkManager 2.11.2 e Room 2.8.4 sono dipendenze esplicite dell’app, mantenendo la base
già distribuita in NyanimeB: il database delle attività resta allo schema 24 anziché
tornare allo schema 23 e perdere le attività programmate attraverso la migrazione
distruttiva della vecchia libreria. La regola upstream per i costruttori Room è
dichiarata anche nell’app, indipendente dai moduli esclusi.
`scripts.verify_work_database` blocca il downgrade su entrambi i classpath e verifica
il costruttore riflessivo nel DEX ottimizzato, anche in un DEX secondario.

Le prove Android complete, l’APK ottimizzato e le verifiche sul dispositivo devono
riferirsi a questa versione: i risultati storici su NyanimeB non li sostituiscono.
Le integrazioni che richiedono account personali, più dispositivi o una persona
davanti alla fotocamera conservano i limiti di validazione indicati nelle guide.

## Limite ereditato dei binari nativi

Le 16 nuove librerie (quattro per ABI) sono verificate per segmenti ELF da 16 KiB.
Nell’inventario precedente rimangono 28 file con segmenti da 4 KiB, conservati
senza modifiche: fra questi libarchive, image decoder, QuickJS, SQLite e TorrServer,
oltre ad alcuni runtime nelle ABI a 32 bit. L’allineamento ZIP dell’APK non equivale
al supporto ELF completo da 16 KiB. Non viene quindi dichiarata una compatibilità
universale con dispositivi Android a pagine da 16 KiB; il telefono USB di questa
prova usa pagine da 4096 byte. Questo limite è precedente alla migrazione.
