# Crediti e licenze

Nyanime è un fork di AniYomi. Il nuovo nome identifica questa distribuzione e
le sue personalizzazioni; non cambia la provenienza del codice mantenuto.

## App e progetti upstream

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

La licenza del codice dell'app è [Apache License 2.0](../LICENSE).
Lo [storico AniYomi](history/aniyomi-changelog.md) rimane archiviato separatamente.

## Componenti e servizi

| Componente | Impiego e provenienza |
| --- | --- |
| mpv e integrazione Android | Player interno; dipendenza `aniyomi-mpv-lib` dichiarata nel [catalogo Gradle](../gradle/aniyomi.versions.toml). |
| FFmpeg / FFmpegKit | Demuxing e media; la build locale include un backport HLS. [Sorgenti, patch, configurazione e licenze](../tools/native/README.md). |
| Anime4K | Shader ufficiali inclusi: [descrizione degli asset](../app/src/main/assets/anime4k/README.txt) e [licenza](../app/src/main/assets/anime4k/LICENSE). |
| Nostr | Protocollo di trasporto delle stanze; [implementazione e riferimenti](watch-together.md). |
| ACINQ secp256k1-kmp | Firme delle identità effimere delle stanze; riferimenti nella stessa guida. |
| ZXing | Generazione locale dei QR d'invito; riferimenti nella guida delle stanze. |
| Google Cast e UPnP/DLNA | SDK e protocolli per i ricevitori; [integrazione Cast](casting.md). |
| AniSkip | Dati facoltativi per il salto dei segmenti; [integrazione](aniskip.md). |
| AniList e Kitsu | Metadati e cataloghi, distinti dalle fonti di riproduzione; [scoperta](discovery-home.md). |
| AniList, TVmaze e Wikidata | Approfondimenti pubblici delle opere: AniList API, TVmaze CC BY-SA e Wikidata CC0; [attribuzioni e condizioni](work-information.md). |
| MIT KEMAR HRTF | Cinema in cuffia: dati di Bill Gardner e Keith Martin, MIT Media Laboratory ©1994, uso libero con attribuzione; [licenza e integrazione](headphone-cinema.md). |
| SymSpellKt 3.4.0 | Correzione lessicale con dizionario costruito durante l’uso; MIT, Adam Brown, Lucky Sharma e Wolf Garbe. [Integrazione e avvisi completi](smart-title-search.md#licenza-della-dipendenza). |
| FlexibleAdapter | Artefatto upstream conservato senza modifiche nel [mirror vincolato](../vendor/README.md), con la [licenza](../vendor/FlexibleAdapter-LICENSE). |
| Downpour e Fetch | Controllo delle connessioni e media della velocità adattati, Apache-2.0; [revisioni, modifiche e licenze](download-components.md). |
| AndroidX Media3, OkHttp e libarchive Android | Parser HLS, rete delle estensioni e archivi CBZ; [integrazione](download-components.md). |

L'elenco orienta tra i componenti principali; non sostituisce l'elenco completo
delle dipendenze nei cataloghi Gradle e delle licenze incluso nell'app.
Le licenze delle dipendenze rimangono applicabili: la licenza dell'app non
riclassifica quelle dei componenti nativi o dei relativi sorgenti.

## Identità visiva e immagini

Il riferimento visivo è
[flutter_netflix](https://github.com/angjelkom/flutter_netflix), di Jack, con licenza MIT.
La presentazione Nyanime è implementata in Kotlin e Compose; marchio e icona
sono descritti nella [guida UI](nyanime-ui.md).

Le copertine mostrate durante l'uso provengono dai cataloghi o dalle estensioni.
Il repository della documentazione usa il logo locale Nyanime e non distribuisce
artwork delle integrazioni private. I progetti upstream e i servizi citati non
sono canali di assistenza per le personalizzazioni Nyanime.
