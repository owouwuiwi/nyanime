# Home dichiarative delle estensioni — API v1

L'app non contiene adattatori, package, nomi di fonti o filtri specifici di un sito. Una fonte opzionale pubblica le proprie capacità nel suo APK, nell'asset `assets/aniyomi/home-v1.json` (nel progetto Android: `src/main/assets/aniyomi/home-v1.json`). Le estensioni senza questo asset continuano a funzionare in Esplora senza aggiungere una Home.

## Contratto

```json
{
  "version": 1,
  "homes": [{
    "id": "films",
    "title": "Film",
    "source": { "name": "Ciao", "lang": "it" },
    "defaults": { "Ordina": "Più visti" },
    "sections": [
      { "id": "popular", "title": "Più visti" },
      { "id": "recent", "title": "Novità", "filters": { "Ordina": "Più recenti" } }
    ],
    "search": { "id": "search", "title": "Cerca film", "filters": {} },
    "categories": { "filter": "Genere", "exclude": ["Tutti"] }
  }]
}
```

Le chiavi e i valori di `defaults`/`filters` corrispondono alle etichette pubbliche di `AnimeFilter.Select<String>`. L'app usa il contratto `getFilterList`/`getSearchAnime` già disponibile, senza riflessione su classi private. Ogni richiesta crea filtri nuovi. Una sezione che richiede filtri non supportati viene esclusa, non trasformata in una ricerca generica.

`source.name` e `source.lang` devono individuare una sola fonte appartenente all'estensione che contiene il file. Non autorizzano a impersonare una fonte di un altro package. Restano validi inizializzazione, compatibilità API, installazione, lingua abilitata, fonti nascoste e controlli NSFW.

## Home condivise

### Presentazione e Home principale

La dichiarazione può aggiungere `"primary": true`: quando la fonte è inizializzata e disponibile,
la sua Home diventa la destinazione iniziale al posto del catalogo pubblico. Non serve un ID
speciale e l'app non controlla il nome del sito. Le altre Home selezionate dall'utente restano
selezionate. Tra più gruppi principali si sceglie deterministicamente l'ID alfabeticamente primo.
Rimuovendo o disabilitando l'ultima fonte principale ricompare il catalogo pubblico.
Durante il caricamento delle estensioni si mostra lo stato di caricamento, senza avviare richieste
al catalogo che potrebbe essere sostituito.

Ogni sezione può dichiarare `"layout": "featured"` per mostrare un carosello con `background_url`,
`thumbnail_url`, titolo e descrizione dei normali `SAnime` restituiti dall'estensione. Il valore
predefinito e gli stili sconosciuti usano le normali copertine. L'ordine delle sezioni e dei titoli
rimane quello dei produttori; banner e suggerimenti non vengono sostituiti con quelli del catalogo.
Il clic apre direttamente la serie della fonte. «Continua a guardare» è sempre la prima
sezione sotto la toolbar, prima della vetrina e delle altre righe dell'estensione.
Resta visibile anche durante caricamenti/errori remoti, in Solo download e senza
elementi da riprendere (con un messaggio esplicativo). Il titolo offre l'accesso alla
cronologia filtrata per tutte le fonti disponibili della Home, con ricerca e azioni
sui singoli titoli; «Tutta la cronologia» apre la schermata generale. Non si offre
la cancellazione globale dentro la vista filtrata. I dati sono locali; le estensioni non
controllano né possono nascondere questa sezione. Gli aggiornamenti della libreria
seguono i contenuti dell'estensione.

Le proprietà sono facoltative e compatibili con le dichiarazioni v1 precedenti. Il selettore in
toolbar occupa la larghezza del contenuto, fino allo spazio disponibile; scorre solo quando serve.

### Sezioni con scelta della data

Una sezione può dichiarare `"dateFilter": "Data"`, dove l'etichetta identifica un solo
`AnimeFilter.Text` pubblico della fonte. L'host mostra giorno precedente/successivo,
selettore della data e ritorno a «Oggi». La data esplicita è una data civile ISO
`AAAA-MM-GG`; il valore vuoto lascia all'estensione il giorno predefinito del sito.
Fuso orario, URL e formato delle richieste remote appartengono alla fonte.

