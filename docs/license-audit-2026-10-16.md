# Nyanime: verifica delle attribuzioni del 16 ottobre 2026

La verifica riguarda **Nyanime normale**, sulla base pubblicata 0.48.0.1
(`b5134d696f0842e3b45093f460857449e94bdcaf`), e le correzioni successive
dell'inventario e della consultazione delle licenze. Non integra FLOW,
la relativa identità visiva o altri componenti di NyanimeB.

## Problema e correzione

L'APK ARM64 pubblicato 0.48.0.1 contiene già i testi completi GPL, WebRTC BSD,
licenza MIT del binding e gli avvisi nativi. Tre riferimenti nell'inventario
Maven non hanno però il testo associato: il collettore usa identificatori hash
diversi dai nomi SPDX già configurati. Sono stati aggiunti i due alias necessari,
con i testi originali già inclusi, senza cambiare licenza ai componenti.

La schermata e il verificatore condividono ora i registri degli avvisi offline.
Sono conservate tutte le 17 voci precedenti e le 24 voci native, compresi
copyright e NOTICE separati. Il JSON delle dipendenze viene letto tramite
`R.raw.aboutlibraries`, conservando un riferimento alla risorsa anche dopo
l'ottimizzazione. I dati generati vengono invalidati quando cambiano dichiarazioni,
cataloghi Gradle o configurazione delle attribuzioni.

## Evidenze controllate

- Inventario rigenerato della variante preview: **283 dipendenze**, 13 testi
  di licenza, nessuna dichiarazione o testo mancante.
- Asset effettivamente uniti dalla build: **41 voci** consultabili, con i
  relativi testi e gli avvisi separati presenti e leggibili in UTF-8.
- Il nuovo verificatore controlla anche gli APK, riconoscendo l'inventario dai
  byte effettivi e senza dipendere dal nome della risorsa ottimizzata.
- **57 test Python** superati per metadati, avvisi, packaging, distribuzione
  nativa e note/versioni delle release. I casi coprono anche un inventario
  rinominato, dati obsoleti, testi mancanti, avvisi separati e percorsi non validi.
- Compilazione Kotlin della schermata nella variante preview, formattazione,
  raccolta dei metadati e unione degli asset completate. Questa verifica non
  produce un nuovo APK firmato e non è una prova della schermata sul telefono.
- Il repository pubblico e la release `v0.48.0.1` risultano accessibili senza
  autenticazione. Sono presenti il sorgente dell'app, il manifesto nativo e gli APK.
- I tre archivi nativi richiamati dal manifesto rispondono pubblicamente con
  HTTP 200. I digest pubblicati da GitHub coincidono con gli SHA-256 dichiarati:
  player e supplemento della 0.42.0.2, WebRTC della 0.47.0.2.

Le verifiche sui download dei sorgenti accertano disponibilità e identità
pubblicata; questa revisione non ha ricompilato tutti i componenti nativi né
riscaricato integralmente quei tre archivi. Manifesti, revisioni e ricette di
compilazione rimangono nella [guida nativa](../tools/native/README.md).

## Limiti della verifica

La presenza di un testo non certifica da sola la conformità di una distribuzione.
Ogni release deve conservare sorgenti corrispondenti, configurazioni e avvisi
richiesti dalle licenze dei componenti inclusi. Apache-2.0 delle contribuzioni
originali non sostituisce gli obblighi GPL del player combinato.

Non sono stati cambiati chiavi, permessi, servizi, dipendenze, player o formati
delle estensioni. Non vengono dichiarati verificati i diritti di contenuti o
marchi di terzi, i termini di tutti i servizi API o l'approvazione di uno store.
Per ripetere i controlli dell'inventario e dei testi, seguire la
[guida alle licenze](licensing.md#inventario-e-testi-consultabili-offline).
