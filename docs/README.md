# Documentazione Nyanime

Le guide descrivono il codice presente nel repository. Alcune funzioni dipendono
da un'estensione compatibile, da un servizio esterno o dalle capacità del dispositivo.
I documenti tecnici in inglese conservano i nomi delle API e dei componenti.

## Per chi usa l'app

| Guida | Contenuto |
| --- | --- |
| [Condivisione in Nyanime](content-sharing.md) | Schede, episodi con minutaggio e capitoli con pagina, senza logica specifica delle fonti. |
| [Primi passi e FAQ](getting-started.md) | Installazione, aggiornamenti, estensioni, preferenze e problemi comuni. |
| [Versioni e aggiornamenti](versioning.md) | Numerazione a quattro componenti, scelta dei canali e compatibilità OTA. |
| [Nyanime Cloud](telegram-cloud.md) | Account Telegram condiviso, backup privati, ripristino e configurazione di sviluppo. |
| [Sincronizzazione personale](cloud-sync-protocol.md) | Associazione dei dispositivi, dati portabili, cifratura, conflitti e verifiche. |
| [Trasferimento del repository](repository-migration.md) | Nuovo proprietario, continuità degli aggiornamenti e dei collegamenti. |
| [Aiuto](support.md) | Archiviazione, migrazione, tracker e risoluzione dei problemi. |
| [Privacy](privacy.md) | Dati locali, connessioni esterne e controlli disponibili. |
| [Tutte le funzionalità](features.md) | Catalogo delle funzioni video, manga, librerie, rete e dati. |
| [Interfaccia](nyanime-ui.md) | ModernUI, ritorno alla legacy, tema manga e copertine. |
| [Smart e timer](player-startup-and-sleep.md) | Avvio di Anime4K, timer, autoplay e stanze. |
| [AniSkip](aniskip.md) | Attivazione, associazione del titolo e segmenti disponibili. |
| [Guide episodi](episode-guides.md) | Classificazioni facoltative, associazione, numerazione, filtri e salto filler. |
| [Novità Nyanime](../CHANGELOG.md) | Modifiche del fork e collegamento allo storico upstream. |

## Funzioni e architettura

| Guida | Contenuto |
| --- | --- |
| [AnimeSchedule facoltativo](animeschedule.md) | Collegamento guidato, RAW/SUB/DUB, limiti e widget. |
| [Uscite e calendario](release-monitor.md) | Segui, avvisi, controlli mirati, date annunciate e recupero. |
| [Primo piano](title-details.md) | Schede anime e manga, ripresa, gestione di episodi e capitoli e transizioni. |
| [Informazioni sulle opere](work-information.md) | Approfondimenti facoltativi senza account, cataloghi, persone e associazioni. |
| [Atlante](atlas-search.md) | Ricerca unificata, categorie, filtri, navigazione e limiti condivisi. |
| [Ricerca intelligente](smart-title-search.md) | Motore comune, suggerimenti, limiti, privacy e licenza SymSpellKt. |
| [Home e scoperta](discovery-home.md) | Cataloghi, associazione alle fonti, calendario, cache e ripresa. |
| [Home Panorama](home-panorama.md) | Carosello circolare, ripresa compatta, caricamento e interazioni. |
| [Store Vetrina](extension-store.md) | Estensioni Ready e Legacy, inventario, installazione e sostituzione guidata. |
| [Estensioni Notizie](news-extensions.md) | Fonti separate, lettore nativo, articoli salvati e avvisi facoltativi. |
| [Addon opzionali](addons.md) | Modalità installabili, logo e attivazione dichiarati, isolamento, tema e aggiornamenti. |
| [API Home anime](extension-home-api.md) | Contratto dichiarativo generico delle estensioni. |
| [API Home manga](manga-home-api.md) | Sezioni manga, capitoli, classifiche e identità degli elementi. |
| [ID per il tracking](extension-tracking-metadata.md) | Contratto facoltativo e generico per identificare titoli senza indovinare la stagione. |
| [Dall'anime al manga](anime-manga-continuity.md) | Relazioni per ID, copie verificate, archi e limiti dei checkpoint. |
| [Anime4K Smart](anime4k-smart.md) | Misure di rendering, preset, fallback e shader. |
| [Guarda insieme](watch-together.md) | Stanze cifrate, sincronizzazione, inviti e verifiche. |
| [I miei dispositivi (disattivato)](personal-sync.md) | Sync rimosso dall’app e pulizia dei suoi dati sul dispositivo. |
| [Protezione laterale del display](privacy-display.md) | Architettura, requisiti hardware e verifica fisica. |
| [Community dormiente](community-protocol.md) | Codice social conservato, disabilitato nell’app. |
| [Cast](casting.md) | Google Cast, UPnP/DLNA, telecomando, relay locale e limiti. |
| [Affidabilità](app-reliability.md) | Ricerca, download, backup, copertine e prestazioni. |
| [Motore di download](download-components.md) | Accelerazione adattiva, ripresa verificata, esecuzione Android e componenti riutilizzati. |
| [Aggiornamenti delle estensioni](extension-update-alerts.md) | Frequenza dei controlli e deduplicazione delle notifiche. |
| [Cataloghi delle estensioni](extension-catalogues.md) | Importazione e collegamento esplicito degli APK locali agli aggiornamenti. |
| [Regressioni del player](stability-regressions.md) | Verifiche native e del ciclo di vita del player. |
| [Verifica del 6 ottobre](regression-audit-2026-10-06.md) | Correzioni di Home, tracking e notifiche, controlli eseguiti e limiti della prova. |

## Sviluppo e provenienza

- [Contribuire e compilare](../CONTRIBUTING.md).
- [Traduzioni](../i18n/README.md).
- [Benchmark](../macrobenchmark/README.md): misurazioni dedicate.
- [Dipendenze conservate localmente](../vendor/README.md).
- [Manutenzione FFmpeg e build nativa](../tools/native/README.md).
- [Crediti e licenze](credits.md).
- [Changelog storico AniYomi](history/aniyomi-changelog.md): archivio upstream,
  non elenco delle release Nyanime.

- [Distribuzioni e aggiornamenti delle estensioni](extension-distributions.md)

- [Nyanime RIN: formato, migrazione e aggiornamenti](rin.md)
