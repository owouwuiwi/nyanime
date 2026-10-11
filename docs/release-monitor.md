# Uscite, aggiornamenti e calendario

Il monitoraggio è attivo inizialmente. Usa i titoli in libreria, tracciati, già
iniziati o seguiti esplicitamente. `Segui` nella scheda permette di includere
anche un titolo fuori dalla libreria; `Smetti di seguire` esclude quel titolo
dai controlli automatici. Restano rispettate le categorie escluse, le fonti
disabilitate, i titoli nascosti e la modalità incognito.

La voce **Uscite**, accanto a Libreria, apre **Le tue uscite**. Anime e manga
condividono l'agenda con filtri Tutti/Anime/Manga. Impostazioni > Libreria >
Agenda e calendario unificati permette di consultarli separatamente.
La schermata legge subito i dati locali; apertura e cambio giorno/mese non
attendono una verifica completa in rete. Il monitor aggiorna in background le
date con una rotazione limitata dei controlli.

La vista **Agenda** mostra le schede raggruppate per giorno. Il selettore
**Agenda / Calendario** permette di passare alla griglia mensile: ogni giorno
è selezionabile, anche se vuoto. Tornando alla lista viene conservato il giorno
scelto come punto di partenza della sequenza cronologica. All’apertura l’agenda
parte da oggi: le uscite precedenti sono sopra e quelle future sotto, senza
un limite di sette giorni. Il comando **Oggi** riporta alla posizione attuale.
Filtri e selettore restano accessibili durante lo scorrimento; le nuove verifiche
non riportano la lista all’inizio. Anche un giorno di apertura vuoto conserva
un punto nella sequenza, senza inventare uscite o date mancanti.

## Due eventi distinti

- **Nuovo contenuto disponibile:** una lista restituita dall'estensione contiene
  un episodio/capitolo nuovo. Viene salvato insieme al suo avviso, nella stessa
  transazione. Le tue novità, gli aggiornamenti e i widget usano i dati esistenti.
