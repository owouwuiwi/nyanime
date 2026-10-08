# Novità Nyanime

Questo file descrive le modifiche del fork Nyanime. Le funzioni ereditate e mantenute
sono incluse nel [catalogo completo](docs/features.md). La documentazione corrente
è raccolta nell'[indice](docs/README.md).

Le nuove versioni usano quattro numeri `X.Y.Z.W`, con `versionCode` Android crescente.
Le vecchie revisioni `rNNNN` restano riconosciute dal sistema OTA. Gli hash qui sotto
identificano modifiche nel repository, non garantiscono che ogni commit sia stato
distribuito come APK.

## Distribuzione privata e continuità OTA

- Il codice e la cronologia completa sono conservati nella repository privata; APK firmati, documentazione e changelog rimangono pubblici.
- Gli aggiornamenti conservano gli indirizzi storici e le release compatibili con i primi updater, senza cambiare firma o identificativo dell'app.

## 9 ottobre 2026 — 0.36.0.2: Lettura guidata stabile e TV nello Store (anteprima)

- Pagina intera e vignetta adattata allo schermo rimangono ferme; il trascinamento esplora l'immagine solo dopo un ingrandimento manuale.
- Pinch e doppio tap permettono di ingrandire; tornando allo zoom iniziale, l'immagine recupera il focus senza mantenere spostamenti accidentali.
- Il focus segue subito lo spazio disponibile dopo rotazione o comparsa e scomparsa delle barre, senza assestarsi al primo trascinamento.
- Gli swipe cambiano vignetta senza trascinare prima la pagina e senza interrompere la transizione appena iniziata.
- Il punto di lettura distingue il focus automatico dallo zoom personale, anche dopo rotazione o ripristino; conservati attivazione per titolo, correzioni e backup precedenti.
- La build OTA libera gli intermedi dopo la verifica degli APK e prima della firma, riducendo lo spazio occupato senza eliminare gli APK o i controlli di integrità.
- Le estensioni TV multilingua sono visibili nello Store anche sui telefoni dove non sono ancora installate, con i filtri italiano e inglese; invariati i filtri linguistici di anime e manga.

## 9 ottobre 2026 — 0.36.0.1: TV in diretta (consigliata)

- Il player distingue problemi temporanei di rete, indirizzi non raggiungibili, accesso rifiutato e formati non supportati.
- I collegamenti definitivamente falliti non vengono riprovati in continuazione: si provano le alternative dello stesso canale e un nuovo recupero dei collegamenti dall’estensione.
- Quando una diretta resta indisponibile, il player mostra il motivo e permette di scegliere un altro canale; un catalogo non garantisce che ogni trasmissione sia raggiungibile.
- La diagnostica del player live conserva soltanto il tipo di errore, senza indirizzi video, header o credenziali.
- Home TV facoltativa, alimentata dalle estensioni compatibili: canali, preferiti ordinabili, recenti e programmi in onda.
- Guida a lista o griglia, filtri di paese, lingua e categoria; palinsesti salvati e caricamenti progressivi per i canali aperti.
- Canali ricercabili in Atlante e condivisibili con link Nyanime senza indirizzi di streaming o credenziali.
- Player live dedicato con PiP, audio, sottotitoli e funzioni abilitate soltanto quando supportate; nessun episodio fittizio, tracking o AniSkip sui canali.
- Cambio canale e riconnessione conservano il lettore live; il ritorno dal PiP al canale già in riproduzione evita un caricamento aggiuntivo.
- Le stanze esistenti supportano la scelta del canale da parte dell’host; pausa e buffering restano locali. Il ritardo è verificato solo quando sono disponibili riferimenti temporali affidabili.
- Cast live per ricevitori compatibili e telecomando verticale; preferiti e scelte TV inclusi nei backup delle impostazioni.
- Contratto RIN TV separato e generico, con controlli di compatibilità e firma; nessun catalogo o parser di una fonte nell’app.

## 9 ottobre 2026 — 0.35.0.0: Lettura guidata (anteprima)

- Nuova lettura guidata facoltativa, ricordata per titolo, per manga, fumetti e webtoon: focus sulle vignette, zoom libero e ritorno alla pagina intera.
- Navigazione tramite tocchi, scorrimento e tasti configurati; transizioni di posizione e zoom coordinate e rispettose delle animazioni ridotte.
- Riconoscimento offline con modello incluso, elaborazione in un processo isolato e precaricamento della sola pagina successiva.
- Editor delle vignette con modifica dei bordi, aggiunta, rimozione, riordino e ripristino del riconoscimento automatico.
- Correzioni e punto di lettura inclusi nei backup e nella sincronizzazione Cloud; nessuna immagine o cache automatica nei backup.
- Lettura insieme e scarabocchi conservano le coordinate originali; il capitolo si completa raggiungendo l'ultima vignetta dell'ultima pagina.
- Se il riconoscimento non riesce, la pagina intera resta disponibile.

## 9 ottobre 2026 — 0.34.0.0: Guide episodi, Atlante e nuovo logo

- Nuova categoria «Guide» nello Store, con estensioni facoltative aggiornabili separatamente.
- Guida nella scheda anime, badge discreti per filler e misti, associazione e numerazione correggibili.
- Nascondere i filler e saltarli automaticamente sono scelte separate, disattivate inizialmente; il salto può seguire una preferenza globale o specifica del titolo.
- Player, autoplay e Cast condividono la scelta del prossimo episodio; nelle stanze decide l'host. Gli episodi aperti esplicitamente vengono riprodotti e quelli saltati non diventano visti.
- Dati salvati disponibili offline, richieste condivise e annullabili, classificazioni incerte lasciate sconosciute. Preferenze e correzioni sono incluse nei backup.
- Nuova icona e logo della Home e di Altro vettoriali, con fori trasparenti e risorse separate per tema chiaro e scuro. La N ha più margine nell'icona, con sfondo bianco nell'aspetto chiaro e nero in quello scuro. Gli asset precedenti sono conservati per poterli ripristinare.

### Atlante: risultati più pertinenti

- Tutti i risultati restano nella stessa griglia: titoli completi e nomi alternativi verificati precedono corrispondenze parziali e risultati meno pertinenti.
- L'ordine dipende dalla pertinenza e dalle fonti configurate, anche quando le risposte arrivano in momenti diversi o vengono caricate altre pagine.
- Conservati gli alias già disponibili durante risposte incomplete e aggiornamenti della Home; le alternative di fonte rimangono apribili.
- Riordino fuori dal percorso grafico, schede con identità stabile e transizioni brevi che rispettano le animazioni ridotte.
- Ricerca esatta, filtri, limiti di richieste e protezioni per stagioni ed edizioni rimangono disponibili.
- Con corrispondenze convincenti, i titoli estranei restano accessibili con «Mostra altri titoli»; nessun risultato viene perso. Il titolo principale precede i contenuti collegati con nomi più lunghi.

## 8 ottobre 2026 — 0.33.0.1: Tornano le stanze su Nostr (anteprima)

- Ripristinate le schermate precedenti per creare, raggiungere e gestire le stanze video e manga.
- Rimossi chat Telegram, gruppi automatici, chiamate e videocamere dalle stanze.
- Il player torna a schermo intero con i suoi comandi abituali, senza pannelli sociali o modifiche audio legate alle chiamate.
- Conservati codici stanza, inviti, lettura insieme e scarabocchi; mantenute le correzioni graduali dei piccoli ritardi e il recupero del buffer dopo una pausa.
- Backup, accesso Telegram e sincronizzazione personale Cloud rimangono disponibili.

## 8 ottobre 2026 — 0.33.0.0: I tuoi dispositivi, collegati (anteprima)

- Sincronizzazione Telegram facoltativa di librerie, categorie, cronologia, progressi video e manga, segnalibri e collegamenti ai tracker.
- Preferenze condivise con la possibilità di mantenere aspetto, player o lettore diversi su ciascun telefono; conversione dei riferimenti locali per categorie, novità e titoli nascosti.
- Associazione tramite QR monouso o codice di recupero, archivio cifrato e gestione dei dispositivi autorizzati.
- Nuove sezioni Sync, Dispositivi e Backup in Nyanime Cloud; copia di sicurezza cifrata prima della prima unione e protezione dei successivi backup Cloud dei dispositivi associati.
- “Continua a guardare/leggere” mostra il dispositivo di provenienza e permette di scegliere un punto conservato; i progressi remoti non spostano la riproduzione o la pagina già aperte.
- Invii persistenti, recupero dopo interruzioni e modifiche offline; download, incognito, sessioni Telegram e impostazioni hardware restano locali.
- Inventario RIN condiviso e supporto alle sole preferenze portabili dichiarate dalle estensioni, con consenso separato per le credenziali.

## 8 ottobre 2026 — 0.32.0.10: Protezione opzionale degli aggiornamenti

- Nuova capacità generica per addon di recupero, con autorizzazione esplicita e revocabile.
- Copia completa verificata prima degli aggiornamenti installati nell’app, quando la protezione è abilitata.
- Esportazione e ripristino isolati dall’avvio normale, con verifica di libreria, progressi e impostazioni.
- Nessun processo di recupero o backup aggiuntivo in assenza di un addon autorizzato.

## 8 ottobre 2026 — 0.32.0.9: Stanza compatta e integrata nel player (anteprima)

- Una sola barra con partecipanti, Chat, Voce e Video, con comandi secondari nel menu.
- Il video mantiene quasi tutto lo schermo finché la conversazione non è disponibile.
- Anteprime dei messaggi sotto il video, senza sovrapposizioni; videocamere compatte.
- Reazioni direttamente nel campo di scrittura, con selezione compatta; anteprime degli allegati ridotte.
- Campo di scrittura semplificato e spazio della conversazione più pulito.

## 8 ottobre 2026 — 0.32.0.8: Comandi della stanza accessibili dal player (anteprima)

- Pannello dei comandi della stanza sull’intero schermo, senza restringerlo alla nuova area del video.
- Pulsante Chiudi sempre raggiungibile e tasto Indietro che chiude i pannelli prima di passare in PiP.
- Rimossi i comandi della chat duplicati nel pannello della stanza; avviso di chiusura aggiornato per i gruppi temporanei.

## 8 ottobre 2026 — 0.32.0.7: Player condiviso, reazioni e chiusura delle stanze (anteprima)

- Video in alto e un solo spazio sottostante per chat, anteprima messaggi, reazioni, voce e videocamera, anche in orizzontale.
- Ogni partecipante può scegliere un episodio e usare Play, pausa e ricerca nella riproduzione; Nostr mantiene un unico ordine dei comandi.
- Microfono e videocamera indipendenti: un errore della videocamera non disattiva più la voce.
- Corretto l’avvio dell’uscita audio e rimossa la ricreazione dei dispositivi audio quando cambia soltanto la videocamera.
- Chiusura corretta del pannello chiamata e messaggi di errore distinti per i dispositivi.
- Conferme degli invii recuperate anche dopo un ritardo della connessione; gli invii incerti non vengono duplicati.
- Gruppi temporanei eliminati alla chiusura della stanza, con coda di cancellazione conservata in caso di assenza di rete.
- Correzioni della sincronizzazione più graduali e ripresa dal buffer disponibile dopo una pausa condivisa.

## 8 ottobre 2026 — 0.32.0.5: Chat nel player e chiamate nelle stanze (anteprima)

- Chat accanto al video in orizzontale e sotto il video in verticale, con comandi rapidi dedicati e campo di scrittura compatto.
- Corretto il posizionamento della conversazione quando si apre la tastiera.
- Anteprime dei messaggi e reazioni con avatar durante la visione, disattivabili dal menu della conversazione.
- “Guarda qui” condivide un minuto del video oppure capitolo e pagina del manga; ogni spostamento richiede un tocco esplicito.
- Menu comune per silenziare, cercare messaggi, vedere i partecipanti e tornare alla sessione.
- Comandi per voce e videocamera, con microfono e video inizialmente spenti; motore separato dal player e videocamera sospesa in background.
- Chat e chiamata accessibili anche dal lettore, mantenendo posizione personale e strumenti per disegnare.
- La sincronizzazione della visione continua a usare Nostr; gli errori della chat o della chiamata non comandano la riproduzione.

## 7 ottobre 2026 — 0.32.0.4: Collegamento della conversazione (anteprima)

