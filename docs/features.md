# Tutte le funzionalità di Nyanime

Questo catalogo comprende le aggiunte di Nyanime e le funzioni mantenute da AniYomi.
Descrive funzionalità utilizzabili e relative condizioni, non sviluppi futuri.
I nomi dei comandi possono variare con la lingua scelta.

## Interfaccia e navigazione

**ModernUI** offre un aspetto scuro con accenti rossi e uno chiaro con accenti
Arancio solare, oppure segue il sistema. Il marchio e l’icona Android seguono
la scelta, mentre i loghi delle fonti mantengono i colori originali. Banner,
caroselli e locandine presentano i titoli. La copertina accompagna l'apertura e il ritorno
dai dettagli; ombre, testi, pannelli e controlli hanno transizioni coordinate.
Le immagini usano segnaposto e comparsa graduale, conservando i contenuti disponibili
durante gli aggiornamenti.

Il selettore delle categorie è centrato quando c'è spazio e scorre sui display
stretti. Griglie, testi e pannelli si adattano all'orientamento e ai caratteri grandi.
L'indicatore del gesto di aggiornamento appare durante il trascinamento; i caricamenti
automatici delle Home video non lo fanno comparire durante la navigazione.

Il primo avvio dopo l’introduzione del tema chiaro presenta una scelta dedicata:
**Scuro, Chiaro o Segui il sistema**. Dopo la conferma non viene riproposta negli
aggiornamenti successivi; puoi cambiare tema in **Impostazioni > Aspetto**.
La modalità AMOLED si applica soltanto all’aspetto scuro. Lo sfondo del lettore
manga resta configurabile separatamente. Le animazioni temporizzate rispettano la scala di sistema; il player
offre anche la riduzione del movimento.


## Notizie facoltative

Le estensioni Notizie si abilitano in **Altro → Sfoglia → Notizie**. La categoria
compare nella Home solo dopo averne abilitata una; ogni APK richiede la conferma
locale della firma. Non vengono installate fonti automaticamente.

**Ultime**, **Per te** e **Salvati** offrono articoli compatti, filtri e un lettore
nativo con dimensione del testo, immagini ingrandibili e collegamento all'originale.
Da questa Home, **Cerca** cerca articoli; le fonti prive di ricerca usano gli
articoli già disponibili. Il testo salvato funziona offline, le immagini dipendono
dalla cache. Backup completi includono preferenze e articoli salvati.

Gli interessi partono dalla libreria e dai titoli seguiti: gli abbinamenti automatici
usano ID verificati, quelli incerti richiedono una scelta. Gli avvisi sono spenti
inizialmente e configurabili per fonte; quando attivi, il controllo è ogni ora e
Android può ritardarlo. Le notizie restano separate dagli avvisi di episodi/capitoli.
[Contratto e limiti](news-extensions.md).

## Catalogo video e ricerca

| Area | Funzioni |
| --- | --- |
| Scoperta | Tendenze, stagioni, classifiche, ricerca, dettagli, immagini e collegamenti ai titoli. |
| Calendario | Uscite nel fuso orario locale, selezione delle date e navigazione dei giorni. La messa in onda non garantisce disponibilità nella fonte. |
| Cataloghi | AniList come catalogo principale; Kitsu per i feed supportati quando il principale non è disponibile. Il calendario dettagliato non ha un equivalente Kitsu. |
| Apertura | Ricerca di titolo e stagione nelle estensioni abilitate, riutilizzo delle associazioni valide, verifica degli episodi e possibilità di cambiare versione. |
| Ricerca globale | Anime e manga, richieste concorrenti limitate, annullamento delle ricerche superate, errori e tentativi per singola fonte. |
| Continuità | Cache dei feed video, contenuti mantenuti durante il refresh, paginazione e nuovi tentativi per sezione. |

L'associazione automatica non installa estensioni né aggiunge tracker. Quando le
edizioni sono ambigue puoi cercare nelle fonti o cambiare associazione.
In incognito non viene salvata una nuova associazione persistente.

## Informazioni sulle opere

Le schede possono essere completate con informazioni pubbliche da **AniList**
per anime e manga, **TVmaze** per serie televisive e **Wikidata** per film.
L'attivazione è facoltativa e non richiede account o chiavi; i dati della fonte
restano prioritari. I Dettagli mostrano le informazioni disponibili su autori,
cast, doppiatori, struttura e opere collegate. Persone e opere hanno pagine native;
la ricerca nelle fonti conserva l'accesso ai contenuti riproducibili.