Data selezionata e richiesta restano associate anche in «Mostra tutto», nella cache
persistente e nella paginazione. Cambiando data si svuotano subito i risultati precedenti;
errori e giorni senza uscite non riutilizzano gli eventi di un altro giorno. Nelle Home
condivise le richieste datate coinvolgono solo i produttori che dichiarano il filtro.
Se il filtro opzionale non è disponibile, la sezione conserva il comportamento precedente.
I vecchi host ignorano la nuova proprietà e continuano a caricare il giorno predefinito.

### Raggruppamento delle varianti

Le sezioni possono condividere una riga con schede selezionabili, dichiarando `group`:

```json
[
  { "id": "day", "title": "Classifica giornaliera", "filters": { "Periodo": "Giorno" },
    "group": { "id": "ranking", "title": "Classifica", "tab": "Giorno" } },
  { "id": "week", "title": "Classifica settimanale", "filters": { "Periodo": "Settimana" },
    "group": { "id": "ranking", "title": "Classifica", "tab": "Settimana" } }
]
```

La riga occupa la posizione della prima sezione del gruppo. Le schede seguono l'ordine dichiarato;
la prima è la scelta iniziale. Titolo del gruppo e nomi delle schede arrivano dall'estensione:
nessun elenco di periodi, lingue o categorie è codificato nell'app. Dentro una dichiarazione,
lo stesso ID gruppo deve avere lo stesso titolo ed etichette delle schede distinte e non vuote.
Valgono i limiti ordinari di ID ed etichette. Le Home condivise possono aggiungere varianti usando
gli stessi ID semantici di gruppo e sezione; la presentazione del primo produttore ha precedenza.

Si carica soltanto la variante selezionata di ogni riga composta. Filtri, cache, retry e
"Mostra tutto" usano ancora l'ID della sezione concreta. Il ritorno alla Home e la ricreazione
della schermata conservano selezione e scorrimento delle varianti già visitate. Se una variante
non è più supportata, viene scelta la prima disponibile; con una sola variante non si mostra
un selettore e si usa il titolo completo della sezione. Il refresh manuale aggiorna le sezioni
già visitate, incluse le altre varianti consultate. Senza `group` la sezione resta indipendente. I precedenti host v1 ignorano
questa proprietà e mostrano tutte le sezioni separate.

L'identificatore semantico `homes[].id` è condiviso tra estensioni: per contribuire alla stessa Home devono dichiarare lo stesso ID, per esempio `cartoons` o `films`. Non si raggruppa tramite il testo tradotto del titolo. Non esiste una whitelist di ID nell'app.

- Una sola voce di navigazione per ID, con contributi di tutte le estensioni disponibili.
- Le sezioni con lo stesso ID si uniscono; quelle esclusive si aggiungono. Concordare la semantica degli ID tra produttori: `popular` non deve significare "episodi appena usciti".
- Anche ricerca e categorie aggregano solo le fonti che le supportano.
- Risultati alternati tra fonti, preservando gli ordinamenti interni. Senza metadati aggiuntivi i doppioni vengono rimossi per identità concreta `source + url`, mai soltanto per titolo. Le schede con un ID di evento distinto restano separate anche se riferite alla stessa serie.
- Ogni scheda conserva la fonte per episodi, libreria e riproduzione. Nessuna selezione obbligatoria della fonte per aprire la Home.
- Una fonte lenta o in errore non nasconde i risultati delle altre. Gli errori riportano il produttore interessato; la paginazione tiene conto delle fonti già esaurite.
- Rimuovendo una fonte spariscono i suoi contributi; la Home sparisce solo quando non rimane nessun produttore disponibile. I collegamenti aperti tornano alla Home quando il gruppo non esiste più.

Il nome visualizzato in caso di titoli discordanti viene scelto deterministicamente ordinando le identità dei produttori. Gli ID del produttore includono package, ID Home e ID fonte, per evitare collisioni nelle cache. La revisione include versione dell'estensione e contenuto della dichiarazione.