- Corretto il recupero delle conversazioni rimaste incomplete durante la creazione.
- Un aggiornamento precedente della stanza non cancella più il gruppo appena creato da un partecipante.
- Preparazione con scadenza e nuova richiesta esplicita tramite “Riprova”, senza interferire con il video.

## 7 ottobre 2026 — 0.32.0.3: Chiusura della stanza dall’hub (anteprima)

- Aggiunto il pulsante “Chiudi stanza” per il proprietario e “Esci dalla stanza”
  per gli altri partecipanti direttamente nella scheda della stanza in corso.
- Conferma prima della chiusura o dell’uscita, con indicazione che la conversazione
  e i messaggi Telegram rimangono disponibili.

## 7 ottobre 2026 — 0.32.0.2: Accesso alla chat e stabilità Telegram (anteprima)

- Corretto un arresto del servizio Telegram durante rotazioni e cambi di
  configurazione, che poteva interrompere la preparazione della chat.
- Accesso Chat sempre visibile nel player quando si è in una stanza.
- Conversazioni accessibili anche dai comandi della stanza e dalla lettura:
  collegamento dell’account, attivazione e stato della preparazione nello stesso punto.
- Titolo dell’hub semplificato in “Guarda insieme”.

## 7 ottobre 2026 — 0.32.0.0: Chat Telegram nelle stanze (anteprima)

- Hub Stanze con sessione attiva, conversazioni recenti e messaggi non letti.
- Chat Telegram facoltativa con l’account già collegato a Cloud e consenso separato.
  Le conversazioni riutilizzano gli stessi partecipanti; un gruppo diverso conserva
  una cronologia separata.
- Nuova interfaccia della chat con copertina del contenuto, avatar, messaggi,
  risposte, reazioni rapide e spoiler nascosti fino al tocco.
- Chat integrata nel player, lateralmente in orizzontale e sotto il video in
  verticale, e disponibile anche durante la lettura condivisa.
- Invio di foto e sticker recenti, apertura delle immagini con zoom e ascolto
  dei messaggi vocali ricevuti. Le foto inviate vengono private dei metadati EXIF.
- Bozze e coda degli invii conservate per account; gli invii incerti non vengono
  ripetuti automaticamente e non risultano falsamente consegnati.
- Layout adattato a schermi stretti, caratteri grandi, tastiera, temi chiaro e
  scuro e preferenza per le animazioni ridotte.
- Questa anteprima riceve messaggi mentre l’app è aperta. Chiamate vocali,
  videocamera e notifiche ad app chiusa non sono ancora disponibili.

## 7 ottobre 2026 — 0.31.0.7: Continuità degli aggiornamenti

- I vecchi updater continuano a trovare la versione più recente anche quando cresce lo storico delle release.
- Ripetere una pubblicazione o promuovere un'anteprima conserva APK e checksum già distribuiti, senza ripristinare documentazione più vecchia.

## 7 ottobre 2026 — 0.31.0.5: Accesso Cloud più comodo

- Numero Telegram con selettore del paese e del prefisso, ricercabile per nome o codice.
  Italia (+39) è preselezionata: basta inserire il numero senza riscrivere il prefisso.
- Incollare un numero internazionale completo aggiorna il paese senza duplicare
  il prefisso; gestiti gli zeri iniziali e la formattazione dei diversi paesi.

## 7 ottobre 2026 — 0.31.0.4: Ripristino delle animazioni precedenti

- Annullato il rifacimento delle animazioni introdotto con 0.30.0.0:
  Home, Libreria, navigazione, player, lettore e pannelli tornano al comportamento precedente.
- Conservati Nyanime Cloud, accesso Telegram, backup automatici e ripristino guidato.
  I componenti di presentazione necessari a Cloud rimangono confinati alle sue schermate.

## 7 ottobre 2026 — 0.31.0.3: Nyanime Cloud

- Accesso Telegram condiviso per le funzioni Nyanime, tramite il motore ufficiale TDLib.
- Accesso con avanzamento immediato ed errori specifici accanto al campo da correggere.
- Schermata Cloud integrata con il tema Nyanime: accesso guidato, ultimo backup
  in primo piano, cronologia per dispositivo e impostazioni in un pannello dedicato.
- Corretto un difetto nel passaggio delle richieste al motore Telegram che poteva
  lasciare l’accesso in attesa senza proseguire.
- Invio dei documenti adeguato all’API TDLib inclusa; gli errori permanenti non
  vengono più rimandati all’infinito come semplici attese di rete.
- Backup completi in un archivio privato Telegram, con storico per dispositivo,
  avanzamento, esportazione e ripristino attraverso le opzioni già presenti nell’app.
- Proposta facoltativa di Cloud al primo avvio, prima delle altre schermate di
  configurazione; nessun accesso o backup viene avviato se si sceglie “Più tardi”.
- Ripristino guidato con anteprima del contenuto, opzioni avanzate raccolte,
  avanzamento e risultato nella stessa schermata. Ripristinare un catalogo già
  presente con lo stesso indirizzo e la stessa chiave non genera più un falso errore.
- Backup automatici ogni 12 ore, intervallo e rete configurabili, copie protette
  e conservazione per numero o età; invii interrotti recuperabili.
- Proposta di recupero dei backup esistenti prima del primo salvataggio automatico
  su un nuovo telefono. Sessioni Telegram e file multimediali restano esclusi.
- Motore Telegram in un processo separato, avviato soltanto quando si usa Cloud;
  controlli delle librerie native e delle licenze prima della pubblicazione.

Accesso, archivio privato, invio confermato ed esportazione verificati su Android.
Le prove e i limiti di verifica sono descritti nella [Guida Cloud](docs/telegram-cloud.md).

## 7 ottobre 2026 — 0.30.0.0: Animazioni coordinate e controlli più fluidi

- Tempi e curve comuni per selezioni, finestre, pannelli e cambi di stato,
  con movimenti contenuti e chiusure più rapide.
- Apertura e ritorno dalle copertine coordinati con titoli, azioni e sfondi;
  immagini già visibili conservate durante caricamento ed errori.
- Play e Pausa si trasformano con un morph discreto nel player, nelle stanze
  e nel telecomando Cast, senza ritardare i comandi.
- Pannelli e finestre gestiscono il ritorno durante l'apertura e i tocchi rapidi,
  impedendo interazioni con i contenuti in uscita.
- Calendario più stabile durante gli aggiornamenti; selezioni e passaggi tra
  Anime e Manga uniformati anche in Libreria e nelle Novità.
- «Riduci animazioni» disponibile anche in Aspetto e applicato all'interfaccia,
  mantenendo la stessa preferenza del player e rispettando la scala Android.
- Animazioni decorative sospese quando nascoste o con l'app in background.

## 6 ottobre 2026 — 0.29.0.5: Addon più chiari e integrati

- Supporto agli addon RIN: installazione, aggiornamento e disattivazione direttamente
  nello Store, con interfacce e logica mantenute nei pacchetti addon.
- Gli addon possono aggiungere azioni in Altro attraverso un contratto dichiarativo,
  con titolo, icona e spiegazione forniti dall’addon stesso.
- Descrizioni dettagliate disponibili nello Store e nelle impostazioni degli addon,
  con indicazioni chiare su come aprirli e disabilitarli.
- Gli addon possono dichiarare una versione minima dell’app e, solo se necessario,
  una massima: Store e apertura verificano la compatibilità con spiegazioni chiare.
- Conservata la compatibilità delle modalità già disponibili con pressione sul logo.
- Tema e lingua condivisi senza incorporare motori, logica o dati specifici degli addon
  nell’app principale.

## 6 ottobre 2026 — 0.29.0.4: Rimozione della modalità Libri

- Rimossa la modalità Libri con il relativo contratto RIN, lettori documenti,
  browser incorporato e download dedicati.
- Pulizia dei moduli e dei dati residui della funzione ritirata sulle installazioni
  aggiornate, senza modificare librerie, progressi e impostazioni anime e manga.
- Conservate le correzioni recenti a Home manga, tracking automatico e notifiche.

## 6 ottobre 2026 — 0.29.0.1: Home manga e tracking più affidabile

- Il carosello manga riunisce tutte le sezioni in evidenza delle fonti della
  categoria, unendo le opere con ID pubblici coerenti e mantenendo il cambio fonte.
- Tracking automatico anime e manga tramite ID AniList verificato anche quando
  il titolo locale è tradotto o abbreviato. La ricerca manuale resta disponibile.
- Errori temporanei di rete e timeout del tracking vengono riprovati con un
  limite; le aggiunte alla libreria rimangono recuperabili dopo un'interruzione.
  Nessun collegamento forzato per risultati ambigui o servizi senza accesso.
- Tracking all'avvio conforme anche alla modalità incognito della singola
  estensione; tentativi dello stesso titolo serializzati per evitare doppioni.
- Avvisi delle nuove uscite conservati se manca il canale Android o se i permessi
  cambiano durante la preparazione della notifica.
- Ampliate le verifiche di regressione per Home, tracking e consegna degli avvisi.

## 6 ottobre 2026 — 0.28.0.4: Il nuovo Store

- Store riprogettato con «Le mie» e «Scopri», ricerca, categorie e filtri per stato.
- RIN gestibili con Attiva/Disattiva, Apri, Installa e Disinstalla; libreria,
  progressi e preferenze conservati quando una fonte viene disattivata o rimossa.
- Estensioni installate visibili anche senza catalogo disponibile, con icone e
  dati locali; disposizione adattiva e dettagli accessibili dalla singola scheda.
- RIN disattivate aggiornate automaticamente senza riattivarle; aggiornamenti
  e modifiche dei moduli rispettano riproduzione, lettura e Cast in corso.
- Eliminati «Mantieni questa versione» e il vecchio percorso Legacy → Ready.
  Resta il passaggio obbligatorio dalle estensioni APK alle RIN disponibili.

## 6 ottobre 2026 — 0.28.0.3: Stabilità e migrazione RIN

- Risolto un crash nella preparazione delle RIN con librerie Kotlin incorporate:
  runtime e contratti condivisi mantengono l’identità delle classi dell’app.
- Errori di compatibilità e inizializzazione delle estensioni gestiti come errori
  recuperabili, prima della rimozione della versione precedente.
- Schermata di errore separata dal caricamento delle estensioni e dai servizi
  in background, evitando un secondo crash nel gestore degli errori.
- RIN aggiornate automaticamente anche quando lo Store trova una nuova versione
  prima del controllo periodico; recupero dei download interrotti e delle attese.
- Nello Store le RIN mantengono «Apri» durante gli aggiornamenti automatici,
  senza richiedere un aggiornamento manuale; le versioni bloccate restano rispettate.

- Passaggio alle RIN compatibile con le edizioni che un’estensione ha già
  disattivato: gli identificativi restano conservati senza riattivare le lingue.
- Aggiornamenti automatici che preservano anche le identità dormienti dichiarate
  dal pacchetto, evitando associazioni errate o migrazioni bloccate.

## 6 ottobre 2026 — 0.28.0.2: Icone RIN

- Icone originali delle estensioni RIN mostrate nello Store e nelle schermate di
  gestione anche dopo la rimozione dell’APK precedente.
- Icone del catalogo disponibili anche per le RIN non ancora installate e per i
  pacchetti precedenti senza icona incorporata.

## 6 ottobre 2026 — 0.28.0.1: Compatibilità RIN

- Passaggio alle estensioni RIN disponibile anche per le fonti installate ma
  nascoste dal filtro dei contenuti adulti; il filtro resta rispettato nell’uso normale.
- Verifica delle fonti prima della migrazione indipendente dalle preferenze di
  visualizzazione, senza modificare libreria, progressi o impostazioni delle fonti.
- Icone delle estensioni conservate nei pacchetti RIN e mostrate anche dopo la
  rimozione dell’APK Android precedente.

## 6 ottobre 2026 — 0.28.0.0: Nyanime RIN

- Nuove estensioni RIN in Kotlin, gestite all’interno dell’app senza installare
  pacchetti Android separati. Le estensioni APK e gli addon restano supportati.
- Passaggio guidato alle RIN disponibili nel proprio catalogo, con preparazione
  verificata prima della disinstallazione e conservazione di libreria e progressi.