Le associazioni ambigue richiedono una scelta e possono essere corrette o rimosse.
La cache funziona offline e in Solo scaricati; in incognito non vengono salvate
nuove associazioni. Consenso e scelte manuali seguono i backup delle impostazioni.
La disponibilità dei dati varia per catalogo, soprattutto per i film. I collegamenti
IMDb sono mostrati soltanto per identificatori verificati. [Guida e attribuzioni](work-information.md).

## Home delle estensioni

L'estensione decide i dati, i nomi e l'ordine delle sezioni, le categorie e i filtri.
L'app interpreta un contratto generico: non include parser di siti o cataloghi privati.
La Home compare solo quando l'estensione è installata, attendibile, abilitata,
inizializzata e dichiara la capacità necessaria.

| Anime e video | Manga |
| --- | --- |
| Banner, locandine, aggiornamenti, classifiche e calendario secondo i dati forniti. | Novità, titoli in evidenza, classifiche, ultimi capitoli e aggiornamenti. |
| Categorie come anime, film, cartoni o serie, se dichiarate. | Badge, dettagli, posizioni in classifica, date e più azioni capitolo. |
| Ricerca, filtri, archivi, sezioni complete e paginazione. | Ricerca, filtri e collegamenti all'archivio attraverso il catalogo della fonte. |
| Ripresa locale indipendente dal caricamento dei feed. | Apertura del capitolo esatto, sincronizzando l'elenco completo se necessario. |
| Recupero delle copertine visibili tramite l'estensione. | Distinzione tra evento della Home, titolo in libreria e avanzamento. |

La scheda Manga offre **Home** e **Biblioteca** quando una Home compatibile è
disponibile; altrimenti apre la biblioteca esistente. Non cambia il lettore né
sostituisce copertine personalizzate e progressi della libreria.

## Continua a guardare e continua a leggere

La ripresa usa la cronologia locale e l'ordine degli episodi o capitoli. La riga video
è disponibile nelle Home Anime e Cartoni e non deve attendere il catalogo remoto.
Nelle Home manga compatibili trovi la ripresa della lettura.

Puoi nascondere un titolo da “Continua a guardare”, annullare la scelta o ripristinare
tutti i titoli nascosti. Questo non cancella cronologia, segnalibri o episodi visti.
**Solo scaricati** limita la ripresa ai contenuti disponibili sul dispositivo;
l'incognito nasconde la cronologia personale nelle Home.

## Player video

| Area | Controlli disponibili |
| --- | --- |
| Riproduzione | Play/pausa, ricerca temporale, ripresa della posizione, episodio precedente/successivo, elenco episodi, velocità e preset. |
| Video e sorgenti | Qualità, host e stagioni esposti dalla fonte, tracce video, proporzioni, orientamento e schermo intero. |
| Gesti | Volume/luminosità, avanzamento, doppio tocco configurabile, comandi multimediali e blocco dei controlli. |
| Audio | Tracce e lingue preferite, ritardo, canali, correzione del tono con variazione di velocità e amplificazione configurabile. |
| Sottotitoli | Traccia principale/secondaria, file esterni, lingue, liste di inclusione/esclusione, ritardo, velocità, font, dimensioni, bordi, colori e posizione. |
| Immagine | Decodifica hardware configurabile, renderer, debanding, luminosità del video, contrasto, saturazione, gamma e tonalità. |
| Multitasking | Picture-in-Picture e relativi controlli, secondo le capacità e le impostazioni Android. |
| Strumenti | Cattura di immagini con sottotitoli facoltativi, statistiche, configurazione e script mpv, comandi e pulsanti personalizzati. |
| Alternative | Player esterno configurabile e streaming torrent tramite l'integrazione esistente, dove supportato. |

Le anteprime durante lo spostamento sulla timeline e i titoli correlati sono disponibili
quando i dati necessari vengono forniti dall'estensione o dal contenuto.

