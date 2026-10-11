<div align="center">
  <img src=".github/assets/nyanime.svg" alt="Logo Nyanime: N rossa su sfondo nero" width="112" height="112" />
  <h1>Nyanime</h1>
  <p><strong>Scopri. Guarda. Leggi. Insieme.</strong></p>
  <p>La tua libreria video e manga su Android, con un'interfaccia cinematografica,<br />
  Anime4K, Cast e visione sincronizzata con gli amici.</p>
</div>

Questa repository pubblica distribuisce APK firmati, changelog e documentazione.
Il codice e la sua cronologia completa sono conservati nel progetto privato;
gli aggiornamenti OTA restano disponibili anche alle installazioni precedenti.

**Nyanime** è un fork di AniYomi che aggiunge una nuova esperienza video e conserva
le funzioni di gestione e lettura manga. La ModernUI offre un aspetto scuro con
marchio rosso e uno chiaro con accenti Arancio solare, oppure segue il sistema.
Al primo avvio viene chiesto quale usare; puoi cambiarlo in Impostazioni → Aspetto.
Lo sfondo del lettore manga resta configurabile indipendentemente.

[Sito Nyanime](https://owouwuiwi.github.io/) · [Funzionalità complete](docs/features.md) · [Primi passi e FAQ](docs/getting-started.md) ·
[Documentazione](docs/README.md) · [Novità](CHANGELOG.md) · [Contribuire](CONTRIBUTING.md)

## L'esperienza Nyanime

| Funzione | Cosa puoi fare |
| --- | --- |
| **ModernUI chiara e scura** | Sfoglia banner, locandine e caroselli con transizioni della copertina; scegli Scuro, Chiaro o Sistema. Anche il marchio e l’icona Android seguono il tema. |
| **Home ricche di contenuti** | Esplora cataloghi, stagioni, classifiche e calendario; usa le Home anime e manga definite dalle estensioni compatibili. |
| **Le tue uscite** | Segui anime e manga dalla scheda, ricevi avvisi delle nuove disponibilita e consulta le date annunciate nel calendario. [Come funziona](docs/release-monitor.md). AnimeSchedule facoltativo e widget: [configurazione guidata](docs/animeschedule.md). |
| **Riprendi da dove eri** | Ritrova “Continua a guardare” e “Continua a leggere”, con avanzamento locale e accesso all'episodio o capitolo. |
| **Nyanime Cloud** | Collega Telegram per conservare backup completi in un canale privato, scegliere le copie da mantenere e ripristinarle su un altro telefono. [Configurazione e limiti](docs/telegram-cloud.md). |
| **Anime4K Ultra offline** | Copie A+ HQ con carico dosato, pause per temperatura e ripresa dei segmenti salvati. Stato, comandi ed esportazione direttamente dagli episodi; coda in Download → Ultra. [Dettagli e requisiti](docs/anime4k-ultra.md). |
| **Anime4K Smart** | Applica shader nel player interno con qualità adattiva, avvio automatico configurabile e modalità manuali. |
| **Guarda insieme** | Genera un codice, invita gli amici e sincronizza episodio, play, pausa, spostamenti e velocità, ognuno con il proprio streaming. |
| **Leggi insieme** | Nella stessa stanza dei video, vedi manga, capitolo e pagina degli amici, raggiungili e torna al tuo punto. Schizzi e note condivisi convergono anche dopo una disconnessione; puoi rientrare nella stanza e ritrovare gli interventi temporanei. [Come funziona](docs/reading-together.md). |
| **Cast con telecomando** | Trasmetti a Google Cast, UPnP/DLNA o alle app Nyanime TV compatibili e continua a usare l'app dal telefono, con controlli e ripresa locale. |
| **Player curato** | Usa tracce audio e sottotitoli, gesti, PiP, AniSkip, timer di sospensione e passaggio al prossimo episodio. |
| **Manga completo** | Mantieni libreria e lettore legacy, con Home opzionali, aggiornamenti dei capitoli e tutte le modalità di lettura. |
| **Protezione laterale hardware** | Proteggi video, manga, schede e aree personali sui display compatibili. Comandi temporanei, modalità “Solo in incognito” e opzione NSFW basata sui metadati; [compatibilità e limiti](docs/privacy-display.md). |

Il [sync tra dispositivi](docs/personal-sync.md), profili, amicizie, feed, chat e presenza social sono
temporaneamente disattivati e non compaiono nell’app. Il codice e i dati locali
obsoleti vengono rimossi dall’APK e dal telefono: all’avvio vengono eliminati chiavi,
archivi privati, bozze e code delle funzioni disattivate. Nessuna connessione o raccolta di progressi per il sync viene avviata,
anche sui dispositivi configurati in precedenza. Le stanze video e manga funzionano senza un profilo:
si crea una stanza e si condivide il suo codice o link.
[Documentazione della community dormiente](docs/community-protocol.md).

## Tutte le aree dell'app

### Home, ricerca e scoperta

- Catalogo AniList con tendenze, stagioni, classifiche, ricerca, dettagli e calendario;
  passaggio a Kitsu quando il catalogo principale non è disponibile.
- Apertura degli episodi attraverso le estensioni installate, con associazione del
  titolo e della stagione e possibilità di correggere manualmente la versione.
- Home delle estensioni anime: sezioni, categorie video, filtri, date, banner,
  aggiornamenti e paginazione dichiarati dall'estensione.
- Home delle estensioni manga: novità, classifiche, titoli in evidenza, aggiornamenti
  e pulsanti per aprire i capitoli esatti.
- [Ricerca intelligente](docs/smart-title-search.md) nelle Home, in Sfoglia, nelle
  fonti e nelle librerie: refusi, punteggiatura e alias, con suggerimenti dinamici
  facoltativi e testo originale preservato. Nessun catalogo precompilato.
- “Continua a guardare” anche nelle Home Anime e Cartoni, titoli nascondibili e
  ripristinabili senza cancellare cronologia o avanzamento; “Continua a leggere”
  nelle Home manga compatibili.
- Copertine con segnaposto discreti, comparsa graduale, recupero delle immagini
  mancanti tramite l'estensione e nuovi tentativi limitati.

### Riproduzione video e Anime4K

- Player interno basato su mpv, ripresa della posizione, scelta di episodio,
  sorgente video e qualità; stagioni e host quando forniti dall'estensione.
- Velocità configurabile, proporzioni, orientamento, gesti di volume, luminosità,
  avanzamento e doppio tocco; controlli multimediali e Picture-in-Picture.
- Tracce audio, lingue preferite, ritardo audio, correzione del tono,
  configurazione dei canali e amplificazione del volume.
- Sottotitoli principali e secondari, tracce esterne, lingue e filtri di preferenza,
  ritardo, velocità, caratteri, dimensioni, colori, bordi e posizione.
- Decodifica hardware configurabile, debanding, filtri dell'immagine, statistiche,
  configurazione e script mpv, pulsanti personalizzati e player esterno.
- Streaming torrent tramite l'integrazione esistente, quando supportato dalla fonte.
- AniSkip facoltativo per sigle e segmenti disponibili, con salto manuale o automatico.
- Timer con durate rapide, durata personalizzata memorizzata, estensione di 15 minuti
  e arresto alla fine dell'episodio.
- Autoplay configurabile, scheda del prossimo episodio, conto alla rovescia,
  “Riproduci ora” e “Annulla”, coordinati con il timer.
- Il pulsante rapido **SM** rimane disponibile; i download già elaborati mostrano **ULTRA** in magenta.
- Anime4K con **Smart, Massimo, Personalizzato e Spento**, scelte per episodio,
  diagnostica e protezione dagli errori degli shader. L'avvio automatico di Smart
  è attivo di default e disattivabile.

### Guarda insieme e Cast

Le app TV possono diventare ricevitori tramite [Nyanime Companion](docs/companion-protocol.md): ricerca automatica o indirizzo manuale, codice di conferma e comandi cifrati. L’app LG webOS conserva anche il funzionamento autonomo.

- Stanze fino a **8 persone**, creazione o ingresso tramite codice, invito con link e QR.
- La stessa stanza continua nel lettore manga: avanzamento indipendente, salto esplicito
  alla pagina di un amico, segnalibro di ritorno locale e schizzi temporanei condivisi.
- L'host sceglie l'episodio; gli ospiti lo aprono con la propria estensione compatibile.
  Play, pausa, avanzamento e velocità sono condivisi; l'host può limitare i controlli.
- Attesa dei partecipanti pronti, ripartenza con conto alla rovescia, correzione
  graduale degli scostamenti, gestione del buffering e riconnessione.
- Indicazione di chi sta caricando e di chi ha usato i controlli, salti coordinati
  e preparazione del prossimo episodio.
- Barra della stanza durante la navigazione, ritorno rapido al player e pausa
  protetta quando scade il timer o il telefono perde il focus audio.
- Stanze cifrate tramite relay Nostr: nessun account Nyanime e nessuna apertura
  di porte; i video, i cookie e gli indirizzi degli streaming non sono condivisi.
- Google Cast, UPnP/DLNA e Nyanime Companion sulla rete locale, con play, pausa, avanzamento,
  episodio precedente/successivo, volume, notifica e telecomando persistente.
- Cambio del video mentre si naviga nell'app, ripresa sul telefono e luminosità
  remota sui ricevitori DLNA che la espongono.

### Manga, librerie e lettura

- Librerie anime e manga con categorie, ricerca, filtri, ordinamenti, selezione
  multipla, copertine personalizzate, cronologia e aggiornamenti.
- Gestione di episodi e capitoli visti/letti, segnalibri, filtri e ordine crescente
  o decrescente; migrazione verso un'altra fonte.
- Lettura da sinistra a destra, da destra a sinistra, verticale, webtoon e continua.
- Zoom, ritaglio dei bordi, gestione delle pagine doppie, orientamento,
  margini webtoon, luminosità, filtri colore e navigazione con i tasti volume.
- Avanzamento e impostazioni di lettura per titolo, salto dei capitoli già letti,
  filtrati o duplicati, salvataggio e condivisione delle pagine.
- Video locali, cartelle di immagini, archivi manga e immagini negli EPUB;
  nessuna estensione necessaria per i file locali supportati.

### Download, tracker, dati e personalizzazione

- Download di episodi e capitoli, code, limite di velocità e concorrenza,
  modalità solo Wi-Fi e **Solo scaricati**.
- Download automatici delle novità o durante lettura/visione, regole per categoria,
  pulizia dei contenuti già consumati e salvataggio dei capitoli in CBZ.
- Ripresa dei download HTTP compatibili, controllo dello spazio e recupero
  dei collegamenti scaduti attraverso l'estensione; downloader esterni facoltativi.
- Tracker **MyAnimeList, AniList, Kitsu, MangaUpdates, Shikimori, Simkl e Bangumi**;
  integrazioni con **Komga, Kavita, Suwayomi e Jellyfin** nei casi supportati.
  Collegamento automatico quando inizi a guardare o leggere e il titolo è
  identificato senza ambiguità; la ricerca manuale rimane disponibile.
- Backup manuali e automatici, ripristino, esportazione degli elenchi della libreria,
  scelta dell'archiviazione e gestione delle cache.
- Gestione di repository, estensioni, lingue e fiducia automatica configurabile;
  notifiche di aggiornamento separate e senza ripetere la stessa versione.
- Risparmio dati delle immagini, DNS-over-HTTPS, strumenti diagnostici,
  manutenzione delle cache e installatore delle estensioni configurabile.
- Modalità incognito, blocco biometrico, protezione delle schermate e contenuto
  delle notifiche configurabile.
- Temi, modalità chiara/scura, AMOLED, tema manga separato, navigazione
  personalizzabile, layout adattivi e opzioni per ridurre il movimento.

Le condizioni e le opzioni sono descritte nel [catalogo completo delle funzionalità](docs/features.md).

## Installazione

Serve **Android 8.0 o successivo**. Installa l'APK Nyanime ricevuto dal manutentore
o compilato da questo repository; non è necessario un account Nyanime.
Non viene indicato un canale pubblico di download che potrebbe non essere disponibile.

Le build producono varianti per `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`
e universale. Il file deve essere compatibile con il dispositivo; per aggiornare
una copia esistente devono corrispondere anche package e firma.

La [guida iniziale](docs/getting-started.md) spiega installazione, estensioni,
backup, preferenze principali e problemi comuni.

## Da sapere

- L'app non include né ospita contenuti. Le estensioni forniscono accesso e logica
  delle fonti; quelle con Home compatibile determinano dati e sezioni visualizzati.
- **Guarda insieme usa server relay esterni**: è decentralizzato, ma non è P2P puro.
  Ogni partecipante deve poter aprire il contenuto autonomamente; qualità della
  rete ed edizione del video incidono sulla sincronizzazione.
- **Anime4K è disabilitato nelle stanze** e non viene applicato ai video via Cast.
  Al ritorno alla visione locale fuori dalla stanza si recupera la scelta prevista.
- Il Cast richiede un ricevitore compatibile sulla stessa rete. Codec, sottotitoli
  e luminosità dipendono dal ricevitore; non sono disponibili transcodifica,
  mirroring dello schermo o un ricevitore Fire TV dedicato.
- Un backup conserva dati e preferenze selezionati: **non contiene i video o
  le pagine scaricati**. Incognito non rende anonime le connessioni di rete.
- Una funzione e i suoi test automatici non garantiscono compatibilità
  con ogni fonte, dispositivo o rete.

## Documentazione e contributi

Parti dall'[indice delle guide](docs/README.md). Per segnalazioni e modifiche usa
gli strumenti di **questo repository**, se hai accesso, seguendo
[CONTRIBUTING.md](CONTRIBUTING.md) e il [codice di condotta](CODE_OF_CONDUCT.md).
Le personalizzazioni Nyanime non sono gestite dall'assistenza dei progetti upstream.

I materiali delle integrazioni private restano fuori dal repository dell'app:
la documentazione e i test usano esempi generici.

## Crediti e licenze

Nyanime deriva da [AniYomi](https://github.com/aniyomiorg/aniyomi) e conserva il
lavoro di [Mihon](https://github.com/mihonapp/mihon), Tachiyomi e dei loro contributori.
Usa mpv e la relativa integrazione Android, FFmpeg, gli shader Anime4K e altre
librerie con le rispettive licenze. Il riferimento visivo del restyling è indicato
nella [guida all'interfaccia](docs/nyanime-ui.md).

Il codice ereditato mantiene [Apache License 2.0](LICENSE) e le attribuzioni
originali. Sono disponibili [i crediti dei componenti](docs/credits.md), la documentazione del
[demuxer nativo](tools/native/README.md) e le licenze incluse nell'app.