- Schermata di passaggio con logo SVG originale, temi chiaro e scuro, contenuto
  scorrevole e pulsanti sempre accessibili anche con caratteri grandi.
- Controllo del catalogo in background, senza schermate di preparazione a ogni
  apertura; il blocco compare soltanto per le migrazioni effettivamente necessarie.
- Aggiornamenti automatici delle sole RIN già installate, ogni sei ore e alla
  riapertura dell’app; attivazione rimandata durante video, PiP, lettura e Cast.
- Firma del publisher e integrità di tutti i componenti verificate prima del
  caricamento, con mantenimento della versione precedente in caso di errore.
- Store e backup aggiornati per le RIN, mantenendo i contratti Home, i filtri,
  gli identificativi delle fonti e la compatibilità con i backup precedenti.

## 5 ottobre 2026 — 0.27.0.0: Download adattivi

- Accelerazione automatica dei video HTTP e HLS compatibili, con connessioni
  adattate alla velocità e ai limiti del server, senza ridurre la qualità.
- Riconoscimento dei contenitori anche nei link senza estensione e aumento più
  rapido delle connessioni quando migliora la velocità.
- Ripresa dei blocchi e dei segmenti verificati dopo un’interruzione; percorso
  precedente mantenuto quando il server o il formato non consentono l’accelerazione.
- Salvataggio HLS nelle cartelle Android tramite il file già autorizzato, senza
  errori del protocollo SAF; recupero separato delle code anime e manga al riavvio.
- Download manga con trasferimenti adattivi, meno scansioni delle cartelle e
  lavorazioni delle immagini e degli archivi limitate per contenere il carico.
- Le due code condividono i limiti di rete. Aggiungere nuovi elementi conserva
  quelli in corso; l’impostazione dei download simultanei viene rispettata.
- Pausa dei download interni durante la visione, anche in PiP e nelle stanze;
  ripresa all’uscita dal player, senza annullare una pausa scelta manualmente.
- Avanzamento con byte trasferiti, velocità, tempo restante quando disponibile
  e motivo dell’attesa; salvataggio verificato prima del completamento.
- Rimosso il vecchio avviso generico sui download di massa: la coda mostra
  lo stato effettivo e gli eventuali errori di trasferimento.
- Corretti il nome della coda Manga, il conteggio della scheda selezionata
  e le indicazioni di attesa dopo il riavvio; testi dei repository aggiornati a Nyanime.
- Esecuzione dei download manuali tramite i trasferimenti avviati dall’utente
  su Android 14+, mantenendo WorkManager per richieste automatiche e recupero.

## 5 ottobre 2026 — Aggiornamenti delle estensioni nello Store

- Il refresh ricontrolla i cataloghi sul server anche se la copia locale è ancora
  recente: nuove versioni e nuovi addon non restano nascosti dalla cache.
- Se il catalogo è invariato, la verifica HTTP riutilizza i dati già scaricati.
  Controlli di firma, distribuzione e preferenze di aggiornamento restano attivi.

## 5 ottobre 2026 — Filtri dello Store semplificati

- Restano soltanto Italiano e Inglese, selezionabili singolarmente o insieme.
- Nuove schede con tutta la riga cliccabile, selezione animata e filtro per adulti
  distinto; le vecchie lingue salvate vengono eliminate dalla selezione.
- Le estensioni installate rimangono disponibili per gestione e aggiornamenti.

## 5 ottobre 2026 — Introduzione agli addon all’avvio

- La scheda degli addon compare una volta all’apertura di Nyanime, anche senza
  addon installati o cataloghi configurati.
- La conferma viene salvata soltanto premendo Continua; chiudere l’app prima
  della conferma conserva la spiegazione per il prossimo avvio.
- Pulsante sempre accessibile, testo scorrevole e comparsa animata nel tema scelto.

## 5 ottobre 2026 — 0.25.0.0: Addon opzionali

- Le modalità aggiuntive si installano dal catalogo delle estensioni: compatibilità,
  firma, autorizzazione e aggiornamenti sono verificati dall’app.
- Ogni addon fornisce il proprio logo e il gesto di apertura. Puoi abilitarlo o
  disabilitarlo senza modificare la Home; il tema segue quello dell’app.
- Una pagina al primo accesso spiega come usare gli addon e il possibile percorso
  delle funzioni alpha e beta, senza riproporsi ai successivi accessi.
- L’app base conserva solo il contratto generico: codice, risorse, motore video e
  dipendenze delle modalità aggiuntive risiedono nei rispettivi APK.
- Ripristinata la configurazione Gradle dell’app senza i moduli delle modalità
  aggiuntive; rimossi anche i relativi passaggi della compilazione principale.

## 4 ottobre 2026 — Informazioni sulle opere

- Approfondimenti facoltativi nelle schede: autori, cast, struttura e opere
  collegate, con informazioni da AniList, TVmaze e Wikidata senza account o chiavi.
- Pagine native delle persone e delle opere collegate, ricerca nelle proprie
  fonti e collegamenti IMDb quando l'identificativo è verificato.
- Associazioni correggibili, precedenza dei dati della fonte e cache disponibile
  offline; consenso e scelte manuali inclusi nei backup delle impostazioni.

## 4 ottobre 2026 — Cinema in cuffia

- Audio binaurale facoltativo per le tracce 5.1 e 7.1, con confronto
  «Originale / Cinema» direttamente dal menu Audio del player.
- Preferenza ricordata, riconoscimento delle cuffie e audio originale
  per le tracce non compatibili; nessun modello da scaricare.
- Elaborazione locale leggera con risposte acustiche misurate, senza
  cambiare volume, punto di ripresa o sincronizzazione delle stanze.

## 4 ottobre 2026 — Categorie di lettura dalle estensioni

- Home di lettura distinte secondo le categorie dichiarate dalle estensioni,
  con sezioni, generi e ricerca coerenti nella Home e in Atlante.
- Ripresa e novità nella categoria pertinente, mantenendo la libreria,
  i progressi e i controlli su lingue, privacy e contenuti per adulti.
- Filtri di esplorazione dichiarati dalle estensioni anche per la lettura,
  schede dei capitoli più leggibili e apertura nella categoria scelta.
- Errori delle sezioni visibili e riprovabili, senza svuotare i contenuti
  salvati; eliminate le richieste agli ID superflue nelle Home con una sola fonte.

## 4 ottobre 2026 — Cataloghi tramite link e versioni consigliate

- Pulizia una tantum dei cataloghi per chi aggiorna; le nuove installazioni,
  le estensioni installate, la libreria e i progressi restano esclusi.
- Cataloghi aggiunti tramite «Aggiungi a Nyanime» e link di importazione,
  con istruzioni al posto dell'inserimento manuale degli indirizzi.
- Autorità facoltative dei cataloghi, mostrate prima del consenso: possono
  consigliare le proprie versioni firmate, mantenendo la scelta all'utente.
- Sostituzione guidata delle versioni con firma diversa, anche senza Home,
  con controllo delle identità, download verificato e conferma prima della rimozione.

## 4 ottobre 2026 — Store Vetrina

- Nuovo Store unificato per Anime, Manga e Notizie, con schede Nyanime Ready
  e un elenco Legacy distinto, ricerca, lingue e inventario delle installate.
- Installazione e aggiornamento dalla vetrina, mantenendo gestione delle fonti,
  impostazioni, fiducia, cataloghi e migrazione della libreria.
- Passaggio guidato dalle legacy compatibili alle Ready: download e verifica prima
  della rimozione, ripresa della procedura e aggiornamento diretto quando possibile.
- Testata stabile, dissolvenze coordinate, icone con spazio riservato e rispetto
  delle animazioni ridotte; italiano e inglese inclusi.

## 4 ottobre 2026 — Schede Primo piano

- Elenco completo di episodi e capitoli direttamente nella scheda, con ricerca,
  indice rapido e salto al punto di ripresa; gestione multipla separata.
- Posizione conservata tra elenco e dettagli, righe caricate solo quando servono
  e caricamento più stabile di progresso, ripresa e avvisi opzionali.
- Nuove schede anime e manga con copertina protagonista, progresso e ripresa immediata,
  mantenendo tracking, avvisi, stanze, categorie e tutte le azioni esistenti.
- Gestore di episodi e capitoli con ricerca per numero o titolo, indice a gruppi,
  novità effettive, offline, segnalibri e selezione multipla.
- Caricamento con spazi già riservati, icone coordinate e transizioni della copertina
  coerenti anche al ritorno; rispetto delle animazioni ridotte.

## 4 ottobre 2026 — Cataloghi unificati e aggiornamenti delle estensioni

- Possibilità di collegare un’estensione installata manualmente a un catalogo compatibile,
  scegliendo esplicitamente l’origine nei dettagli dell’estensione.
- Controllo di pacchetto, firma, distribuzione e supporto Home prima del passaggio;
  «Mantieni questa versione» continua a escludere gli aggiornamenti.
- Un’unica conferma per importare cataloghi che comprendono video, manga e notizie;
  ogni sezione mostra soltanto le estensioni del proprio tipo.
- Installazione e aggiornamento delle estensioni Notizie dalla schermata Fonti,
  con avanzamento, controlli di firma e consenso separato per abilitarle.
- Versioni e pulsanti delle estensioni Notizie aggiornati al ritorno dall’installatore,
  conservando preferenze e consenso quando l’identità della distribuzione coincide.
- Collegamenti Nyanime per importare cataloghi senza credenziali condivise
  e senza nomi o regole delle fonti incorporati nell’app.
- Sito e nuovi link di condivisione sul dominio dell’organizzazione; collegamenti
  precedenti ancora compatibili, inclusi episodio, minutaggio, capitolo e pagina.

## 3 ottobre 2026 — Una nuova schermata per gli aggiornamenti

- Schermata degli aggiornamenti ridisegnata con testata compatta, versione e comandi
  fissi; scorrono soltanto le novità, in schede leggibili nei temi chiaro e scuro.
- Download con avanzamento, annullamento e possibilità di continuare a usare l’app;
  «Installa ora» compare dopo il controllo del file scaricato.
- Controlli fuori dal thread dell’interfaccia, protezione dai tocchi ripetuti e recupero
  dei file non più disponibili, con messaggi in italiano e inglese.

## 3 ottobre 2026 — Nuovo repository e continuità degli aggiornamenti

- Aggiornamenti e collegamenti di aiuto usano il repository dell’organizzazione.
- Compatibilità mantenuta con il vecchio indirizzo OTA e con le versioni `r…`,
  conservando pacchetto, firma e dati dell’app.
- Recupero tramite l’indirizzo precedente se quello principale non è disponibile;
  il cambio di repository avvia un nuovo controllo senza attendere la vecchia cache.

## 3 ottobre 2026 — Versioni e scelta degli aggiornamenti

- Nuova numerazione a quattro componenti, a partire da 0.19.0.0, con confronto numerico
  e versione Android crescente; le installazioni precedenti continuano ad aggiornarsi via OTA.
- Scelta iniziale tra «Consigliati» e «Anche le anteprime», chiesta una sola volta dopo
  la conferma, con schermata adattiva nei due temi e testi in italiano e inglese.
- Preferenza modificabile in Impostazioni → Aggiornamenti, con controllo manuale e
  installazione integrata; cambiare canale non propone versioni precedenti.
- Pubblicazioni con note dedicate e APK immutabili, mantenendo pacchetto e firma per
  conservare i dati. [Numerazione e compatibilità](docs/versioning.md).

## 3 ottobre 2026 — Player più reattivo e compatibilità delle estensioni

- Caricamento delle tracce audio e dei sottotitoli esterni fuori dal thread
  dell’interfaccia, per evitare blocchi all’apertura dei video con molte tracce.
- Il caricamento delle tracce rispetta il cambio di episodio e la chiusura del player,
  evitando di applicare tracce a una riproduzione successiva.
- Ripristinata la compatibilità con le varianti della libreria video 16 usate dalle
  estensioni, evitando errori di apertura dovuti ai costruttori dei server video.

## 3 ottobre 2026 — Rimozione del doppio tocco posteriore

- Rimossi il gesto posteriore, la calibrazione, le relative impostazioni e
  l’ascolto dei sensori in tutte le schermate.