Il lettore interno usa mpv e FFmpeg. I collegamenti vengono risolti dall'estensione;
codec, qualità e tracce dipendono dal contenuto. Il percorso HLS include la
correzione nativa per ripresa e spostamenti nei flussi frammentati descritta nella
[guida FFmpeg](../tools/native/README.md). Le stanze inattive non interrogano
né modificano il player nativo.

### Anime4K

| Modalità | Comportamento |
| --- | --- |
| **Smart (SM)** | Parte dal preset compatibile più alto e riduce il carico quando le misure di rendering indicano sovraccarico. |
| **Massimo** | Selezione manuale della qualità compatibile più alta. |
| **Personalizzato** | Scelta manuale dei preset disponibili. |
| **Spento** | Disattivazione degli shader Anime4K. |

L'avvio automatico di Smart è **attivo di default** e disattivabile senza perdere
l'accesso manuale a SM. Le scelte per episodio sono conservate durante cambi qualità
e recupero dello stream. È disponibile una diagnostica del preset e delle misure.

Il controller gestisce finestre di misura dopo seek, pause e buffering, esclude
gli shader falliti e usa fallback limitati. Verifica gli shader inclusi e conserva
gli shader personalizzati che non appartengono ad Anime4K.

Gli shader ufficiali Anime4K 4.0.1 sono inclusi senza modificarne i pesi.
Smart seleziona e gestisce i preset: non classifica visivamente il contenuto e non
garantisce un miglioramento per ogni video. Il carico dipende da GPU e risoluzione.

**Anime4K è indisponibile per tutta la permanenza in una stanza**, comprese pause
e riconnessioni. Uscendo si ripristina la scelta della visione individuale.
Gli effetti locali di mpv non vengono applicati al video via Cast.

### Passaggio tra anime e manga

La scheda anime può mostrare i manga collegati tramite ID di catalogo, anche
attraverso la novel originale. Le copie disponibili nelle estensioni compatibili
vengono aperte dopo verifica degli ID; i checkpoint documentati indicano inizio,
fine e, quando esplicito, un capitolo di ripresa. Non vengono inventate
corrispondenze episodio/capitolo. La scheda manga offre anche il percorso inverso,
con scelta degli adattamenti e apertura delle copie verificate nelle estensioni.
Vedi [Passaggio tra anime e manga](anime-manga-continuity.md).

### AniSkip, timer e fine episodio

AniSkip è facoltativo: usa l'identificativo del titolo quando è disponibile
dall'estensione, da un tracker collegato oppure da una ricerca univoca nel catalogo.
Quando il servizio dispone degli intervalli, il player può proporre o eseguire
salti di sigla, conclusione e altri segmenti riconosciuti. Se i dati mancano,
la riproduzione normale prosegue.

Il timer offre **15, 30, 45, 60, 90 e 120 minuti**, una durata personalizzata da
**1 a 1439 minuti**, memoria dell'ultima durata personalizzata e **Alla fine
dell'episodio**. Un timer a tempo attivo mostra il residuo e consente di aggiungere
15 minuti, cambiare durata o annullare. La scadenza usa il tempo trascorso reale,
anche durante una pausa; ferma la riproduzione e non simula il tasto di accensione.

L'autoplay è configurabile. La scheda di fine episodio propone il successivo con
conto alla rovescia, riproduzione immediata e annullamento. La scadenza del timer
ha priorità sul cambio automatico. Il timer “Alla fine dell'episodio” riguarda
l'episodio selezionato; scegliendone un altro manualmente quella modalità viene rimossa.

## Guarda insieme

1. Apri **Guarda insieme** dalla Home o dal menu del player.
2. Crea una stanza e condividi il codice, il link d'invito o il QR.
3. Gli amici entrano e l'host sceglie titolo ed episodio.
4. Ogni telefono apre il proprio streaming; la stanza coordina i controlli.

Sono supportati fino a **otto partecipanti, host incluso**. Per l'apertura automatica
gli ospiti devono avere la stessa estensione utilizzabile e accesso alla stessa
edizione. Una fonte mancante o una durata incompatibile produce un'indicazione
e un'azione di recupero.

- Episodio scelto dall'host, play/pausa, spostamenti e velocità condivisi.
- Opzioni dell'host per limitare i controlli e attendere tutti i partecipanti.
- Attesa della disponibilità, conto alla rovescia comune e pausa coordinata
  durante il buffering.