- **Trasmissione originale annunciata:** una data pubblica AniList, associata
  tramite ID AniList/MAL verificato. Non equivale alla disponibilità nella fonte.
  Il calendario non trasforma una frequenza stimata in una data di pubblicazione.
  [Dati di trasmissione AniList](https://docs.anilist.co/reference/object/airingschedule).

I manga mostrano le nuove disponibilità reali. Le date future richiedono il
contratto facoltativo `ReleaseScheduleProvider` dell'estensione; in assenza di
date annunciate il calendario lo indica, senza inventare appuntamenti.

## Controlli e recupero

WorkManager verifica la coda ogni 15 minuti, con al massimo 24 titoli per giro,
tre fonti in parallelo e richieste sequenziali per fonte. Le code grandi
proseguono in giri successivi; non vengono tagliate definitivamente. Il controllo
ordinario è ogni sei ore; vicino alla trasmissione è ogni 15 minuti, poi ogni ora
per le prime 48 ore di attesa. Un anime concluso viene controllato meno spesso
soltanto con stato concluso e numero totale confermati e tutti gli episodi presenti.
Le schede e Continua a guardare possono anticipare un controllo dopo dieci minuti.

Successo, tentativo e prossimo controllo sono persistenti e distinti. Gli errori
mantengono il successo precedente e usano attese crescenti. Riavviare l'app non
fa perdere il lavoro completato. Le richieste allo stesso titolo coordinano il
recupero e il salvataggio; le richieste al calendario rispettano la risposta 429
e la relativa attesa. Il ritmo rispetta i
[limiti documentati di AniList](https://docs.anilist.co/guide/rate-limiting).
Scheda e monitor condividono la regola di validità degli orari: soltanto una
risposta riuscita può essere riutilizzata per sei ore. Un errore viene ritentato
senza ereditare la validità precedente, con almeno cinque minuti tra tentativi
ordinari; le date salvate restano visibili. Anche l'ultima trasmissione già passata
fa ricontrollare un calendario che prima conteneva appuntamenti futuri.
Le richieste usano il DNS configurato nell'app e il limite comune a Home e tracking.
Gli avvisi sono riferiti al tipo di contenuto selezionato e gli errori sono registrati
con l'identificatore locale del titolo, senza pubblicare i dati della libreria.
Il tracking salvato prevale sugli indizi delle estensioni. Se un ID AniList
non esiste, il calendario può usare l'ID MyAnimeList collegato solo quando
la risposta conferma la corrispondenza esatta. ID contraddittori o non trovati
restano non risolti, senza scegliere un titolo per nome né segnalarli come
semplici problemi di rete. Consultare gli orari pubblici non richiede login.
La prima acquisizione di un titolo non pubblica tutto il catalogo come novità.
Gli avvisi storici importati dalla prima migrazione vengono esclusi dall'agenda,
senza cancellare capitoli, episodi o progressi. Una data fornita effettivamente
dall'estensione viene conservata separatamente dalla data di rilevamento.
Anche i controlli senza modifiche verificano le date degli avvisi esistenti.
Una disponibilità priva di data resta nella Home/Novità, senza inventare un giorno
nel calendario. La correzione degli avvisi precedenti usa la normale rotazione
limitata delle richieste; le date sostitutive del catalogo non sono prove di pubblicazione.

Le notifiche usano una coda persistente, con deduplicazione e identificatori
Android stabili. Se il permesso o il canale sono disabilitati, l'avviso resta
in attesa. Lo stato e il test delle notifiche sono in Impostazioni > Libreria.
Gli avvisi dei titoli esclusi vengono scartati. Date e impostazioni sono locali;
le preferenze per titolo entrano nel backup mediante URL e ID opaco della fonte,
non mediante gli ID numerici di un altro database.

## Promemoria e limiti verificabili

Un solo allarme locale punta al prossimo avviso. **Orari dei promemoria**, in
Impostazioni > Libreria, permette di combinare 24 ore prima, le 09:00/15:00/20:00
del giorno precedente e del giorno stesso, un'ora o 10/5/2 minuti prima e
l'orario della trasmissione. Gli orari fissi seguono il fuso del telefono; nel
giorno dell'uscita vengono usati solo se precedono la trasmissione. Le due scelte
precedenti, 24 ore prima e all'uscita, conservano i loro valori dopo l'aggiornamento.
Funziona con le date AniList anche senza AnimeSchedule, con il monitoraggio e
i promemoria attivi. Le nuove opzioni sono disattivate inizialmente.
Le scelte coincidenti producono un solo avviso. I diversi momenti hanno ricevute
persistenti e condivise tra edizioni con lo stesso ID di catalogo verificato.
Visto in una di queste edizioni, esclusioni per titolo e incognito impediscono
l'avviso. Le ricevute sono dati di consegna locali, non cronologia da importare
su un altro telefono. Nessuna richiesta di rete è necessaria alla scadenza.

L'autorizzazione Android per gli allarmi puntuali è facoltativa; senza di essa il
promemoria può ritardare. L'avviso delle 24 ore recupera fino a sei ore; gli orari
fissi e l'uscita fino a due ore, gli avvisi vicini all'uscita hanno finestre più
brevi e vengono sempre saltati dopo l'uscita. Oltre queste finestre non vengono
inviati, per evitare raffiche dopo periodi offline. Un avviso saltato non viene
registrato come consegnato; una data successivamente rinviata può essere riprogrammata.
I permessi e il canale bloccati non consumano la ricevuta. Alla riapertura o al
prossimo controllo si riprova entro la finestra; nessuna sequenza di allarmi viene
creata soltanto per chiedere il permesso mancante. Riavvio, aggiornamento dell'app,
cambio orario/fuso e concessione del permesso per gli allarmi riprogrammano gli avvisi.
**Stato del monitoraggio** permette di provare separatamente il canale dei nuovi
contenuti e quello dei promemoria.

La vista compatta dà la prima riga al nome del titolo e la seconda a numero,
giorno e ora: il nome non è preceduto da una frase che potrebbe nasconderlo.
Espandendo la notifica compare l'annuncio completo, per esempio **Domani esce un
nuovo episodio di «Titolo»**. Il corpo riporta il nome intero su più righe quando
serve, poi data completa, tipo di trasmissione, piattaforme e disponibilità nella fonte.
Gli avvisi dei contenuti già trovati usano **Oggi esce…** soltanto con una data di
pubblicazione effettivamente fornita per tutti gli elementi. Senza questa prova,
oppure con un recupero storico, mostrano **È disponibile…**. L’ora di rilevamento
è distinta dall’ora di pubblicazione; una data senza ora non diventa mezzanotte.
Titoli lunghi conservati nella vista espansa, plurali per più episodi/capitoli e
preferenze di riservatezza applicate anche ai nuovi testi.
Date non più annunciate vengono rimosse soltanto dopo una risposta completa e
valida, con numero e ora aggiornati insieme. Errori e pagine incomplete conservano
l'ultima verifica riuscita. Gli orari seguono il fuso del dispositivo.

La disponibilità non può precedere la pubblicazione dell'estensione. I controlli
periodici dipendono dalla rete e dalla pianificazione Android; un arresto forzato
dell'app impedisce l'esecuzione fino alla riapertura. Non serve un token aggiuntivo.
AnimeSchedule è un'integrazione facoltativa: se collegato, i promemoria usano
la trasmissione preferita; il calendario abituale resta il fallback.

## Verifiche

- `ReleasePolicyTest`: finestre temporali, recupero da errori, decodifica delle
  date, formato dei backup e coordinamento/cancellazione degli aggiornamenti.
- `scripts/tests/test_release_monitor_schema.py`: migrazioni effettive su SQLite,
  rollback della coda, invii duplicati, esclusioni, visto/letto, cancellazione e
  ricevute separate per anticipo e trasmissione.
- `ReleaseReminderPlanTest`: anticipo, deduplicazione per ID, cambio provider,
  rinvii, ritardi, assenza di date e cambio dell'ora legale.
- Le prove reali di layout e notifiche sono distinte dalle prove delle migrazioni;
  l'esecuzione pianificata a schermo spento va verificata per ogni dispositivo.

## Unione delle uscite tra fonti

L'agenda anime riusa `mergeHomeCards`: ID di catalogo compatibili, oppure titolo
normalizzato e anno entrambi noti, senza conflitti tra identificativi. I dati di
tracking e calendario arricchiscono il contratto generico delle estensioni;
evidenze contraddittorie impediscono l'unione. Un episodio con numero riconosciuto
compare una sola volta e offre le fonti concrete disponibili. Numeri sconosciuti,
stagioni con ID diversi non vengono accorpati. Le edizioni nella stessa fonte
possono condividere l'annuncio solo con ID comuni verificati, mai per il solo
titolo/anno; la scelta resta esplicita e non cambia il comportamento della Home.
La disponibilità prevale sull'annuncio e un episodio già visto in una variante
non resta un'uscita da vedere nell'altra. Le date di pubblicazione dei capitoli
restano separate dal rilevamento nell'app anche durante la riparazione dei dati.