- Pulizia automatica delle preferenze obsolete, escluse anche dal ripristino
  dei vecchi backup. Restano disponibili i normali gesti del player e del lettore.

## 2 ottobre 2026 — Icone delle estensioni Notizie

- Le fonti Notizie mostrano l’icona del rispettivo APK anche se disabilitate,
  con caricamento fuori dall’interfaccia e un segnaposto per i pacchetti senza icona.

## 2 ottobre 2026 — Notizie legate ai tuoi titoli

- «Per te» riconosce anche i nomi alternativi e tradotti verificati nei cataloghi,
  gli ID equivalenti e le relazioni dirette tra opere, senza modificare il tracking.
- Le schede spiegano il collegamento al titolo seguito; le semplici citazioni possono
  comparire nel feed, ma non generano notifiche personali senza un riscontro affidabile.
- Recupero graduale dei dati mancanti degli articoli, cache degli abbinamenti e
  esclusioni valide anche per gli ID equivalenti. Restano esclusi gli avvisi storici.

## 2 ottobre 2026 — Notizie dalle estensioni

- Nuovo tipo di estensione Notizie, separato dalle fonti video e manga: si abilita da
  Sfoglia e aggiunge una categoria nella Home, mantenendo la testata e la navigazione.
- Articoli compatti in Ultime, Per te e Salvati, ricerca dedicata e lettore integrato
  con testo regolabile, immagini ingrandibili e collegamento alla pubblicazione originale.
- Salvataggi per leggere il testo offline, punto di lettura e associazioni ai titoli
  inclusi nei backup. Gli articoli nuovi non spostano quelli che stai leggendo.
- Notifiche facoltative per fonte, con controllo periodico, deduplicazione e nessun
  invio dell’archivio storico alla prima attivazione o dopo un ripristino.

## 2 ottobre 2026 — Ripresa compatta nella Home

- «Continua a guardare» usa segnalibri orizzontali compatti: piccola copertina verticale,
  titolo, episodio, minutaggio e avanzamento, con ripresa al tocco e menu separato.
- Altezza adattata ai caratteri grandi e skeleton delle stesse dimensioni, mantenendo
  apertura della scheda, nascondi con annullamento e ripresa del finale.

## 2 ottobre 2026 — Carosello senza intestazione

- Rimossa la riga «In evidenza» con la freccia sopra il carosello, anche durante
  il caricamento: le copertine partono direttamente sotto le categorie.

## 2 ottobre 2026 — Ripresa dalla Home e fluidità

- Conservati gli angoli arrotondati delle copertine durante apertura e ritorno dalla
  scheda, con una sola immagine e un cambiamento continuo di forma e proporzioni.
- «Continua a guardare» nella Home diventa una fila di schede ampie: ripresa diretta,
  episodio, minutaggio e avanzamento leggibili, con le altre azioni raccolte nel menu.
- Migliorata la fluidità delle transizioni tra Home e schede, limitando i ricalcoli
  agli elementi coinvolti; le azioni sulla copertina seguono la dissolvenza del titolo.
- Rimossi gli indicatori sotto i titoli in primo piano per alleggerire la Home.

## 2 ottobre 2026 — Aumento della numerazione

- Aumento di numerazione, nessun cambiamento alle funzionalità dell'app.

## 2 ottobre 2026 — Home Panorama

- Nuova Home Panorama per anime e manga: copertina centrale ampia, anteprime laterali,
  scorrimento circolare con il dito e rotazione dopo sei secondi di inattività.
- Testata stabile, titoli in dissolvenza e skeleton con le stesse dimensioni delle
  copertine. Il titolo selezionato si conserva durante gli aggiornamenti delle sezioni.
- Ripresa compatta con progressi e opzioni; novità più leggibili, con tutte le voci
  raggiungibili e azioni di lettura, riproduzione e apertura della scheda conservate.
- Categorie, sezioni, date, classifiche, capitoli, paginazione e fonti alternative
  continuano a usare i contratti delle estensioni, senza regole specifiche nell'app.
- Rotazione sospesa durante l'interazione, fuori dalla sezione visibile, nelle finestre
  di scelta della fonte e con animazioni ridotte o lettore schermo attivo.

## 1 ottobre 2026 — Lingua della Libreria

- Il pulsante Libreria e le intestazioni della raccolta seguono la lingua scelta
  nell'app, mostrando «Library» in inglese e «Libreria» in italiano.

## 1 ottobre 2026 — Affidabilità della Home

- Migliorato il coordinamento tra dati locali e inizializzazione asincrona delle estensioni.
- Gestiti gli aggiornamenti del registro delle estensioni senza modificare i dati
  persistenti o introdurre richieste di rete aggiuntive.

## 1 ottobre 2026 — Atlante, ricerca unificata

- Cerca riunisce anime e manga, con categorie della Home, risultati progressivi
  e scelta delle fonti alternative quando gli ID verificati identificano la stessa opera.
- La barra inferiore si trasforma nel campo di ricerca; generi e filtri sono disponibili
  sopra il campo, con accesso ai filtri avanzati delle singole fonti. Sfoglia si trova in Altro
  e si apre anche tenendo premuto Cerca; il logo rimane fisso durante la transizione.
- Esplorazione con copertine già disponibili, suggerimenti ed esatta/intelligente,
  memoria facoltativa della ricerca e preferenze per tastiera e ricerca mentre scrivi.
- Ritorno dalle schede con filtri e posizione conservati, dissolvenze coordinate,
  controlli adattati alla tastiera e testi in italiano e inglese.
- Breve guida al primo aggiornamento per trovare Cerca e Sfoglia, senza mostrarla
  nelle nuove installazioni; campo di ricerca più leggibile e placeholder centrato.
- Richieste limitate e condivise tra i due tipi di contenuto, risultati obsoleti scartati
  e comportamento incognito/Solo scaricati conservato. [Guida](docs/atlas-search.md).

## 1 ottobre 2026 — Risultati completi per grafie equivalenti

- La ricerca con spazi o punteggiatura diversa conserva la query breve e recupera
  anche stagioni, parti e titoli collegati, senza restringersi al primo nome completo.
- Un risultato iniziale non blocca il recupero delle grafie equivalenti note;
  refresh e paginazione conservano tutte le varianti verificate, entro i limiti esistenti.
- Aggiunte verifiche della prima ricerca e delle successive con cache già alimentata.

## 30 settembre 2026 — Ricerca intelligente dei titoli

- Home, Sfoglia, singole fonti e libreria riconoscono punteggiatura diversa,
  parole unite, refusi e alias, mantenendo il testo originale e i filtri.
- Suggerimenti di titolo distinti dai risultati disponibili, risultati progressivi e recuperi limitati
  e comando per alternare ricerca esatta e intelligente.
- Recupero automatico del candidato plausibile anche con alternative visibili;
  numeri del nome e delle parti con precedenza, con recupero del titolo base
  quando il numero finale non corrisponde a una parte riconosciuta.
- Aiuto online facoltativo dei cataloghi, cache locale cancellabile e nessuna
  nuova memorizzazione persistente in incognito. La libreria resta tutta locale.
- Nessun aggiornamento obbligatorio delle estensioni e nessuna modifica automatica
  al tracking o alle associazioni dei titoli. [Dettagli e licenze](docs/smart-title-search.md).

## 30 settembre 2026 — Impostazioni delle notifiche più facili da trovare

- Aggiunta «Notifiche e uscite» direttamente nelle Impostazioni, con orari dei
  promemoria, avvisi per nuovi episodi e capitoli, permessi Android e notifiche di prova.
- Le scelte sono raggiungibili anche dalla ricerca delle impostazioni; la Libreria
  rimanda alla stessa schermata. Gli orari si conservano quando i promemoria sono spenti.
- I promemoria si possono attivare anche mentre si scelgono gli orari, senza tornare
  indietro. Il salvataggio riprogramma gli avvisi anche uscendo subito dalla schermata.

## 30 settembre 2026 — Traduzioni inglesi più complete

- Stanze, inviti, QR, lettura condivisa e schizzi seguono la lingua dell’app,
  inclusi messaggi di connessione, comandi nel player e notifica della stanza.
- Tradotti anche i testi e i filtri della Home, le opzioni della sigla e dei comandi del player,
  il download degli aggiornamenti nell’app e la visibilità dei manga in altre lingue.
- L’italiano rimane disponibile; codici d’invito e riferimenti delle estensioni
  conservano il formato esistente.

## 30 settembre 2026 — Collegamenti condivisi apribili dall’app

- Schede, episodi con minutaggio e capitoli con pagina vengono condivisi con link
  HTTPS e titolo leggibile, adatti alle applicazioni di messaggistica.
- Il sito Nyanime permette di aprire il contenuto anche nelle versioni precedenti;
  l’app aggiornata riconosce i collegamenti verificati. I vecchi link restano validi.
- I riferimenti rimangono dati opachi forniti dalle estensioni: nessuna fonte o
  regola di sito entra nell’app.

## 30 settembre 2026 — Orari personalizzati per i promemoria

- I promemoria delle trasmissioni anime possono essere combinati: 24 ore prima o alle 09:00,
  15:00 e 20:00 del giorno precedente; il giorno stesso agli stessi orari,
  un’ora, 10, 5 o 2 minuti prima, oppure all’orario annunciato.
- Gli orari fissi seguono il fuso del telefono. Gli avvisi che arriverebbero dopo
  l’uscita o troppo tardi vengono saltati; scelte coincidenti non duplicano la notifica.
- Le preferenze già esistenti per 24 ore prima e all’uscita restano attive dopo
  l’aggiornamento. Le notifiche di contenuti disponibili nella fonte restano separate.
- La riprogrammazione completa il salvataggio anche uscendo subito dalle impostazioni;
  cambiare la lingua del telefono conserva gli orari scelti.

## 30 settembre 2026 — Backup più semplici

- Un backup completo si avvia con un tocco da Altro o da Dati e archiviazione,
  con avanzamento visibile anche cambiando schermata.
- I backup manuali vengono salvati in Download/Nyanime e verificati prima di
  comparire tra i file. Contengono anche impostazioni sensibili ed estensioni,
  ma non i video o i capitoli scaricati.
- Il ripristino mostra i backup trovati sul dispositivo e un'anteprima del loro
  contenuto; rimane possibile selezionare un file esterno o un vecchio `.tachibk`.

## 30 settembre 2026 — Home e navigazione

- Copertine protagoniste, sezioni e stati di caricamento riorganizzati nella
  Home per rendere più chiari titoli, azioni e contenuti in evidenza.
- Categorie e filtri conservano la disposizione compatta originale; i comandi
  della testata mantengono una posizione stabile quando cambiano le novità.
- Barra inferiore flottante più contrastata, senza alone o fondale esterno. Le
  pagine scorrono sotto la barra e lasciano libero l'ultimo contenuto a fine elenco.
- In modalità scura il fondale della barra inferiore è più scuro e uniforme.
- Il logo vettoriale arancione della modalità chiara usa una tonalità più scura.

## 30 settembre 2026 — Libreria Anime e Manga ridisegnata

- Libreria ModernUI più leggibile, con testata compatta, categorie ordinate,
  progresso immediato e stati vuoti più utili.
- Le nuove uscite vengono raccolte in cima finché non sono viste o lette.
- La sezione “Tutti” riunisce Anime e Manga in un'unica raccolta ricercabile.
- In “Tutti” la visualizzazione si può scegliere e ricordare; la griglia è
  predefinita. Aprire un titolo rimuove i suoi avvisi già visti dalla raccolta
  delle novità, senza segnare episodi o capitoli come guardati o letti.
- Il passaggio tra “Tutti”, “Anime” e “Manga” mantiene ferma la testata e usa una
  dissolvenza discreta; filtri, categorie e azioni esistenti restano disponibili.
- La Home usa un logo vettoriale trasparente coordinato ai temi chiaro e scuro,
  mentre la testata di “Altro” resta invariata.

## 29 settembre 2026 — Ripresa intelligente degli episodi

- “Continua a guardare” propone il prossimo episodio quando restano pochi secondi
  oppure la riproduzione arriva a cinque secondi dall'inizio della sigla finale riconosciuta.
- Il finale resta accessibile dalla scheda di ripresa. Alla successiva apertura
  della Home o di una scheda di ripresa, il marker verificato aggiorna “visto”
  e accoda il tracking, senza modificare la riproduzione.
  I marker finali sono conservati nel backup.