- Correzioni graduali della velocità entro il 3% e riallineamento per scostamenti
  persistenti; i messaggi vecchi non ripristinano posizioni superate.
- Indicatore centrale animato play/cuore/cerchio, nomi e iniziali dei partecipanti
  ancora in caricamento, indicazione breve di chi ha usato un controllo.
- Errori e nuovi tentativi per richieste non confermate, riconnessione e protezione
  dai comandi riferiti a un episodio precedente.
- Salti AniSkip coordinati e preparazione del riferimento al prossimo episodio,
  senza scaricare preventivamente il video.
- Scheda e conto alla rovescia del prossimo episodio condivisi, con annullamento.
- Barra della stanza durante la navigazione con episodio, numero dei partecipanti,
  ritorno al player e accesso ai dettagli.
- Servizio e notifica per mantenere la connessione mentre si usa l'app,
  con uscita esplicita dalla stanza.
- Timer scaduto, perdita del focus audio e passaggio in background creano una
  pausa locale protetta: il Play remoto non la rimuove senza una ripresa locale.

Il trasporto usa **relay Nostr esterni e messaggi cifrati**. Non serve un account
Nyanime, un nostro backend o l'apertura di porte sul router; resta necessaria
una connessione ai relay. Non è P2P puro e non trasferisce il video tra amici.
I relay possono osservare indirizzi IP, tempi e dimensioni dei messaggi, ma non
leggere i dati cifrati della stanza. Il codice è una credenziale d'accesso:
condividerlo significa invitare chi lo riceve.

La stanza scade dopo 24 ore; l'app non conserva le sue chiavi dopo la chiusura.
Se l'host scompare, gli ospiti attendono e tentano di riconnettersi: non è previsto
il passaggio automatico del ruolo. La sincronizzazione non è garantita al singolo
fotogramma. Tutti devono usare una versione con protocollo compatibile.

## Cast e telecomando

Google Cast e UPnP/DLNA permettono di scegliere un ricevitore sulla stessa rete
locale. Il telefono controlla la riproduzione e può continuare a navigare nell'app.

| Operazione | Disponibilità |
| --- | --- |
| Play, pausa, posizione e volume | Secondo i controlli del ricevitore. |
| Episodio precedente/successivo | Con la coda del titolo selezionato. |
| Cambio del video dalla libreria o Home | Sostituisce la selezione remota conservando la sessione Cast. |
| Minicontroller, telecomando e notifica | Disponibili mentre si usa l'app; il servizio mantiene la sessione. |
| Continua sul telefono | Ferma il video remoto e riapre l'episodio alla posizione disponibile. |
| Luminosità del filmato | Solo DLNA con supporto verificato di lettura/scrittura; non con il ricevitore Google predefinito. |
| Sottotitoli | WebVTT esterni su Google Cast; disponibilità e formati DLNA dipendono dal ricevitore. |

Il relay locale rende raggiungibili file supportati e stream HLS/HTTP mantenendo
sul telefono gli header e i cookie della fonte. Il telefono deve restare
collegato alla rete e il servizio deve poter funzionare.

Non ci sono transcodifica, mirroring, shader remoti o supporto universale ai codec.
DASH e HLS con sostituzione di variabili non sono accettati da questo percorso Cast.
Non è incluso un ricevitore Fire TV. Reti ospiti o isolamento dei dispositivi
possono impedire scoperta e riproduzione.

## Lettore manga e file locali

La sezione manga conserva biblioteca, dettagli e lettore indipendenti dal restyling video.

- Lettura orizzontale in entrambe le direzioni, verticale, webtoon e continua.
- [Lettura guidata](guided-reading.md) facoltativa: riconoscimento offline delle
  vignette, focus animato, pagina intera ed editor delle geometrie. Attivazione
  ricordata per titolo, correzioni e punto guidato nei backup e nel Cloud.
- Modalità e orientamento per titolo, pagina corrente, passaggi tra capitoli,
  mantenimento della posizione e schermo acceso durante la lettura.
- Zoom e punto di partenza, ritaglio bordi, separazione/inversione delle pagine
  doppie, rotazione per adattarle allo schermo e margini webtoon.
- Temi, luminosità, filtro colore, scala di grigi e colori invertiti.
- Aree di tocco, doppio tocco, pressione prolungata, tasti volume e transizioni.
- Salto dei capitoli letti, filtrati o duplicati; segnalibri, salvataggio e condivisione
  delle pagine e copertina personalizzata.
