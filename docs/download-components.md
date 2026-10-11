# Componenti del motore di download

Le code, i database, le notifiche e i contratti delle estensioni restano quelli
di Nyanime. Le richieste usano il client dell'estensione, compresi cookie,
intercettori e limiti. Il motore non contiene estrattori o regole per siti.

## Codice adattato e attribuzioni

- **Downpour**, Alireza Javan, Apache-2.0. `ConnectionTuner` in
  `TransferCoordinator.kt` adatta il controllo del beneficio della concorrenza.
  Revisione `1dc14db51697d275789a8e82d32f90e73a040fa6`,
  [file originale](https://github.com/AlirezaJavan/Downpour/blob/1dc14db51697d275789a8e82d32f90e73a040fa6/downloader/src/main/kotlin/io/github/alirezajavan/downpour/internal/engine/ConnectionTuner.kt).
  Modifiche: limite 4, campioni aggregati per fonte, riduzione su errori e
  cooldown che non può essere annullato da un campione di velocità. Campionamento
  ogni secondo; dopo un plateau si riprova l'aumento soltanto dopo 60 secondi.
  [Licenza inclusa](../vendor/Downpour-LICENSE).
- **Fetch**, Tony Francis, Apache-2.0. `RecentAverage` in `TransferRuntime.kt`
  adatta la media ponderata dei campioni recenti di `AverageCalculator`.
  Revisione `11c2c45f6be1cfe034fb2b32dea7c14660ff6961`,
  [file originale](https://github.com/tonyofrancis/Fetch/blob/11c2c45f6be1cfe034fb2b32dea7c14660ff6961/fetch2core/src/main/java/com/tonyodev/fetch2core/AverageCalculator.kt).
  Modifiche: anello di 8 campioni a memoria fissa, tempo monotono,
  aggiornamenti limitati a 4 al secondo, nessuna crescita dell'array.
  [Licenza inclusa](../vendor/Fetch-LICENSE).

## Librerie riutilizzate

- **AndroidX Media3 1.8.0**, Android Open Source Project, Apache-2.0:
  `media3-exoplayer-hls`, parser delle playlist HLS. Si conservano i tag del
  manifest; il risultato resta un file apribile dal player e non una cache
  specifica di ExoPlayer. [Sorgente](https://github.com/androidx/media/tree/1.8.0).
- **OkHttp 5.4.0**, Apache-2.0: client già integrato, con MockWebServer per
  le prove di trasferimento. [Sorgente](https://github.com/lysine-dev/okhttp).
- **libarchive Android**, versione già configurata nel catalogo Gradle,
  Apache-2.0 per il wrapper: creazione CBZ senza compressione, mantenendo
  i byte originali delle immagini. [Sorgente](https://github.com/zhanghai/libarchive-android).
- **FFmpegKit**, distribuzione già integrata: assemblaggio locale dei flussi
  con copia dei codec, senza ricodifica. Rimane il percorso precedente per
  opzioni delle estensioni non compatibili con l'accelerazione.

## Integrità e ripresa

HTTP parallelo richiede intervalli esatti, un ETag forte e almeno 16 MiB.
Il registro versione 2 salva gli hash SHA-256 dei blocchi completati; i blocchi
incompleti o alterati vengono riscaricati. I parziali sequenziali precedenti
continuano a utilizzare il loro metodo di ripresa.

Gli URL HTTP senza estensione vengono identificati con una lettura limitata
a 64 byte, senza affidarsi soltanto al Content-Type. I client, gli header e
le opzioni fornite dalle estensioni vengono conservati.

HLS accelera playlist VOD terminate, segmenti, mappe di inizializzazione e
chiavi AES-128. Playlist live, DRM, byte range HLS e opzioni personalizzate
mantengono il percorso precedente. Audio e sottotitoli separati compatibili
sono conservati. Il registro dei segmenti verifica hash, lunghezza e un ETag
riconfermato dal server: un file soltanto non vuoto non è una prova di integrità.

I download manuali usano UIDT su Android 14+ quando l'app è visibile; le
versioni precedenti e le richieste automatiche usano WorkManager foreground.
Nessun nuovo elemento sostituisce un lavoro attivo. Le pause durante la visione
sono distinte dalle pause manuali e dall'attesa della rete.

Anime e manga conservano le rispettive code in namespace distinti. Le vecchie
voci numeriche vengono migrate senza cancellare l'altra coda. Gli elementi
con un'estensione non ancora disponibile rimangono salvati e vengono recuperati
quando la fonte diventa pronta. Il ripristino non blocca il thread della UI.

FFmpeg scrive tramite il protocollo `fd:` e il descrittore aperto da Android,
mantenuto vivo fino al callback finale della sessione nativa. Non riapre il
percorso tramite `/proc` e non richiede il protocollo SAF opzionale della
distribuzione FFmpeg. Conserva il seek nel file senza una seconda copia locale.
[Protocollo FD](https://ffmpeg.org/ffmpeg-protocols.html#fd).
La cancellazione non può pubblicare un file incompleto tramite un callback tardivo.

Le prove e le misure devono distinguere preparazione, rete e finalizzazione.
Un’accelerazione osservata su un server che limita ogni connessione non implica
la stessa accelerazione su qualsiasi server o connessione.

## Misure su dispositivo reale — 5 ottobre 2026

Galaxy Z Flip6, Android 16, APK preview ottimizzato con R8. Tre prove per
percorso, alternate tra sequenziale precedente e accelerato, con file originali
uguali. MockWebServer sul dispositivo: 80 ms di latenza iniziale e 512 KiB/s per
connessione. Il limite per connessione è intenzionale, per verificare il beneficio
del parallelismo; non riproduce le condizioni di ogni estensione o rete.

| Percorso | Tempi precedenti (s) | Tempi nuovi (s) | Mediana precedente → nuova |
| --- | --- | --- | --- |
| HTTP, 16 MiB | 32,543 / 32,514 / 32,535 | 10,143 / 10,149 / 10,143 | 32,535 → 10,143; 3,21× |
| HLS, video + audio + sottotitoli | 11,602 / 11,598 / 11,588 | 6,529 / 6,508 / 6,459 | 11,598 → 6,508; 1,78× |
| Manga, 24 JPEG e CBZ senza compressione | 16,545 / 16,537 / 16,522 | 9,677 / 9,669 / 9,632 | 16,537 → 9,669; 1,71× |

HTTP confrontato mediante il vecchio `ResumableVideoTransfer` e il nuovo motore,
su `UriPartialVideoStore`. Il contenuto HTTP è sintetico: integrità verificata
mediante SHA-256, non apertura come video. HLS confrontato con FFprobe remoto e
FFmpeg remoto del percorso precedente: il nuovo assemblaggio locale dura 87–105 ms;
FFprobe verifica le tre tracce nel file offline. I contenitori possono differire
per metadati; entrambi usano copia dei codec. Per i manga la prova precedente
riproduce due trasferimenti, scansione per pagina e buffer da 8 KiB: tutte le
24 immagini estratte dai CBZ mantengono l’hash originale.

La temperatura della batteria ai checkpoint è rimasta a 30,5 °C. Il PSS include
il server di prova e i suoi buffer: 105–157 MiB circa. Non è una misura isolata della memoria del
download in produzione, né dimostra una riduzione del consumo o della temperatura.

È stato verificato anche un capitolo scaricato dall'interfaccia normale: 36 pagine
e ComicInfo, CBZ senza compressione di 198.381.509 byte. Controllo CRC completo
senza errori, stato Offline nella scheda e apertura nel lettore con Wi-Fi e dati
mobili disattivati. Il test non costituisce un confronto di velocità con la
versione precedente su quella fonte. Rete e modalità incognito sono state
ripristinate al termine della prova.

Nelle verifiche dall'interfaccia normale sono stati completati anche
due download video: ripresa di un parziale preesistente e trasferimento nuovo
con registro dei blocchi. Il nuovo MP4 è stato aperto con Wi-Fi e dati mobili
disattivati: durata 23:40, riproduzione osservata da 00:01 a 00:13.

La coda è rimasta ferma durante la visione e in PiP anche con rete disponibile,
con gli stessi blocchi parziali; chiudendo il player il trasferimento è ripartito.
Una pausa manuale è invece rimasta attiva dopo l'uscita dal player. Il download
parziale è stato poi ripreso e completato dopo il riavvio dell'app. Queste prove
verificano i percorsi funzionali, senza attribuire una velocità universale al motore.

La sonda è opt-in: `-PdownloadProbe=true` abilita runner e regole R8 esclusivamente
di prova. L’APK normale non contiene il runner. Esecuzione: `am instrument -w -r`
con `xyz.jmir.tachiyomi.mi.anime4k.debug.test/eu.kanade.tachiyomi.download.DownloadDeviceProbe`.
`-e checksOnly true` verifica AES-128, discontinuità, ripresa dei segmenti e una
cartella SAF se l’app dispone già di un’autorizzazione scrivibile.
`-e cleanup true` rimuove soltanto i file della sonda nella cache dell’app.
`-e nativeFd true` verifica scrittura e lettura nativa tramite un descrittore SAF,
usando il video sintetico cifrato già generato dalla sonda; non scarica altri titoli.
