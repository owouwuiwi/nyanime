# Lettore romanzi

La consegna0.80.0.0 di Nyanime ordinario aggiunge EPUB/TXT e romanzi online al lettore manga della0.55.0.0. Legado resta rinviato: [roadmap separata](legado-roadmap.md).

## Modello e documenti

`core/novel-reader` possiede identità, importazione, pubblicazioni, archivio SQLite, posizioni strutturali, annotazioni e indice di ricerca. Non utilizza le tabelle delle pagine manga. L’identità di un documento locale dipende dall’hash del file originale; quella di un titolo online richiede fonte e riferimento esatto, senza deduzioni dal nome.

L’importazione EPUB/TXT copia il documento nello spazio privato, verifica dimensioni, hash e archivio e pubblica la nuova edizione solo dopo la verifica. L’originale esterno resta intatto. Una nuova codifica TXT produce un’edizione distinta dello stesso libro. Il ricollegamento di un file mancante esige l’hash originale, conservando note e identità.

Il TXT viene convertito a capitoli XHTML limitati attraverso lettura a blocchi. Sono disponibili riconoscimento iniziale della codifica e selezione manuale; errori di decodifica non sostituiscono silenziosamente il testo. EPUB2/3 sono aperti con gli AAR ufficiali Readium3.4.0. La navigazione grafica dei vecchi EPUB manga rimane separata.

## Navigazione e ricerca

La Libreria Tutti e la sezione Romanzi condividono una lettura locale a pagine di40, ricerca e rimozione dalla raccolta. Il filtro Da continuare richiede un punto strutturale salvato e viene applicato prima del conteggio e della paginazione: non esclude libri oltre la prima pagina e non inventa numeri di capitolo. Solo scaricati richiede disponibilità offline verificata dell'edizione corrente, indicizzata separatamente dai metadati. Una copertina o un indice online non costituiscono un libro scaricato; un ripristino portabile non può attribuire disponibilità locale. La consultazione dei metadati non inizializza il navigator Readium.

Il lettore mantiene la pubblicazione nel ViewModel durante la rotazione. Locator e contenuto hanno versioni esplicite; numero di pagina e percentuale visualizzata non diventano identità persistenti. Un locator non valido non sostituisce il precedente punto salvato.

Il servizio delle posizioni riutilizza `EpubPositionsService` con lunghezze ZIP non compresse verificate e un limite di16384 posizioni, senza leggere tutti i capitoli all’apertura. La forte compressione di un libro non riduce la barra di avanzamento a un solo punto per capitolo. I valori di dimensione dichiarati dai documenti cifrati non determinano allocazioni incontrollate.

Indice, aspetto, ricerca e note sono pannelli Nyanime. L’interfaccia usa ModernMotion e le preferenze di animazione ridotta. I quattro comandi si dispongono su due righe quando larghezza e dimensione del carattere lo richiedono, evitando parole spezzate. Il pannello di ricerca opta esplicitamente per gli spazi sopra la tastiera; i pannelli esistenti mantengono il comportamento precedente.

La ricerca FTS4 viene costruita soltanto all’uso, un capitolo limitato per volta, con risultati progressivi e annullamento. Quote:32MiB di testo per libro,64MiB complessivi,64 libri conservati e192MiB massimi per il database derivato. Le quote includono le sovrapposizioni dei passaggi; un limite raggiunto viene mostrato esplicitamente. La rimozione dell’indice non elimina documenti, note o posizioni.

La Home mostra una categoria Romanzi quando esistono libri preferiti o fonti Romanzi attive, incluse quelle dello Studio. La disponibilità usa soltanto dichiarazioni locali, senza istanziare fonti o avviare Telegram; lingue, contenuti per adulti e identità duplicate mantengono le politiche del registro condiviso. La ripresa legge fino a10 libri in corso; le copertine della raccolta provengono dall'archivio locale, senza ricerca remota vuota. Il cambio categoria ferma le letture della sezione lasciata mantenendo le schede durante la dissolvenza.

La cronologia contiene una scheda Romanzi con ricerca e paginazione per data d'uso, inclusi libri completati e rimossi dai preferiti. Il collegamento dalla ripresa in Home seleziona direttamente questa scheda, senza riavviare un anime alla riselezione. I filtri non trasformano percentuali EPUB in capitoli manga. L'incognito continua a impedire la registrazione di nuove posizioni.

## Contenuto locale e risorse

Il client HTTP del navigator locale è bloccato. Il container espone soltanto risorse relative realmente presenti nell’archivio e rifiuta URL assoluti prima di delegare la lettura. Script del documento, eventi, frame, form, riferimenti attivi e suggerimenti di rete vengono rimossi. SVG conserva la struttura vettoriale dopo la sanificazione. Le risorse remote eventualmente dichiarate da un libro non vengono scaricate automaticamente.