- Home, scheda, libreria e cronologia condividono la scelta dell'episodio da riprendere.

## 29 settembre 2026 — Manga unificati e cambio fonte

- Riconoscimento delle fonti alternative tramite ID pubblici, condiviso tra
  Home, ricerca e scheda manga anche quando i titoli compaiono in sezioni diverse.
- Cambio fonte in un pannello compatto, con scelta della fonte predefinita;
  apertura diretta e collegamenti ai capitoli conservati per ciascuna estensione.
- Recupero progressivo delle identità senza limite ai primi otto risultati,
  mantenendo posizione delle schede e scorrimento durante gli aggiornamenti.
- La Home Manga apre direttamente con filtri e contenuti, senza intestazione e
  scorciatoia duplicate; la Libreria rimane nella navigazione principale.
- Identità discordanti restano separate; cambiare fonte non migra automaticamente
  libreria, download o progresso. Dati e logica dei siti restano nelle estensioni.

## 29 settembre 2026 — Menu delle uscite nell’agenda

- Pressione prolungata su una voce, anche nel calendario: pannello animato con
  copertina, titolo, data e azioni per scheda, episodio o capitolo.
- Accesso al contenuto disponibile distinto dalla scheda; per le uscite future
  il menu spiega che l’episodio o capitolo non è ancora disponibile.
- “Smetti di seguire” interrompe le uscite del titolo, anche quando riunisce più
  fonti. “Elimina solo questa voce” nasconde soltanto quell’uscita nell’agenda,
  mantenendo il seguito, la libreria e i download. Entrambe le azioni sono annullabili.
- Le voci nascoste restano tali dopo un riavvio, un cambio di orario o il ripristino
  delle impostazioni da backup; le uscite successive restano visibili.

## 29 settembre 2026 — Link che aprono i contenuti in Nyanime

- Condivisione delle schede tramite link `nyanime://open/v1`, aperti direttamente
  nell’app tramite l’estensione installata sul telefono del destinatario.
- Un unico pannello per schede, episodi e capitoli: scelta tra titolo, inizio
  dell’episodio/capitolo, minuto del player o pagina del lettore.
- Pulsanti Condividi/Copia sempre raggiungibili anche nel player orizzontale;
  disposizione compatta a due colonne sugli schermi larghi e bassi, con tutte
  le destinazioni subito visibili e contenuto scorrevole per i testi grandi.
- Aprire un episodio condiviso durante un’altra riproduzione attende il rilascio
  effettivo del vecchio player, evitando due inizializzazioni native sovrapposte.
- Azione di condivisione anche selezionando un singolo episodio o capitolo;
  link alla pagina distinto dalla condivisione dell’immagine nel lettore.
- Apertura del riferimento preciso, senza cercare un titolo simile; errori
  espliciti per estensione assente, link non valido e contenuto non disponibile.
- I link non includono URL di streaming, intestazioni, cookie, ID del database
  locale o dati delle stanze. Le normali azioni WebView/browser restano disponibili.

## 29 settembre 2026 — Notifiche delle uscite più leggibili

- Nome del titolo sulla prima riga compatta; nome completo a capo nei dettagli
  espansi, leggibile anche quando l'annuncio è lungo.
- Titoli espliciti: “Domani esce un nuovo episodio di…” e “Oggi esce un nuovo
  episodio/capitolo di…”, con plurali per le uscite multiple.
- Numero e ora in una riga breve; notifica espansa con data completa, trasmissione,
  piattaforme e dettagli disponibili. Orari nel fuso e nel formato scelto sul telefono.
- Avvisi di disponibilità distinti dagli annunci: una data di rilevamento o un
  recupero storico non diventano una pubblicazione di oggi. Orario di rilevamento
  indicato come tale, senza inventare l’ora di uscita dei capitoli.
- Struttura coerente anche per gli avvisi della libreria; azioni, canali, impostazioni
  di privacy e ricevute di consegna mantenuti.

## 29 settembre 2026 — Protezione laterale integrata

- Compatibilità rilevata dall’hardware e dalle API, senza vincolo sul firmware esatto.
- Protezione selezionabile per le schede dei titoli, con copertina, descrizione ed episodi o capitoli.
- Comando temporaneo nel lettore manga, condiviso con quello del player.
- Opzione “Solo in incognito” per le aree scelte, rispettando anche l’incognito della fonte corrente.
- Stato più chiaro nelle impostazioni e nei comandi della sessione: attesa, richiesta, sospensione ed errore.

## 29 settembre 2026 — Manga in altre lingue visibili inizialmente

- “Mostra manga in altre lingue” è ora attivo di default per chi non ha ancora
  salvato una scelta. Le preferenze già impostate rimangono rispettate.

## 29 settembre 2026 — Splash coerente con il tema scelto

- Su Android 12 e successivi, splash e sfondo iniziale rispettano la modalità
  Chiaro o Scuro scelta nell’app, anche se il telefono usa il tema opposto.
  Segui il sistema rimuove la forzatura e segue i cambiamenti di Android.
- Gestione del tema nativo separata da quella dell’icona; preferenze e conferma
  iniziale conservate. Sulle versioni precedenti rimane la compatibilità AppCompat.
- Il primo avvio dopo l’aggiornamento può mostrare ancora la splash precedente
  prima che Android registri la preferenza per gli avvii successivi.

## 29 settembre 2026 — Promemoria del giorno prima

- Avviso facoltativo 24 ore prima dell’episodio, attivo inizialmente e utilizzabile
  anche con il calendario abituale senza collegare AnimeSchedule. Disattivabile da
  Impostazioni → Libreria, senza perdere l’avviso all’orario della trasmissione.
- Un solo allarme Android per il prossimo avviso, riprogrammato dopo riavvio,
  aggiornamento, cambi dell’orario e dei permessi. Ricevute locali distinte per
  anticipo e trasmissione evitano duplicati anche tra fonti con lo stesso ID verificato.
- Recupero limitato degli avvisi ritardati, senza raffiche dopo lunghi periodi offline;
  notifiche bloccate non registrate come consegnate. Titoli esclusi, episodi già visti
  e modalità incognito rispettati.
- Prova del canale dei promemoria nello Stato del monitoraggio e controlli Android
  per gli avvisi puntuali. L’orario annunciato rimane distinto dalla disponibilità
  effettiva del video nella fonte.

## 29 settembre 2026 — Memoria della compilazione GitHub

- Profilo CI condiviso per preview e pull request: compilatore Kotlin nello stesso
  processo Gradle, memoria riservata aumentata e compilazione dei moduli senza
  parallelismo. Corretto il percorso che esauriva la memoria prima dei test.
- Rapporti dei test e diagnostica di compilazione conservati in caso di fallimento,
  senza allegare dump di memoria del processo.

## 29 settembre 2026 — AnimeSchedule facoltativo e configurazione guidata

- Collegamento opzionale, inizialmente disattivato, con guida nel browser interno,
  verifica del token e salvataggio cifrato sul dispositivo; nessun token incluso nell’APK.
- Guida con moduli adattati allo schermo, scelta Accedi/Crea account, passaggio
  automatico alle impostazioni API e importazione del token con un comando esplicito.
  Sito completo e inserimento manuale sempre disponibili; password mai lette dall’app.
- Consenso ai soli cookie necessari configurato prima dell’accesso; scelte già
  salvate sul sito conservate. Termini e verifiche di registrazione restano visibili.
- Moduli guidati senza larghezza aggiunta dai margini del sito: campi e collegamenti
  contenuti nello schermo stretto, messaggi di conferma della registrazione preservati.
- Orari RAW, SUB inglese e DUB inglese distinti, première, rinvii, date eccezionali
  e piattaforme integrate nell’agenda esistente e nelle schede anime.
- Conto alla rovescia della scheda e dettaglio degli orari coordinati con la
  trasmissione preferita; un rinvio senza data non conserva un vecchio conto alla rovescia.
- Associazione tramite ID di catalogo verificati, cache condivisa degli orari e rispetto
  dei limiti del servizio. Il catalogo abituale rimane disponibile senza configurazione.
- Link di catalogo senza schema HTTPS riconosciuti e controllati per dominio e ID;
  titoli assenti dal catalogo distinti dagli errori di rete. Recupero automatico delle
  associazioni fallite nella prova precedente, senza perdere il collegamento salvato.
- Errori di rete, servizio, formato e salvataggio distinti, con diagnostica priva di
  token e contenuti privati: un titolo non trovato non segnala il servizio irraggiungibile.
- Settimane future non ancora pubblicate trattate come dati assenti e conservate
  nella cache; restano disponibili le date lontane del catalogo abituale. La verifica
  del token e della settimana corrente continua a riconoscere i guasti del servizio.
- Coda di verifica corretta quando contiene sia titoli già associati sia associazioni
  da recuperare: l’ordinamento conserva la priorità senza bloccare il lavoro.
- Trasmissione preferita per promemoria e controlli mirati delle disponibilità;
  orario annunciato distinto dal contenuto effettivamente trovato nell’estensione.
- Widget “Le tue uscite” basato sulla stessa agenda locale anime e manga, con
  apertura diretta e rispetto della modalità incognito e della privacy delle notifiche.

## 29 settembre 2026 — Agenda futura e verifica degli orari

- Verifiche degli orari in una coda indipendente dagli aggiornamenti degli
  episodi: il primo controllo prosegue a piccoli gruppi fino a raggiungere
  tutti i titoli con identificatori, anche senza aprire le loro schede.
- Uscite future conservate nell’agenda anche quando il numero dell’episodio
  è già registrato localmente; date lontane e deduplicazione fra edizioni mantenute.
- Titoli senza ID esclusi dal controllo del catalogo degli orari e dai relativi
  avvisi. Il monitoraggio dei contenuti disponibili nella fonte continua.
- Verifiche in attesa distinte dagli ID non confermati e dagli errori di rete;
  i titoli coinvolti possono essere consultati direttamente dall’agenda.
- Fine agenda con un messaggio esplicito, senza una schermata vuota aggiunta
  allo scorrimento. Apertura su oggi mantenuta anche quando ci sono solo uscite passate.

## 29 settembre 2026 - Estensioni e distribuzioni protette

- Schermate Estensioni anime e manga con viste Installate e Catalogo, schede
  compatte e filtri per lingua, repository e supporto Home, anche con UI legacy.
- Provenienza e Home distinte: badge Adattata a Nyanime, Home integrata,
  Home parziale e Senza Home con spiegazioni nei dettagli.
- Aggiornamenti soltanto per pacchetto, firma, distribuzione e API compatibili.
  Le edizioni locali manuali e quelle protette non vengono sostituite dai cataloghi;
  repository duplicati non vengono scelti arbitrariamente.
- Mantieni questa versione nei dettagli, conservato nei backup e rivalidato
  contro la firma degli APK presenti dopo il ripristino.
- Contatori, notifiche e Aggiorna tutte usano la stessa politica. Un catalogo
  irraggiungibile mostra Controllo non riuscito e viene ricontrollato, senza
  dichiarare obsolete le estensioni locali.
- APK scaricati verificati crittograficamente prima di qualsiasi installatore,
  comprese le installazioni private; controlli di pacchetto, versione, API,
  firma e descrittore della distribuzione.

## 28 settembre 2026 — Identificatori verificati per gli orari

- Il tracking già salvato ha precedenza sugli identificatori forniti dalle
  estensioni. Un ID AniList inesistente può essere risolto tramite l’ID
  MyAnimeList collegato, soltanto dopo conferma della corrispondenza nel catalogo.
- Identificatori contraddittori non selezionano un altro titolo in silenzio.
  Una corrispondenza assente viene distinta da un errore di connessione.
- Il filtro Tutti usa ora il violetto uniforme, con riempimento soltanto
  quando selezionato; Anime e Manga mantengono arancione e celeste.

## 28 settembre 2026 — Recupero delle verifiche degli orari

- Una richiesta degli orari fallita non eredita più le sei ore di validità
  della verifica precedente: il monitor e la scheda usano la stessa regola
  per ritentare, conservando le date salvate e limitando richieste ravvicinate.
- Quando l’ultimo orario è passato, il calendario può ricontrollare il titolo
  senza aspettare la scadenza dei metadati. Un cambio dell’orologio non blocca i tentativi.