## Limiti e privacy

### Dati delle singole schede

L'API pubblica `SAnime.memo` può contenere la chiave facoltativa `aniyomi.home.v1`:

```json
{
  "id": "episode-9",
  "badges": ["DUB", "Ep 9"],
  "details": ["Orario indicato dalla fonte: 18:30"],
  "sectionTitle": "Titolo attuale della sezione"
}
```

`id` distingue una scheda/evento all'interno della serie, senza cambiare `SAnime.url`.
La stessa serie può comparire per episodi diversi mantenendo un solo ID in libreria.
Il carosello `featured` mostra anche tutti i badge e dettagli di presentazione, con
testo a capo e altezza adattabile, oltre a immagine, titolo e sinossi.
Badge ed etichette sono testo semplice fornito dall'estensione: l'app non interpreta
episodi, orari, voti o lingue di un sito. Il titolo dinamico viene usato quando i produttori
concordano; le righe con varianti mantengono il proprio titolo di gruppo.

L'ID è limitato a 512 caratteri; badge e dettagli a 8 elementi, rispettivamente di
80 e 300 caratteri; il titolo a 100. Controlli, campi malformati e oggetti oltre 8 KiB
non diventano contenuto eseguibile o dati della libreria. Metadati assenti o non validi
mantengono il comportamento precedente.

Il gateway rimuove questa chiave prima della conversione nella libreria e la applica
solo alle copie di presentazione. La cache Home conserva i dati per posizione della
scheda, così due episodi della stessa serie non si sovrascrivono. Identità, titoli
personalizzati, preferiti, progressi e gli altri campi memo restano quelli locali.
Le vecchie pagine della cache sono ancora leggibili. Valgono le medesime esclusioni
di cache per incognito e solo download.

La proprietà memo è pubblica dall'API 17. Un'estensione compilata con una precedente
libreria può applicarla tramite un piccolo adattatore opzionale al setter pubblico
`SAnime.setMemo(JsonObject)`, mantenendo le API precedenti quando il metodo non esiste.
Il rilevamento riguarda solo questo metodo pubblico, mai classi private della fonte.

Solo lettura locale dell'APK scelto dal loader esistente, anche per installazioni private; nessuna richiesta remota durante il rilevamento delle capacità. Il documento ha un limite di 64 KiB decompressi, 8 Home, 16 sezioni per Home e 100 caratteri per etichetta. ID duplicati nella stessa estensione sono ambigui e ignorati. Versioni sconosciute o documenti malformati non bloccano il catalogo generale. Gli attributi aggiuntivi non eseguono codice e non avviano richieste.

Concorrenza condivisa: massimo 3 richieste alle fonti. Cache in memoria, 16 pagine per produttore, fino a 8 servizi conservati, scadenza 30 minuti. I feed pubblici persistono anche nel database Discovery separato: massimo 128 pagine consultate, conservazione massima 24 ore. Un riavvio entro la scadenza non riscarica i feed già salvati; dopo 30 minuti vengono mostrati subito e aggiornati in background. Il refresh manuale resta disponibile. La revisione dell'estensione invalida le vecchie pagine.

Al rientro nella Home e al ritorno dell'app in primo piano si rivalutano le sezioni già
visitate: le pagine ancora fresche non richiedono rete. Il gesto di trascinamento verso
il basso e il pulsante della toolbar forzano l'aggiornamento. Le schede della stessa
richiesta rimangono visibili durante il caricamento; date diverse e cambi delle fonti
non ereditano quei dati. Solo download continua a impedire richieste remote.

Il database salva ID locali, paginazione e dati di presentazione pubblici (banner e descrizioni,
limitati rispettivamente a 2048 e 8000 caratteri per scheda). Flag, progressi, titolo e copertina della
libreria vengono riletti, non ripristinati da vecchie copie. Le pagine precedenti restano leggibili.
La migrazione 1 → 2 aggiunge una tabella senza alterare catalogo e collegamenti esistenti. Errori
della cache non impediscono l'accesso alla rete. Le ricerche non vengono persistite; in incognito
nessuna lettura o scrittura della cache, nemmeno in memoria. Solo download impedisce letture di
queste pagine e richieste alle fonti. Continua a guardare e aggiornamenti usano gli interactor
locali filtrando l'insieme delle fonti del gruppo.