Readium usa hostname virtuali con underscore. Chromium non li accetta come sorgente host della CSP: la policy permette lo schema HTTPS per gli asset fidati del toolkit, mentre container e client continuano a bloccare la rete del documento. I MIME nel manifest sono privi di charset perché Android li passa direttamente a WebResourceResponse; la codifica resta nei byte e nei metadati della risorsa.

Le sole funzioni annotate JavascriptInterface del navigator fidato sono conservate dall’ottimizzatore R8. Un test del modulo non sostituisce il controllo della presenza del bridge e dei tocchi nell’APK minificato.

EPUB strutturalmente fuori budget e DRM non supportati vengono segnalati. L’offuscamento standard dei font è distinto dalla cifratura dei contenuti. PDF, TTS, OCR e traduzione sono esclusi da questa consegna. Nessun sito, catalogo o regola di una fonte viene preinstallato.

## Runtime online

Il core dispone di un container Readium separato per i romanzi online. I riferimenti dei capitoli sono opachi e producono indirizzi interni stabili anche dopo rinomina, riordino o aggiunta di capitoli. HTML e testo vengono resi inerti prima del navigator; soltanto immagini dichiarate esplicitamente dalla fonte ottengono un riferimento interno. I capitoli non vengono letti per costruire il manifest o calcolare le posizioni: le percentuali iniziali seguono la struttura dei capitoli, senza dichiarare conteggi esatti di pagine.

La materializzazione è asincrona, con due richieste simultanee per pubblicazione, timeout, condivisione delle richieste in corso e annullamento alla chiusura. I riferimenti web restano esclusi dal container. La sola origine virtuale del navigator Readium viene ricondotta alla medesima lista di risorse autorizzate, necessaria per le illustrazioni scoperte caricando un capitolo. Il container non possiede un client HTTP e non riceve credenziali.

Il caricamento possiede un proprio job e una scadenza: il suo timeout o annullamento non è l’annullamento del chiamante Readium. Al confine `Resource`, questi errori diventano `ReadError`, evitando eccezioni non controllate nei callback JNI del WebView. La cancellazione del chiamante rimane invece propagata. Non vengono allungati i timeout né intercettati globalmente gli errori del processo.

La cache online è persistente e separata dagli originali e dai libri scaricati: limite predefinito256MiB e8192 voci, hash verificati, pulizia per uso recente e protezione dei file ancora aperti. Le richieste uguali sono coordinate anche fra pubblicazioni; l'interruzione di una scrittura non lascia oggetti senza indice permanenti. Le immagini raster vengono controllate sui byte effettivi, con limiti di dimensione e pixel; una risposta HTML dichiarata come PNG non viene accettata.

Il contratto offre una vista distinta della sola cache: una risorsa assente fallisce senza chiamare la fonte. Il servizio della libreria ammette un opener online facoltativo, conservando i dati aggiornati e la posizione strutturale senza chiedere un file EPUB per un romanzo remoto. I documenti locali mantengono il percorso precedente.

L'adattatore delle fonti conserva un indice privato dei capitoli, scritto per sostituzione atomica e distinto dai record portabili. Aggiornare i dettagli non può annullare una scelta sui preferiti fatta nel frattempo; riscrivere soltanto il percorso locale dell'indice non genera un nuovo evento Cloud. Il contenuto verifica identità del capitolo, conflitti fra immagini dichiarate e revisione della fonte prima e dopo la chiamata. Il caricamento della fonte viene rinviato al primo oggetto assente dalla cache.

Le immagini usano solo richieste HTTP/HTTPS dichiarate dalla fonte, limitate in byte e tempo. Non ricevono cookie, intercettori, autenticazione o log degli header dell'host. I reindirizzamenti sono limitati: a cambio origine vengono rimossi gli header della sessione e HTTPS non può diventare HTTP. Questa politica non sostituisce la gestione della sessione nella fonte: eventuali credenziali necessarie devono essere restituite esplicitamente da resourceRequest e restano transitorie.

Le copertine dei risultati hanno una cache derivata distinta, di32MiB e128 voci, con hash e recupero delle scritture interrotte. Al salvataggio dei dettagli una copertina già disponibile viene collegata alla copia privata del libro, indipendentemente dalla cache in memoria di Coil. Se manca, il caricamento facoltativo dell'immagine lavora separatamente dai metadati, con due richieste simultanee e controllo della revisione della fonte. I percorsi locali non generano eventi Cloud.