- Gli avvisi degli orari anime non compaiono nel filtro Manga. Il controllo
  riuscito rimuove l’avviso; gli errori sono registrati per poterli diagnosticare.
- Richieste degli orari con il DNS configurato nell’app e il limite condiviso
  con Home e tracking, evitando un secondo flusso indipendente di richieste.

## 28 settembre 2026 — Filtri delle uscite e verifica della navigazione

- Filtri Tutti/Anime/Manga centrati e distribuiti sulla larghezza disponibile,
  con etichette leggibili anche negli schermi stretti e con caratteri grandi.
- L’interno dei filtri si colora soltanto quando sono selezionati.
- Selezione dei filtri con transizione morbida del riempimento, senza spostare
  i pulsanti o ridisegnare il testo. Animazione ridotta rispettata.
- Corretto il test della navigazione rimasto precedente all’aggiunta di Uscite,
  che impediva la build GitHub; verifica delle destinazioni conservata anche per
  le vecchie preferenze di navigazione.

## 28 settembre 2026 — Agenda continua, aperta su oggi

- L’agenda si apre su oggi: scorrendo verso l’alto si consultano le uscite
  precedenti e verso il basso quelle future, senza il limite di sette giorni.
- Filtri e scelta Agenda/Calendario sempre accessibili; Oggi riporta al punto
  corrente. Se oggi non ci sono uscite, resta una posizione chiara nella lista.
- Il giorno scelto nel calendario diventa il punto di apertura dell’agenda.
  Gli aggiornamenti dei dati mantengono la posizione senza riportare la lista all’inizio.
- Consultazione basata sui dati locali e sulle date effettive, senza nuove
  richieste alle fonti durante lo scorrimento.

## 28 settembre 2026 - Uscite seguite e calendario affidabile

- Nuovo comando Segui nelle schede anime e manga, con preferenze separate
  per nuove disponibilità e promemoria delle trasmissioni annunciate.
- Aggiornamenti automatici attivi inizialmente, controlli mirati, recupero
  persistente degli errori e rotazione delle librerie grandi, senza aggiornare
  tutto a ogni apertura o fermarsi ai primi titoli.
- Vista Agenda con schede raggruppate per giorno e Calendario con giorni
  vuoti selezionabili;
  date annunciate tramite ID verificati, distinte dalla disponibilità nella fonte.
- Uscite accanto a Libreria nella navigazione principale. Anime e manga insieme,
  filtri Tutti/Anime/Manga e preferenza per consultarli separatamente.
- Schede anime con bordo sfumato arancione; manga in celeste, con contrasto
  adattato al tema chiaro e scuro e indicazione testuale del tipo di contenuto.
- Agenda caricata dai dati locali, senza attendere una verifica in rete di tutta
  la libreria; giorni e mesi si cambiano senza nuovi controlli remoti.
- Corretta la data dei capitoli storici: l'agenda usa soltanto date annunciate o
  di pubblicazione fornite dalla fonte; il rilevamento nell'app non diventa
  un'uscita di oggi. Riparazione degli avvisi esistenti al controllo del titolo,
  senza cancellare letture, capitoli o ricevute delle notifiche.
- Uscite dello stesso anime riunite tra fonti ed edizioni tramite i criteri della
  Home e identificativi verificati; una scheda con scelta di fonte ed edizione, senza
  confondere le stagioni. Visto in una fonte non resta annunciato in un'altra.
- Avvisi salvati insieme agli episodi e capitoli, deduplicazione e recupero
  quando le notifiche vengono riabilitate. Test degli avvisi e stato dei controlli
  nelle impostazioni; promemoria puntuali facoltativi.
- Preferenze Segui incluse nei backup, mantenimento dei progressi quando cambiano
  gli indirizzi e coordinamento tra scheda e aggiornamento in background.
- Ottimizzatore Android aggiornato alla versione compatibile con Kotlin 2.4,
  per completare correttamente gli APK firmati.

## 28 settembre 2026 — Protezione laterale del display, verifica hardware

- Aggiunta una protezione hardware facoltativa in Impostazioni → Sicurezza,
  con selezione separata di video e sottotitoli, lettura manga, cronologia,
  ripresa e ricerca. Il comando principale parte spento.
- Opzione aggiuntiva per le aree NSFW dichiarate dal contenuto o dall’estensione,
  senza oscuramento frontale o riconoscimento dei nomi delle fonti.
- Comando temporaneo in Player → Altro; geometria aggiornata insieme alla finestra
  e gestione degli errori separata dalla riproduzione. Preferenze incluse nei backup,
  senza esportare lo stato hardware o l’avviso locale.
- Regione gestita tramite una vista dedicata, senza spostare o ridisegnare i contenuti
  dell’app. Gestione dei margini richiesti dal pannello e aggiornamenti della posizione
  senza ripetere l’attivazione hardware.
- Modulo indipendente con adattatore Samsung originale. Sui dispositivi incompatibili
  il comando è disabilitato. Le build ordinarie mantengono disabilitate le modalità
  senza verifica fisica; l’APK locale di prova permette la valutazione sul Galaxy
  S26 Ultra. PiP, multifinestra e display esterni restano esclusi.
  [Requisiti e validazione](docs/privacy-display.md).

## 28 settembre 2026 — ModernUI chiara e scelta iniziale del tema

- Tre aspetti selezionabili: Scuro con marchio rosso, Chiaro con superfici bianche
  e marchio Arancio solare, oppure Segui il sistema. Palette condivise fra
  Compose, componenti nativi e barre Android; copertine e marchi delle fonti
  conservano i propri colori, con sfumature adatte al contrasto di ciascun tema.
- Al primo avvio compare una schermata essenziale con le tre anteprime e
  «Continua». Preseleziona la preferenza esistente, oppure Sistema nelle nuove
  installazioni. Dopo la conferma non ricompare nei successivi avvii e aggiornamenti;
  la scelta e il completamento vengono salvati insieme sul dispositivo.
- La configurazione mantiene la scelta in corso durante la ricreazione della
  schermata e riprende i collegamenti di apertura dopo la conferma. Il vecchio
  passaggio del tema nell'onboarding è stato rimosso per evitare richieste doppie.
- Impostazioni → Aspetto usa le nuove card di Nyanime. Conservati lingua,
  manga in altre lingue, modalità tablet, schermata iniziale, formato data e
  date relative. AMOLED è disponibile soltanto nello scuro; lo sfondo delle
  pagine manga mantiene le proprie impostazioni di lettura.
- Anche l'icona Android segue l'aspetto rosso o arancione e si riallinea
  all'avvio. I tempi di aggiornamento dipendono dal launcher; nei test Samsung
  il cambio d'icona può chiudere l'app una volta, con scelta già salvata alla riapertura.
- Card, anteprime e controlli si adattano agli schermi stretti e ai caratteri
  grandi, rispettando la riduzione delle animazioni. Nessuna modifica ai
  contratti delle estensioni.

## 28 settembre 2026 — Home continua e caricamenti più morbidi

- Le categorie della Home condividono una sola testata: cambia soltanto il
  contenuto sottostante con una dissolvenza, senza scorrimento laterale della pagina.
- Hero e righe in caricamento usano skeleton con una pulsazione discreta e
  dimensioni adattate allo schermo e al testo. I dati già disponibili restano
  visibili durante gli aggiornamenti; errori e sezioni vuote non restano in caricamento.
- Conservati posizione di scorrimento, ricerca, loghi delle estensioni, ripresa
  e novità di ogni Home, inclusa Manga. La riduzione delle animazioni è rispettata.

## 28 settembre 2026 — Scegliere la stagione dal manga

- La scelta degli anime collegati a un manga mostra i capitoli documentati di
  inizio e fine per ogni stagione, in un riquadro compatto sotto titolo e anno.
  Sono conservati anche i riferimenti a una pagina interna al capitolo.
- I due percorsi anime/manga condividono dati e cache di catalogo. Film, speciali
  e stagioni senza un riferimento pertinente non ereditano intervalli di altre
  stagioni; l'apertura del titolo continua a verificarne gli ID nelle estensioni.

## 28 settembre 2026 — Home torna prima in cima

- Toccando di nuovo Home, una pagina scorsa torna in cima; il cambio categoria
  avviene soltanto quando si è già all'inizio della pagina.
- Lo stesso comportamento vale per le Home video, Manga e catalogo. Durante
  il ritorno in cima, tocchi ripetuti non cambiano categoria.

## 28 settembre 2026 — Collegamenti anime e manga sempre compatti

- Rimossi il pulsante di espansione e i dettagli estesi. Restano il gradiente,
  i riferimenti di stagione e capitolo e l'apertura diretta del titolo.
- Se sono disponibili più adattamenti o copie, una breve scelta al tocco conserva
  tutte le destinazioni senza ingrandire la scheda.

## 28 settembre 2026 — Dettagli manga senza ripetizioni

- La scheda espansa mantiene i riferimenti compatti di stagione, inizio e fine
  una sola volta. Rimossi i paragrafi duplicati dai dettagli; restano le copie
  alternative, la copertina e le azioni di apertura.

## 28 settembre 2026 — Capitoli della stagione aperta

- La scheda compatta anime/manga mostra due righe con stagione, inizio/fine e
  capitolo documentato. Il numero si riferisce alla stagione aperta nella scheda.
- Riconosciuti anche i riferimenti «S1» senza episodio e i campi con più stagioni:
  i capitoli di un'altra stagione o dell'intera serie non vengono riutilizzati
  come fine di un sequel. Gli archi con riferimenti pertinenti hanno la precedenza.
- Conservati il gradiente, l'apertura diretta del manga e il controllo circolare
  per espandere e richiudere i dettagli.

## 28 settembre 2026 — Riferimenti manga a colpo d'occhio

- La scheda compatta mostra subito il capitolo iniziale e l'ultimo capitolo
  documentato, quando disponibili. Il gradiente è condiviso con la versione
  espansa, mantenendo un solo controllo circolare per aprire e richiudere i dettagli.
- Il passaggio dal manga all'anime verifica l'identità della copia nei metadati
  dell'estensione: vecchie associazioni di tracking da sole non fanno comparire
  adattamenti diversi tra le copie dell'anime.

## 28 settembre 2026 — Dal manga all'anime

- Collegamenti anime/manga compatti di default: toccare la scheda apre il titolo,
  il pulsante circolare espande i dettagli e rimane in basso a destra per richiuderli.
  Il capitolo viene mostrato solo quando esiste un punto di continuazione verificato.
- Le schede manga mostrano gli anime collegati tramite ID di catalogo, senza
  richiedere libreria o tracking. Le estensioni compatibili possono aprire una
  copia verificata direttamente; stagioni, film e altri adattamenti restano
  distinti e selezionabili.
- I collegamenti attraverso la novel originale sono indicati esplicitamente.
  Non viene inventato un episodio dal numero del capitolo: aprire la scheda
  anime conserva il progresso già presente.
- La nuova card conserva il suo stato durante la navigazione e rispetta le
  animazioni ridotte. Un errore del catalogo permette di riprovare nella scheda.

## 28 settembre 2026 — Manga per archi delle opere nate come novel

- Quando un anime deriva da una novel, Nyanime mostra anche i manga collegati
  alla stessa opera, distinguendo il rapporto indiretto da un adattamento diretto.
  Gli intervalli stagione/episodio documentati aiutano a scegliere l'arco, senza
  usarli per indovinare un capitolo esatto.
- Corretto il mantenimento dei contratti di ricerca nelle build ottimizzate:
  i collegamenti forniti dalle estensioni possono aprire anche manga mai aggiunti
  alla libreria e senza un tracking già impostato.
- Le estensioni che includono copie delle interfacce facoltative usano ora la
  definizione fornita dall'app, così la ricerca per ID e i link diretti vengono
  riconosciuti anche dopo il caricamento degli APK.
- Tornando dal manga, la card dell'anime conserva copertina, copie e riferimenti
  già caricati. Le richieste in corso proseguono con la scheda e gli aggiornamenti
  non rimuovono temporaneamente i risultati precedenti.

## 28 settembre 2026 — Dall'anime al manga

- La scheda anime riconosce i manga collegati tramite ID di catalogo e, quando
  l'estensione lo fornisce, apre direttamente una copia verificata senza cercarla
  per titolo.