- Opzioni di flash al cambio pagina per gli schermi che ne beneficiano.
- Cartelle di immagini, ZIP/CBZ, RAR/CBR, 7Z/CB7, TAR/CBT e immagini degli EPUB,
  oltre ai video locali nei formati supportati dal player.

I filtri colore non sono colorazione automatica delle tavole. I file locali usano
le cartelle e i metadati gestiti dalla fonte locale dell'app.

## Librerie, download e aggiornamenti

| Area | Funzioni |
| --- | --- |
| Organizzazione | Categorie anime e manga, ricerca, filtri, ordinamenti, griglie/elenchi, selezione multipla e copertine personalizzate. |
| Episodi e capitoli | Ordine crescente/decrescente, filtri per letti/visti, scaricati e segnalibri, marcature e avanzamento. |
| Cronologia | Ripresa, consultazione e gestione della cronologia video e manga. |
| Aggiornamenti | Controlli manuali e programmati della libreria, regole di inclusione/esclusione e download delle novità. |
| Migrazione | Trasferimento di un titolo verso una fonte diversa attraverso il flusso esistente. |
| Download | Code esistenti con connessioni adattive, accelerazione HTTP/HLS compatibile, concorrenza, limite di velocità condiviso, solo Wi-Fi, download automatici e downloader esterno facoltativo. |
| Archiviazione | Directory scelta dall'utente, capitoli in CBZ, divisione delle immagini alte, pulizia dopo lettura/visione e regole per categorie e segnalibri. |
| Offline | File locali e scaricati; Solo scaricati limita le Home e la ripresa ai dati disponibili. |
| Estensioni | Repository, cataloghi, installazione, aggiornamenti, lingue, abilitazione e gestione della fiducia. |

I video HTTP compatibili vengono divisi in blocchi con hash verificati e un
validatore forte della risorsa. Le playlist HLS VOD compatibili vengono scaricate
in parallelo e assemblate senza ricodifica, conservando audio e sottotitoli.
Formati e opzioni non supportati conservano il percorso precedente. La ripresa
verifica i parziali prima di riutilizzarli; non si uniscono contenuti differenti.
Il recupero dei link video scaduti passa dall'estensione e i tentativi sono limitati.

Manga e video condividono un massimo di quattro trasferimenti per fonte e otto
complessivi. I download interni si sospendono durante la visione, anche in PiP e
nelle stanze, e riprendono all'uscita dal player; una pausa manuale rimane tale.
Le schermate esistenti mostrano fase, byte, velocità e tempo restante quando
disponibili. Il completamento avviene soltanto dopo il salvataggio verificato.

Le impostazioni di libreria comprendono categorie predefinite, visualizzazione
per categoria, aggiornamento dei metadati e delle stagioni, restrizioni dei lavori
programmati, azioni di scorrimento su episodi/capitoli e marcatura dei duplicati.

I controlli automatici degli aggiornamenti delle estensioni sono separati per anime
e manga, limitati nel tempo e deduplicati per versione. Il refresh manuale resta
disponibile. L'aggiornamento dell'app dipende dall'accessibilità del canale
configurato; l'installazione manuale dell'APK resta utilizzabile.

## Strumenti avanzati e risparmio dati

- **Fiducia automatica nelle estensioni**, attiva di default e reversibile in
  Impostazioni > Avanzate. Carica le estensioni installate senza la conferma
  individuale; la modifica richiede il riavvio dell'app. Disattivandola tornano
  applicabili i consensi espliciti e il comando di revoca della fiducia.
- Installatore delle estensioni configurabile, inclusa l'integrazione Shizuku
  quando disponibile.
- DNS-over-HTTPS selezionabile, user agent configurabile e ripristinabile,
  pulizia di cookie e dati WebView.
- Esportazione dei log di arresto, informazioni diagnostiche, registrazione
  dettagliata facoltativa e accesso alle impostazioni delle notifiche.
- Aggiornamento delle copertine, ricostruzione della cache dei download e strumenti
  di manutenzione dei database anime e manga.
- Risparmio dati delle immagini, disattivato di default, tramite i servizi
  configurabili presenti nell'app: qualità, formato, esclusione JPEG/GIF,
  applicazione ai download e opzioni specifiche del servizio.