Il runtime è provato con dati e file fittizi sul vero navigator Android, incluso il caricamento di un'immagine non presente nel manifest iniziale. Una fonte fittizia esterna è stata importata dal vero Store nell'APK minificato: verificati Atlante, paginazione, scheda, preferiti, apertura diretta del capitolo2, testo e illustrazione, ricerca, segnalibro e ripresa dopo chiusura del processo. La prova ha individuato il salvataggio mancante della copertina nella Libreria: il successivo candidato13 ha verificato anche la copia privata, rileggendo hash e dimensioni dell'immagine.

La ricerca online nel lettore usa uno snapshot della sola cache, verificato tramite hash e protetto dall'espulsione durante l'indicizzazione. Lo snapshot ha una versione FTS distinta dall'edizione del libro: aggiungere, modificare o rimuovere capitoli dalla cache invalida i risultati derivati, senza modificare progressi e note. Nessun capitolo mancante viene scaricato per la ricerca. Preparazione e indicizzazione hanno avanzamenti separati; annullamento e chiusura rilasciano i file trattenuti.

I locator e le annotazioni online possono conservare il riferimento strutturale e l'hash del capitolo effettivamente letto. Le annotazioni senza verifica restano consultabili, ma una vecchia citazione non viene applicata a un capitolo modificato. I dati locali e i record precedenti mantengono campi opzionali compatibili.

Atlante interroga le fonti Romanzi abilitate tramite il contratto facoltativo, nel medesimo limite globale di cinque richieste. Cursori opachi e recuperi hanno stati indipendenti; gli aggiornamenti della fonte invalidano le risposte tardive. I romanzi non consumano il budget dei cataloghi esterni. La scheda online conserva dettagli disponibili, progressi e preferiti; l'apertura di un capitolo usa il suo riferimento dichiarato, senza trasformarlo in un ID manga. Le copertine passano da richieste esplicite della fonte, senza credenziali nei modelli Coil o nei metadati portabili.

## Download dei capitoli online

I capitoli scelti dall'utente hanno copie durevoli separate dalla cache di lettura. Il registro
SQLite locale conserva una selezione esplicita e una revisione del lavoro: una risposta di un
tentativo annullato non può essere applicata al successivo. Un capitolo diventa completo soltanto
dopo il controllo del corpo e di tutte le illustrazioni dichiarate. Capitoli e immagini condivise
già verificati vengono riutilizzati; la pausa conserva i file parziali e permette di riprendere.

Le copie sono limitate a2GiB e20000 risorse per libro, con32MiB di margine libero. La copia usa
buffer limitati, hash, fsync e pubblicazione atomica; il conteggio fisico considera anche i file
sostituiti ancora trattenuti da una lettura aperta. La rimozione non cancella dati di lettura e
non può eliminare file usati da un navigator attivo. La disponibilità resta privata e non è
ricreata dal solo ripristino di un backup dei metadati.

L'integrazione host aggiunge selezione dei capitoli, stato, pausa/ripresa e rimozione nella scheda,
una pagina Romanzi nei Download e contatori nella Libreria. WorkManager conserva il lavoro tra
chiusure dell'interfaccia; la notifica di pausa identifica il tentativo esatto. Il lettore dà
precedenza ai file durevoli e in Solo scaricati esclude il recupero dalla rete.

Nell'APK privato minificato è verificato il percorso Store → importazione della fonte fittizia →
Atlante Romanzi → scheda → selezione e download di tre capitoli. Il registro conferma tre corpi
verificati e una sola copia dell'immagine condivisa. Dopo rimozione della fonte, della relativa
cache temporanea e del collegamento al server di prova, il vero navigator apre testo e immagini
dalle copie durevoli. I dati di prova sono stati rimossi e il sync ripristinato. Pausa/ripresa,
retry, selezione parziale e pagina Download richiedono ancora le prove nell'interfaccia.

## Preparazione degli indici dello Studio

Le scansioni dello stesso titolo condividono un lavoro, mentre titoli diversi non trattengono un
mutex di rete globale. Al massimo due scansioni lavorano insieme. La scheda mostra i metadati
prima dell'indice completo, con pagine e capitoli effettivamente trovati e comandi Pausa/Riprendi.
L'anteprima usa lo stesso coordinatore con uno spazio separato dalla fonte attiva e dai suoi consensi.

Ogni pagina aggiorna righe e cursore nella stessa transazione SQLite. Una generazione completa
rimane leggibile durante la preparazione della successiva; quest'ultima viene pubblicata solo a
paginazione conclusa e dopo la verifica di revisione, account e autorizzazioni. I checkpoint sono
cache privata esclusa da backup e Cloud, con limiti di10.000 capitoli e16MiB per indice.
La ripresa dopo chiusura del processo è esplicita: questo coordinatore non promette un lavoro
Android durevole in background. Le fonti ordinarie conservano il budget condiviso di45 secondi
per metadati e capitoli; la capacità di preparazione è facoltativa e interna all'host.