## Compatibilità degli aggiornamenti

### Esplorazione e filtri combinabili

Ogni Home può dichiarare `browseFilters`, una lista di massimo 24 nomi dei suoi
filtri pubblici. Il gateway supporta select a scelta singola, gruppi di checkbox
e campi di testo. Opzioni e valori iniziali vengono letti dall'estensione;
`defaults` e i filtri della sezione `search` determinano i valori iniziali effettivi.
Esempio neutro: `"browseFilters": ["Categoria", "Anno", "Ordine"]`.

La ricerca funziona anche con testo vuoto. I filtri vengono combinati con il testo,
si applicano solo dopo la conferma e restano visibili come chip rimovibili.
Un cambio di ricerca o filtri annulla la richiesta precedente e riparte dalla prima
pagina. I cursori di paginazione includono tutti i filtri; ricerche e pagine complete
non vengono persistite nella cache delle anteprime pubbliche.

`categories.filter` può indicare anche un gruppo di checkbox. L'app ricava le
categorie da quel controllo e apre la ricerca con l'opzione già selezionata.
Il controllo delle categorie rimane disponibile anche senza `browseFilters`, per
compatibilità con i manifest precedenti. Nei cataloghi con più produttori vengono
esposti solo controlli identici per tutte le fonti ricercabili. Nessun filtro interno
non dichiarato può essere modificato dalla ricerca.

`moreFilters` su una sezione sostituisce i filtri dell'anteprima, mantenendo i
`defaults`, quando si apre la pagina completa. La Home continua a usare i filtri
ordinari della sezione. L'estensione decide il percorso e la paginazione corretti.

### Logo dinamico della fonte

La presentazione `aniyomi.home.v1` può contenere `logoUrl`, URL HTTP(S) pubblico del
marchio orizzontale corrente. L'estensione lo estrae dalla pagina o dai suoi asset
di stile durante il caricamento della Home. Non deve inviare cookie, credenziali,
URL locali o immagini inventate. Il campo è facoltativo e limitato a 2048 caratteri.
Nessun parsing del sito viene effettuato dall'app.

`logoBackground` può indicare `light` o `dark` quando la trasparenza del marchio
richiede un contrasto preciso. `logoName`, testo semplice fino a 80 caratteri,
può accompagnare un marchio compatto: l'app dispone icona e nome in orizzontale,
senza deformare l'immagine. Entrambi i campi sono facoltativi. Con animazioni
ridotte il logo GIF viene mostrato come immagine statica.

In Impostazioni → Aspetto, **Logo della fonte nella Home** abilita questa resa
sia per ModernUI sia per la UI legacy. Inizialmente resta il marchio NYANIME.
Il logo viene proporzionato senza ritaglio, con supporto PNG, GIF, WebP e SVG,
tramite il client immagini della fonte, conservando intestazioni e cache.
Caricamenti falliti mostrano il marchio dell'app; durante un aggiornamento viene
conservata l'immagine precedente della stessa fonte. Le Home che aggregano più
identità mantengono il marchio dell'app, senza attribuire il catalogo a una sola.
Le URL del logo vengono conservate soltanto nella presentazione e nella cache Home,
mai come copertine della libreria.

I vecchi collegamenti serializzati hanno un piccolo ponte verso la Home neutra. La vecchia selezione booleana non viene convertita con un cast nel nuovo identificatore testuale. Anime4K, package dell'app, firma e identità delle opere locali rimangono invariati.

La fiducia automatica è una preferenza separata, attiva per default in questo fork, comune ad anime e manga. Non modifica i consensi espliciti né i controlli di firma Android o versione API; disattivandola e riavviando si ripristina la conferma manuale.