- I punti di inizio e fine adattamento compaiono quando sono documentati. Il
  passaggio a un capitolo preciso è proposto soltanto se il riferimento
  all'episodio è esplicito; non vengono stimate corrispondenze mancanti.
- Le estensioni possono fornire ricerca per ID e link correlati tramite contratti
  generici, mantenendo nell'estensione ogni logica del rispettivo sito.

## 27 settembre 2026 — Categorie Home adattive

- Le categorie della Home si distribuiscono su una o due righe secondo la larghezza
  dello schermo. Se sono ancora troppe, restano raggiungibili scorrendo lateralmente.
- Una pressione lunga porta una categoria all'inizio e conserva l'ordine scelto.
  Toccare nuovamente Home nella barra inferiore passa alla categoria successiva,
  tornando alla prima dopo l'ultima.

## 27 settembre 2026 — Esplorazione manga e filtro lingue

- La sezione personale si chiama ora «Libreria». Nella Home manga i generi sono
  subito disponibili sotto l'intestazione, con chip e ricerca nell'elenco completo.
- Etichette equivalenti in italiano e inglese confluiscono in un solo genere;
  ogni fonte riceve comunque il proprio filtro originale.
- La ricerca usa una corsia separata dai caricamenti della Home e presenta i
  risultati di ogni fonte appena arrivano, senza attendere le altre.
- La preferenza già presente per le altre lingue ora vale anche in ricerca,
  elenco fonti, Libreria, cronologia e novità manga. Nascondere un titolo non
  cancella letture, download o dati salvati.

## 27 settembre 2026 — Compatibilità completa con le estensioni manga 1.6

- Le schede manga, la biblioteca, il tracking, la ricerca e le stanze usano ora
  l'aggiornamento combinato di dettagli e capitoli delle estensioni 1.6.
  L'apertura di un titolo non invia più richieste ai vecchi endpoint separati.
- Le estensioni meno recenti continuano a funzionare tramite il contratto
  precedente. Il modello e i metodi delle nuove estensioni sono disponibili
  nell'API generica dell'app, senza logica legata a una fonte.
- Le richieste simultanee per lo stesso manga vengono coordinate, evitando
  aggiornamenti concorrenti tra scheda, Home e tracking.

## 27 settembre 2026 — Home Manga e librerie unite

- Manga entra nella Home moderna accanto alle categorie video, con sezioni, ricerca
  nel catalogo completo e filtri forniti dalle estensioni installate. I risultati
  di fonti diverse si uniscono soltanto quando condividono ID pubblici concordi;
  i titoli ambigui restano separati e la fonte predefinita si può cambiare.
- La barra inferiore riunisce la libreria anime e la biblioteca manga in
  «Biblioteca & Libreria». La vecchia interfaccia manga viene disattivata.
- Una preferenza nasconde dalla Home Manga i cataloghi non italiani, senza
  rimuovere titoli già presenti nella biblioteca personale.
- Il tracking manga continua ad avviarsi dalla lettura del primo capitolo;
  i tentativi simultanei sullo stesso titolo vengono serializzati per evitare
  associazioni duplicate.
- L'interfaccia generica delle estensioni manga ora conserva i metadati
  temporanei richiesti dai client più recenti per caricare titoli e capitoli.

## 27 settembre 2026 — Prossima uscita nella scheda del titolo

- Il tempo che manca al prossimo episodio compare anche sotto lo studio,
  prima dello stato e della fonte. Usa lo stesso conto alla rovescia della lista
  episodi e rispetta la preferenza che ne controlla la visibilità.
- Un refresh conserva la data nota se il tracker non è ancora disponibile o la
  richiesta alla rete fallisce. La aggiorna soltanto dopo una risposta valida.

## 27 settembre 2026 — Prossima uscita degli episodi

- La previsione del prossimo episodio è ora una scheda discreta nella lista:
  mette in primo piano data e ora locali, seguite dal tempo rimanente; il numero
  e il titolo dell'episodio restano leggibili sotto, senza la vecchia scritta rossa.
  Il tempo si aggiorna quando cambia il minuto e la scheda si adatta agli
  schermi stretti e alle griglie della UI moderna e legacy.

## 27 settembre 2026 — Home Anime con più fonti

- Le sezioni e la ricerca della Home Anime combinano i risultati delle estensioni
  installate. Le schede dello stesso titolo vengono unite quando condividono
  un identificatore di catalogo affidabile; le fonti restano selezionabili
  anche quando le schede arrivano in pagine diverse. Identificatori in
  conflitto impediscono la fusione.
- Un tocco apre subito la fonte predefinita. Il selettore sulla scheda permette
  di cambiarla e, se desiderato, ricordarla per quel titolo. I filtri mostrano
  le opzioni disponibili nelle fonti attive.
- Le estensioni possono fornire classifiche, un indice dei generi, titoli ed
  episodi casuali, l'episodio preciso di una scheda e titoli simili. Quando una
  Home usa più fonti, il logo di un singolo sito non sostituisce Nyanime.
- La barra dei generi mostra di nuovo tutte le categorie disponibili. Le due
  scelte casuali sono raccolte in Esplora, fuori dalla barra dei generi.
- Uscendo dal player dopo l'apertura diretta di un episodio, la scheda conserva
  lo stato dell'avvio e non rilancia una seconda volta il player.

## 27 settembre 2026 — Tracking dei titoli nella libreria

- Un anime o manga aggiunto alla libreria viene cercato in background nei
  tracker configurati, anche prima del primo episodio o capitolo. Quando
  l'associazione è certa, lo stato iniziale resta «Da vedere» o «Da leggere»
  nei tracker che lo supportano e il progresso resta a zero finché non inizi
  davvero. Le associazioni già presenti non vengono duplicate.
- Un controllo correttivo, eseguito una sola volta, esamina anche i titoli
  non ancora iniziati che erano già in libreria. La voce «Riesamina la
  libreria» permette di riprovare manualmente i titoli rimasti senza un
  collegamento certo.

## 27 settembre 2026 — Aggiornamenti progressivi di episodi e Home

- Aprendo una scheda anime, l'elenco degli episodi salvato viene mostrato subito e
  controllato in sottofondo con la sua estensione, senza dover trascinare per aggiornare.
- Nella Home i titoli visti di recente vengono controllati a rotazione, compresi
  quelli che aspettano un episodio per riapparire in «Continua a guardare».
  Nessun titolo viene escluso perché la lista è lunga; quelli nascosti non
  partecipano. I titoli mai controllati o più arretrati passano per primi;
  a parità di attesa hanno precedenza le serie in corso. Limiti persistenti
  per titolo e per fonte evitano raffiche di
  richieste anche dopo il riavvio dell'app.
- Le sezioni della Home continuano a ricevere i dati dalle estensioni. Il controllo
  della libreria resta legato soltanto all'aggiornamento periodico configurato
  nelle impostazioni o all'azione manuale. Il controllo automatico prosegue
  con piccoli lotti a distanza di almeno un'ora finché ha esaurito i titoli
  arretrati; dopo una lunga assenza riparte alla riapertura. Usa lo stesso
  limite per fonte. Quello manuale resta completo quando viene richiesto
  esplicitamente.
- Un vecchio controllo generale avviato automaticamente dalla Home viene fermato
  dopo l'aggiornamento, senza interrompere i controlli periodici o manuali.

## 26 settembre 2026 — Riesame manuale del tracking

- In Impostazioni → Tracking, «Riesamina i titoli iniziati» riprova anime e manga
  guardati o letti che non sono ancora collegati ai servizi configurati. Si può
  avviare anche dopo il recupero iniziale e con il tracking automatico spento.
- I collegamenti già presenti restano intatti. La schermata mostra l'avanzamento,
  i nuovi collegamenti e se il controllo è stato interrotto.

## 26 settembre 2026 — Riproduzione in finestra

- Il video continua a riprodursi quando si passa alla modalità picture-in-picture:
  la pausa avviene solo quando il player esce davvero dallo schermo.
- Corretto il ridimensionamento che poteva lasciare metà finestra nera al primo
  ingresso in picture-in-picture, specialmente uscendo dal player orizzontale.

## 25 settembre 2026 — Schermata Novità

- Lo scorrimento orizzontale tra Anime e Manga resta continuo: il tema della
  pagina manga non ricrea più l'intera schermata a metà gesto.
- Filtri e stato vuoto hanno una presentazione più chiara, con conteggi separati
  per i titoli ancora da vedere o leggere e per tutti gli episodi o capitoli.

## 25 settembre 2026 — Installazione degli aggiornamenti

- Quando il download OTA termina, un avviso nell'app offre «Installa ora» e
  «Non ora». L'APK pronto resta accessibile in Altro anche dopo aver lasciato
  la schermata delle novità; l'avviso scompare quando la versione è installata.

## 25 settembre 2026 — Novità nella Home

- Un indicatore circolare appare nella Home anime o manga soltanto per novità non
  ancora viste. Ha il colore delle altre icone della barra superiore. Toccandolo,
  la pagina scorre fino alla sezione «Le tue novità».
- Per impostazione iniziale l'indicatore si spegne anche quando la sezione entra
  nello schermo scorrendo a mano; questa scelta si può disattivare nelle
  impostazioni Libreria. I titoli restano nella sezione finché non vengono
  aperti o ignorati; le novità arrivate dopo riattivano l'indicatore.

## 25 settembre 2026 — Recupero iniziale e sigle personalizzate

- Se un tracker è già configurato, Nyanime collega in background anche i titoli
  iniziati prima dell'aggiornamento. Il recupero si conclude una sola volta per
  installazione; se viene interrotto, riprende al successivo avvio dell'app.
- In Altro del player si sceglie se saltare la sigla automaticamente come regola
  generale e, per l'anime aperto, se ereditarla oppure fare un'eccezione.
  La scelta viene applicata senza riavviare il video.
- Quando viene associato un titolo già visto o letto, il progresso locale
  riconosciuto viene riportato al tracker anche in presenza di episodi o
  capitoli non consecutivi.

## 25 settembre 2026 — Collegamento automatico ai tracker

- Quando inizia la visione o la lettura, Nyanime collega in background il titolo
  ai tracker già configurati sul dispositivo e aggiorna poi il progresso.
  La ricerca usa prima gli identificativi forniti dall'estensione, poi titoli
  alternativi e corrispondenze prudenti; risultati ambigui restano da confermare.
- AniSkip può ricavare l'identificativo dell'anime senza richiedere prima un
  collegamento manuale al tracker. Le impostazioni disattivate esplicitamente
  restano rispettate.
- Nelle nuove installazioni l'aggiunta alla libreria non apre più di default
  la finestra di collegamento manuale. La scelta già salvata resta invariata.

## 25 settembre 2026 — Novità personali e aggiornamenti nell'app

- Le schermate Aggiornamenti già esistenti mostrano per prime le novità da
  vedere o leggere dei titoli nella libreria e di quelli guardati o letti di recente.
  Raggruppano le novità per titolo, così molti episodi o capitoli della stessa
  opera non riempiono l'elenco. Aprire o ignorare un titolo lo toglie dalle
  novità correnti; la scheda Tutti mantiene la cronologia completa.
- Nelle Home, una campanella apre Novità; la sezione orizzontale «Le tue novità»
  compare soltanto quando ci sono episodi o capitoli pertinenti da scoprire.
  Il controllo periodico include fino a 20 titoli recenti della cronologia anche
  se non sono nella libreria, senza avviare download automatici per questi titoli.
  I contenuti già presenti prima della prima visione o lettura non vengono
  scambiati per nuove uscite.
  La ricerca delle novità
  usa la data in cui l'app ha rilevato episodi e capitoli nuovi, anche quando
  la fonte non indica una data di pubblicazione affidabile.
- Il calendario evidenzia il giorno selezionato e mostra subito quante uscite
  sono previste, anche quando il giorno scelto è vuoto.
- Nella schermata Info si può attivare il download dell'aggiornamento nell'app:
  avanzamento visibile, possibilità di annullare o riprovare e pulsante
  Installa aggiornamento al termine. L'opzione è attiva inizialmente e conserva
  la scelta di chi l'ha già disattivata. Android chiede comunque la conferma.

## 24 settembre 2026 — Stanze: avvio preparato e durate dei video