Undici test del coordinatore verificano condivisione, cancellazione, ripresa, risposte obsolete,
separazione dell'anteprima e dati non validi. Sul telefono,18 controlli del journal SQLite reale
verificano10.000 righe, rollback di pagina/cursore, generazione precedente e ripresa fra processi.
Sono prove distinte dall'interfaccia completa di preparazione, ancora da collaudare.

L’importazione [Legado è rinviata a una consegna futura](legado-roadmap.md).
Le fondazioni sperimentali non sono collegate all’app: i file Legado non sono importabili
e questa integrazione non blocca la consegna EPUB/TXT, NovelSource e Studio Romanzo.

## Dipendenze e attribuzioni

Readium3.4.0 è fissato con hash AAR/POM in `core/novel-reader/provenance.json`; `verifyReadium` confronta gli AAR utilizzati. La variazione rispetto al piano3.3.0 risolve la mutazione degli header immutabili introdotti nella versione WebKit già utilizzata dall’app. Nessun fork o patch binaria del toolkit.

Il checkout usa già compileSDK37, target36 e min26. Il grafo risolto verificato mantiene Media3 alla1.9.0; i componenti audio facoltativi del toolkit sono esclusi. Il navigator incorpora22 classi PhotoView2.3.0 byte-identiche alla precedente dipendenza diretta, rimossa per evitare duplicati conservandone la licenza.

Le licenze dei font rimangono distinte da quella BSD del toolkit. L’OpenDyslexic effettivamente incluso è una versione storica con Bitstream Vera e attribuzioneCC-BY3.0, non viene rietichettato con la licenza delle versioni upstream più recenti. Le notice sono negli asset e nel catalogo delle licenze dell’app.

## Verifiche e limiti

La suite comune comprende i test completi app/domain e i moduli Telegram, romanzi, Studio e ricerca, oltre a compilazione Preview e controllo delle classi duplicate. Le ricevute di consegna distinguono sempre revisione, APK e prove sul dispositivo: una prova di un candidato precedente non diventa automaticamente prova dei byte finali.

Nei candidati minificati sul telefono USB sono stati verificati EPUB illustrati, TXT Unicode di1.8MB e Windows-1252, codifica, indice, ricerca con accenti, selezione, note, segnalibri, rotazione, chiusura e ripresa. Ripristino e ricollegamento sono stati provati con un originale errato rifiutato e il file corretto accettato conservando la nota.

Una fonte esterna fittizia, importata dal vero Store, ha verificato Atlante, paginazione, scheda, copertina privata, preferiti e lettura Readium con illustrazioni. I download sono stati provati con selezione1/3, pausa e ripresa mantenendo i file parziali, errore HTTP503 e ripetizione, fino a copie READY1/1 e3/3 con hash verificati. La coda Download mostra entrambe le selezioni. La lettura offline è stata provata separatamente dopo la rimozione della fonte e della cache temporanea. Fixture, probe e file sintetici sono stati rimossi; il sync originale è stato ripristinato.

Il ciclo di vita delle anteprime dispone anche di48 controlli Android isolati: metadati, file e FTS separati e completamento delle scritture prima della pulizia. Il journal degli indici è verificato su SQLite Android con10000 riferimenti, rollback, generazione precedente e ripresa tra processi. Queste prove non attestano ogni sito o script personale.

Sette controlli aggiuntivi verificano timeout interno, annullamento della fonte, chiusura durante una lettura, cancellazione del chiamante, ripetizione e i veri callback WebView per un’immagine scaduta o una chiusura con richiesta pendente. La versione precedente fallisce la prova della risorsa e termina il processo di test nel WebView; con la correzione tutti i casi e i48 controlli precedenti passano, senza modificare il package principale o i dati dell’utente.

Sette controlli dell’adattatore Cloud fra archivi SQLite fittizi verificano roundtrip senza eco, tombstone, journal, incognito, preferenze, modifiche offline concorrenti e assenza di percorsi locali. Non equivalgono a una sincronizzazione reale fra due telefoni. I file originali e la cache restano esclusi dal trasferimento dei metadati.

La pagina sulla roadmap della0.80 appare una sola volta negli aggiornamenti, anche da revisioni legacy; le installazioni nuove vengono escluse prima dell’onboarding o del ripristino. La conferma è locale all’installazione ed esclusa da backup/Cloud. Dalla1.0 il messaggio non viene più proposto. Testi IT/EN e variante compatta mantengono lo stesso significato senza introdurre scorrimento.

PDF, TTS, OCR, traduzione e importazione Legado non fanno parte di questa consegna. Nessun catalogo o regola di sito viene distribuito nell’app.