Le estensioni eseguono codice nell'app: la fiducia automatica è una preferenza di
caricamento, non una verifica del loro contenuto. Il risparmio dati che usa un
servizio esterno invia a quel servizio le richieste necessarie per elaborare le immagini;
disponibilità e compatibilità dipendono dal servizio scelto.

## Tracker, backup e privacy

I servizi di tracking integrati sono **MyAnimeList, AniList, Kitsu, MangaUpdates,
Shikimori, Simkl e Bangumi**. Per i server personali sono presenti integrazioni con
**Komga, Kavita, Suwayomi e Jellyfin**. Disponibilità per anime/manga e operazioni
sincronizzabili dipendono dal servizio. L'accesso a un tracker è facoltativo.
All'inizio della visione o lettura, i tracker configurati vengono associati in
background quando l'identità del titolo è sufficientemente certa. Edizioni o
stagioni ambigue non vengono collegate in modo arbitrario: resta disponibile
la ricerca manuale. La cronologia non viene inviata a servizi senza accesso.

Puoi creare o programmare backup, scegliere i dati da includere, ripristinarli,
esportare gli elenchi della libreria e gestire spazio e cache. I backup automatici
sono verificati prima della rotazione e gli errori vengono mostrati.
In Esporta dati, **Backup completo** salva librerie anime e manga, progressi,
cronologia, categorie, impostazioni, preferenze delle fonti e APK delle estensioni
installate in un file `.nyabk`. I vecchi file `.tachibk` restano importabili.
Il backup completo può contenere credenziali e token e non è cifrato: custodiscilo
in privato. Su un altro dispositivo Android può essere necessario autorizzare
di nuovo storage, servizi esterni e installazione delle estensioni.
**Backup dei dati e download dei contenuti sono operazioni diverse**: video e
pagine scaricati non vengono incorporati nel backup.

La modalità incognito limita la registrazione dell'attività e nasconde la ripresa
personale nelle Home. Sono disponibili blocco biometrico, blocco dopo inattività,
protezione delle schermate e occultamento del contenuto delle notifiche.
Incognito non nasconde l'indirizzo IP alle fonti o ai servizi usati.

## Verifiche e limiti

Le funzioni hanno verifiche mirate di logica, rendering Compose e build.
I test con relay reali sono facoltativi; le prove su telefoni e ricevitori sono
separate. Un render sul PC non dimostra fluidità o stabilità sul dispositivo.

Consulta le [guide tecniche](README.md) per protocollo delle stanze, vincoli Cast,
misure Anime4K, contratti delle estensioni e procedure di verifica.

## Condivisione dei contenuti

Il comando Condividi nelle schede genera un link che apre Nyanime. Lo stesso
pannello è disponibile selezionando un episodio o capitolo, da Altro nel player
e dal menu del lettore. Permette di scegliere scheda, inizio del contenuto,
minuto attuale o pagina attuale; la pressione prolungata su una pagina offre
anche il collegamento a quella pagina, distinto dal salvataggio dell’immagine.

Il destinatario deve avere la stessa estensione installata e abilitata.
L’app risolve il riferimento preciso del contenuto tramite i contratti generici
delle estensioni, senza indovinare un’alternativa. [Dettagli](content-sharing.md).

## Ricerca intelligente dei titoli

Home, ricerca globale, singole fonti e libreria tollerano punteggiatura, parole
unite, refusi e alias disponibili. I suggerimenti dei cataloghi pubblici sono
facoltativi e distinti dai risultati realmente disponibili nelle estensioni.
Il testo originale e i filtri restano applicati; la ricerca esatta è sempre
accessibile. La libreria usa soltanto dati locali. Cache cancellabile, nessun
catalogo precompilato e nessun cambiamento alle identità del tracking.
[Funzionamento, impostazioni e limiti](smart-title-search.md).

## Addon opzionali

Il catalogo in **Altro → Sfoglia → Addon** permette di installare modalità aggiuntive, autorizzarne la firma e gestirne gli aggiornamenti. Gli addon forniscono logo e gesto di apertura: con uno abilitato puoi tenere premuto il logo della Home; con più addon scegli quale aprire. Disabilitandoli, il logo torna unicamente decorativo. Il codice delle modalità rimane nei loro APK.