- L'owner può attivare nelle opzioni della stanza il precaricamento prima del
  primo avvio di ogni episodio. Ogni telefono carica in parallelo fino a 15
  secondi di video; se la stima non è disponibile, usa un margine breve.
  L'attesa massima per il precaricamento è 15 secondi.
- Le piccole differenze di durata riportate da manifest e qualità diverse dello
  stesso episodio non bloccano più la stanza. Durante l'apertura, una durata
  ancora sconosciuta viene mostrata come preparazione e non come video diverso.

## 24 settembre 2026 — Pulizia dei dati disattivati

- La pulizia dei vecchi dati di profili e sincronizzazione termina correttamente
  anche nell'APK ottimizzato, senza un avviso a ogni avvio.

## 23 settembre 2026 — Stanze con relay verificati

- Le stanze usano due relay che hanno superato una prova di scambio cifrato in
  entrambe le direzioni, senza account. I precedenti rifiutavano gli invii.
- La connessione viene mostrata come pronta solo dopo che il relay accetta un
  messaggio di prova; rifiuti e limiti di frequenza non fanno più lampeggiare lo
  stato della stanza come se fosse disponibile.
- Una stanza vuota non invia più aggiornamenti periodici inutili e le richieste
  di ingresso ripetute sono meno frequenti.
- Un singolo aggiornamento di presenza perso non ferma più entrambi i video;
  con l'opzione di attesa attiva, il buffering reale continua a mettere in pausa la stanza.
- Le nuove stanze tollerano fino a 5 secondi di caricamento dell'ospite
  prima di fermare anche chi ospita; chi era rimasto indietro si riallinea quando
  torna pronto. La pausa immediata per tutti resta disponibile nelle opzioni.

## 23 settembre 2026 — Inviti brevi coerenti in tutta la stanza

- Link, messaggio condiviso e QR del creatore ora contengono il codice stanza a
  otto cifre, anche quando si invita dal lettore manga. L'amico chiede di entrare
  e il creatore conferma l'accesso.
- La schermata delle stanze mette in primo piano Crea/Entra, codice e richieste;
  il nome facoltativo resta modificabile senza affollare il percorso iniziale.
- I link completi delle versioni precedenti continuano ad aprirsi.

## 23 settembre 2026 — Accesso alle stanze con codice breve

- Per invitare un amico in una stanza video o manga ora bastano otto cifre temporanee.
  Chi crea la stanza vede la richiesta e può accettarla o rifiutarla.
- L'ingresso mostra lo stato del collegamento; gli inviti completi già condivisi
  continuano a funzionare.

## 23 settembre 2026 — Rimozione della traduzione manga sperimentale

- Rimossi pulsanti, impostazioni e componenti della traduzione manga offline.
- Dopo l'aggiornamento, l'app elimina i modelli e la cache della traduzione scaricati
  in precedenza, senza alterare manga, progressi o annotazioni delle stanze.

## 23 settembre 2026 — Annotazioni condivise più affidabili

- Nella stessa stanza video e manga, schizzi e note sulla pagina si riallineano
  dopo disconnessioni e messaggi fuori ordine; gli interventi nascosti non ricompaiono.
- Ogni partecipante può nascondere e ripristinare i propri interventi per tutti;
  il creatore può moderare quelli della stanza. La visibilità locale resta separata.
- Dopo una chiusura imprevista, la schermata offre il rientro esplicito nella stanza
  con le annotazioni temporanee salvate in forma cifrata sul dispositivo.
- La stanza mostra temporaneamente a che punto del video stanno guardando gli altri.

## 23 settembre 2026 — Pressione prolungata nel player personalizzabile

- Nelle impostazioni dei gesti puoi scegliere cosa accade tenendo premuto sul video:
  velocità temporanea 2× (predefinita), 1,5× o 1,25×, cattura schermata oppure
  nessuna azione.
- Rilasciando il dito viene ripristinata la velocità che avevi scelto prima del
  gesto; la velocità temporanea resta disabilitata nelle stanze.

## 23 settembre 2026 — Backup trasferibili e note OTA per versione

- I nuovi backup manuali e automatici usano `.nyabk`; il ripristino continua ad
  accettare i vecchi file `.tachibk` e il formato interno resta compatibile.
- Esporta dati offre un backup completo di librerie, progressi e segnalibri di
  visione e lettura anche fuori dalla libreria, cronologia, impostazioni, preferenze delle
  fonti ed estensioni. Video e pagine scaricati restano esclusi.
- Sul nuovo dispositivo vengono ripristinate anche le preferenze non ancora
  inizializzate; categorie, impostazioni e titoli sono ripristinati nell'ordine
  corretto. Le categorie anime e manga sono mappate separatamente e le stagioni
  recuperano episodi e progressi, anche quando non erano state aggiunte
  singolarmente alla libreria.
- I titoli nascosti da “Continua a guardare” sono ricollegati sul nuovo telefono
  tramite riferimento alla fonte e al titolo, senza copiare ID del database.
- Le preferenze che non si riescono a ripristinare compaiono nel resoconto degli
  errori invece di essere ignorate senza avviso.
- Le note di aggiornamento OTA mostrano soltanto le novità aggiunte dal tag
  precedente, senza riproporre ogni volta l'intera sezione del changelog.

## 23 settembre 2026 — Licenze, aiuto e aggiornamenti OTA

- La testata Info ora mostra il marchio Nyanime senza lo spazio vuoto sopra l'icona.
- Nella Home moderna, logo, Stanze e Cerca restano sulla stessa riga anche sugli
  schermi stretti; il pulsante Cerca mantiene la sua forma a pillola.
- La pagina delle licenze delle librerie usa un elenco compatibile con la versione
  Compose dell'app, con ricerca e dettaglio della licenza, evitando il crash.
- Il pulsante Aiuto e le guide collegate aprono la documentazione Nyanime.
- La schermata di aggiornamento mostra le novità di questa release, prese dal
  changelog, anziché istruzioni tecniche per scegliere un APK.
- Gli APK delle nuove release mantengono nomi riconosciuti anche dalle versioni
  già installate, per consentire l'aggiornamento OTA diretto.

## 23 settembre 2026 — Link Nyanime e aggiornamenti OTA

- I pulsanti Sito web, Discord e GitHub nella schermata Info mostrano un avviso
  finché i relativi canali non saranno disponibili; rimossa la voce per tradurre.
- Licenze e privacy aprono i documenti di questo repository.
- Le release preview firmate vengono pubblicate automaticamente da `main` con APK
  per ogni architettura, riattivando la verifica degli aggiornamenti OTA.
- Supporto alle estensioni video con versione 17 della libreria, conservando le
  versioni precedenti e gli identificatori tecnici necessari alla compatibilità.

## 22 settembre 2026 — Sync disattivato e avvio Ultra più accessibile

- Sync personale rimosso dalle impostazioni e disattivato anche sulle installazioni
  già configurate. Pulizia automatica di chiavi, archivi privati e code di sync/community;
  librerie, progressi locali, download e stanze restano disponibili.
- Codice social e sync inutilizzato escluso dall’APK ottimizzato.
- Ultra può elaborare a schermo acceso e a batteria; le due restrizioni diventano
  facoltative. Soglie termiche meno prudenti, carico GPU ancora dosato e attese
  ricontrollate senza accumulare ritardi crescenti. Aggiornamento delle code esistenti.

## r8200 — Guarda insieme e controlli del player

Riferimenti: `bc660e1b0`, `7ee9e9a4f`, `51d484a1e`, `83115106a`.

- Play con risposta immediata, animazione play/cuore/cerchio e conto alla rovescia
  integrato nel video.
- Iniziali e stato dei partecipanti, indicazione di chi sta ancora caricando
  e messaggi brevi sulle azioni manuali.
- Scadenza dei comandi non confermati, nuovi tentativi controllati e riconnessione
  senza riapplicare richieste di episodi precedenti.
- Barra della stanza nella navigazione e nei dettagli, con episodio, partecipanti
  e ritorno al player.
- Caricamento individuale, inviti al salto e scheda del prossimo episodio più compatti,
  conservando legacy, riduzione del movimento e timer.
- Anime4K disabilitato per l'intera stanza, con ripristino delle scelte individuali
  all'uscita.
- Protezioni del ciclo di vita nativo durante apertura e chiusura, anche senza stanze.

Per questa revisione: 411 test automatici superati, quattro facoltativi saltati,
85 render Compose generati, build preview e controlli di firma/allineamento completati.
Non è stata eseguita una nuova prova con due telefoni su questa revisione.

## Stanze cifrate e sincronizzazione

Riferimenti: `2ec1bd009`, `51ba63dfb`, `6019330d5`.

- Creazione e ingresso tramite codice, link e QR, fino a otto partecipanti.
- Trasporto cifrato su relay Nostr, senza account Nyanime o apertura di porte.
- Episodio scelto dall'host e apertura automatica attraverso l'estensione locale.
- Play, pausa, seek e velocità condivisi, attesa della disponibilità,
  correzioni temporali graduali e recupero dopo perdita della connessione.
- Protezioni per timer, focus audio e background; salti e prossimo episodio coordinati.

## Home manga delle estensioni

Riferimento: `10fb2a381`.

- Home opzionale accanto alla biblioteca, basata sulle capacità dell'estensione.
- Aggiornamenti, classifiche, metadati di presentazione e azioni dei capitoli.
- Apertura del capitolo esatto e “Continua a leggere”.
- Lettore, tema manga, librerie e avanzamento mantenuti.

## ModernUI, navigazione e strumenti del player

Riferimenti: `27b30551a`, `f4455e11c`, `a7364253d`, `f5c1210ca`,
`73a899d03`, `9575ea5b3`, `f70647847`, `d91acfa62`, `b1f9a8e69`.

- Nome e icona Nyanime, presentazione video cinematografica e tema manga indipendente.
- ModernUI attiva di default, con ritorno alla presentazione legacy dalle impostazioni.
- Transizioni tra locandina e dettagli, ritorno, sfondi, testi e pannelli.
- Segnaposto delle copertine, aggiornamenti che conservano il contenuto e recupero
  delle immagini tramite l'estensione.
- Selettori centrati quando possibile e indicatore di refresh legato al trascinamento.
- Interruttore per l'avvio automatico di Smart, mantenendo l'attivazione manuale.
- Timer ridisegnato, durate rapide, memoria della durata personalizzata, estensione,
  fine episodio e priorità rispetto all'autoplay.
- Scheda del prossimo episodio e miglioramenti dell'integrazione AniSkip esistente.

## Cast e telecomando persistente

Riferimento: `c5ef563fe`.

- Google Cast e UPnP/DLNA, scoperta sulla rete locale e relay dei contenuti supportati.
- Telecomando, volume, avanzamento, coda episodi, notifica e navigazione durante il Cast.
- Cambio del video e ritorno alla riproduzione locale con posizione.
- Luminosità sui ricevitori DLNA che espongono il controllo; limiti documentati
  per codec, sottotitoli, DASH e shader locali.

## Scoperta, Anime4K e affidabilità

Il lavoro precedente comprende:

- Catalogo video, associazione automatica a titolo e stagione, fallback Kitsu,
  calendario e Home dichiarative delle estensioni.
- “Continua a guardare” indipendente dai feed remoti e gestione dei titoli nascosti.
- Anime4K integrato in mpv, Smart con misure del rendering, calibrazione,
  telemetria limitata e verifica degli shader.
- Ricerca globale con annullamento e concorrenza limitata, cache e retry per sezione.
- Download HTTP riprendibili quando validabili, controllo dello spazio,
  recupero dei link e protezioni dei backup.
- Aggiornamenti delle estensioni deduplicati, scelta corretta degli allegati APK.
- Correzioni PiP/JNI, demuxer HLS per ripresa e seek, accessibilità con testo grande,
  verifiche di rendering e strumenti di misura delle prestazioni.

Dettagli e vincoli sono nelle [guide tecniche](docs/README.md).

## Storico upstream

Il precedente changelog AniYomi è conservato integralmente in
[docs/history/aniyomi-changelog.md](docs/history/aniyomi-changelog.md).
La sua sezione “Unreleased” appartiene allo storico upstream importato:
non è una roadmap o una dichiarazione di release di Nyanime.
