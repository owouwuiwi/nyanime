# Crediti e licenze

Nyanime è un fork di AniYomi. Il nuovo nome identifica questa distribuzione e
le sue personalizzazioni; non cambia la provenienza del codice mantenuto.

## App e progetti upstream

Il [pannello di tracking](tracking-panel.md) riprende come riferimento di prodotto
il pannello mostrato da [Karasu](https://github.com/Suzora/Karasu).
L'implementazione Compose di Nyanime è originale e non importa codice web,
componenti o asset di Karasu.

- [AniYomi](https://github.com/aniyomiorg/aniyomi): base video e manga,
  player, estensioni e numerose funzioni conservate.
- [Mihon](https://github.com/mihonapp/mihon) e Tachiyomi: base del lettore,
  librerie, dati e contributi ereditati.
- Contributori dei progetti e traduttori: lo storico Git e le attribuzioni nei file
  conservano il loro lavoro.

Le attribuzioni riportate dal README precedente sono mantenute:

```text
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2024 Aniyomi Open Source Project
```

Il codice originale di Nyanime usa [Apache License 2.0](../LICENSE), con
copyright © 2026 owouwuiwi e contributori Nyanime. Il [NOTICE](../NOTICE)
conserva le attribuzioni; la [guida alle licenze](licensing.md) distingue
il codice dell'app dagli obblighi dei componenti inclusi, anche GPL/LGPL.
Lo [storico AniYomi](history/aniyomi-changelog.md) rimane archiviato separatamente.

## Componenti e servizi

| Componente | Impiego e provenienza |
| --- | --- |
| FLOW — solo presentazione del player | Sottoinsieme grafico GPL-3.0-only, revisione e modifiche in [PROVENANCE](../presentation-player/third-party/flow-ui/PROVENANCE.json). Motore YouTube e servizi non inclusi. |
| CameraX e MediaPipe | Analisi locale facoltativa dell’attenzione; SDK e modello Apache-2.0, avvisi nativi originali, trasformazione locale delle statistiche documentata in [core/attention](../core/attention/README.md). |
| Lightning P2P / iroh | Compatibilità dei ticket e trasporto per lo scambio fonti, con processi e schermata Nyanime; ricette e attribuzioni in [source-exchange](../core/source-exchange/native/UPSTREAM.md). |
| Sandspiel | Simulazione offline, MIT; submodulo fissato e relativi sorgenti inclusi nell’archivio dell’app. |
| mpv e integrazione Android | Player interno; bridge Android originario conservato nel rebuild `it.owouwuiwi.native:nyanime-mpv`. [AAR, sorgenti e verifiche](../tools/native/README.md). |
| FFmpeg / FFmpegKit | Demuxing e media nel rebuild nativo GPL; wrapper Android e licenze originali conservati. [Sorgenti, patch, configurazione e licenze](../tools/native/README.md). |
| TorrServer | Componente nativo GPL-3.0 e relativo wrapper; sorgenti Go, dipendenze vendor e ricetta nell'archivio supplementare descritto nella stessa guida nativa. |
| Anime4K | Shader ufficiali inclusi: [descrizione degli asset](../app/src/main/assets/anime4k/README.txt) e [licenza](../app/src/main/assets/anime4k/LICENSE). |
| Nostr | Protocollo di trasporto delle stanze; [implementazione e riferimenti](watch-together.md). |
| ACINQ secp256k1-kmp | Firme delle identità effimere delle stanze; riferimenti nella stessa guida. |
| ZXing | Generazione locale dei QR d'invito; riferimenti nella guida delle stanze. |
| Piik | Ispirazione per il salotto nativo e il recupero limitato dei collegamenti; MIT, TNTcraft e contributori. Il backend e la condivisione video non sono incorporati. |
| WebRTC e webrtc-sdk/android | Collegamenti vocali facoltativi, separati dal player; BSD-3-Clause e licenze dei componenti inclusi, binding MIT. [Versioni e provenienza](../core/room-voice/README.md). |
| Google Cast e UPnP/DLNA | SDK e protocolli per i ricevitori; [integrazione Cast](casting.md). |
| AniSkip | Dati facoltativi per il salto dei segmenti; [integrazione](aniskip.md). |
| AniList e Kitsu | Metadati e cataloghi, distinti dalle fonti di riproduzione; [scoperta](discovery-home.md). |
| AniList, TVmaze e Wikidata | Approfondimenti pubblici delle opere: AniList API, TVmaze CC BY-SA e Wikidata CC0; [attribuzioni e condizioni](work-information.md). |
| MIT KEMAR HRTF | Cinema in cuffia: dati di Bill Gardner e Keith Martin, MIT Media Laboratory ©1994, uso libero con attribuzione; [licenza e integrazione](headphone-cinema.md). |
| SymSpellKt 3.4.0 | Correzione lessicale con dizionario costruito durante l’uso; MIT, Adam Brown, Lucky Sharma e Wolf Garbe. [Integrazione e avvisi completi](smart-title-search.md#licenza-della-dipendenza). |
| FlexibleAdapter | Artefatto upstream conservato senza modifiche nel [mirror vincolato](../vendor/README.md), con la [licenza](../vendor/FlexibleAdapter-LICENSE). |
| Downpour e Fetch | Controllo delle connessioni e media della velocità adattati, Apache-2.0; [revisioni, modifiche e licenze](download-components.md). |
| AndroidX Media3, OkHttp e libarchive Android | Parser HLS, rete delle estensioni e archivi CBZ; [integrazione](download-components.md). |
| TDLib e OpenSSL | Motore Telegram BSL-1.0 e trasporto nativo Apache-2.0; licenze, avvisi e provenienza inclusi negli asset e nella [guida nativa](../tools/native/README.md). |
| DeepPanel | Riconoscimento dei pannelli: codice e modello con licenza Apache-2.0; [NOTICE e provenienza](../core/panels/NOTICE.md). |

L'elenco orienta tra i componenti principali; non sostituisce l'elenco completo
delle dipendenze nei cataloghi Gradle e delle licenze incluso nell'app.
Le licenze delle dipendenze rimangono applicabili: la licenza dell'app non
riclassifica quelle dei componenti nativi o dei relativi sorgenti.
I registri e i testi offline vengono verificati anche negli APK ottimizzati;
la [guida alle licenze](licensing.md#inventario-e-testi-consultabili-offline)
descrive copertura e limiti del controllo.

## Identità visiva e immagini

Il riferimento visivo è
[flutter_netflix](https://github.com/angjelkom/flutter_netflix), di Jack, con licenza MIT.
La presentazione Nyanime è implementata in Kotlin e Compose; marchio e icona
sono descritti nella [guida UI](nyanime-ui.md).

Le copertine mostrate durante l'uso provengono dai cataloghi o dalle estensioni.
Il repository pubblico usa il logo locale Nyanime e non distribuisce
artwork delle integrazioni private. I progetti upstream e i servizi citati non
sono canali di assistenza per le personalizzazioni Nyanime.
