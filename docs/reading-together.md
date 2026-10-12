# Leggi insieme

Video e manga usano **la stessa stanza**, lo stesso codice NY1, lo stesso invito QR,
gli stessi partecipanti (massimo otto) e le stesse connessioni cifrate Nostr.
Dal lettore, il pulsante con le persone apre la stanza. È disponibile anche da
Altro → Guarda e leggi insieme e dai controlli della stanza video.

## Leggere, raggiungere, tornare

Quando il creatore inizia a leggere un capitolo, ne sceglie il riferimento condiviso:
gli ospiti aggiornati lo aprono nell'app in primo piano, al proprio punto salvato o
alla prima pagina. Visitare una scheda non cambia la selezione. Passare a un altro
capitolo dello stesso manga propone «Raggiungi», senza interrompere la lettura.
In background la notifica della stanza permette di aprire il contenuto scelto.
«Leggi per conto tuo» sospende le aperture automatiche; si può tornare alla scelta
condivisa senza lasciare la stanza. Il creatore può concedere il permesso di scegliere
a un partecipante, mentre gli altri possono inviare una proposta.

Ogni persona mantiene il proprio manga, capitolo e pagina. Non esiste un comando
remoto che costringe il lettore a voltare pagina. La stanza mostra il punto degli
altri e distingue la lettura attiva dall'ultimo punto condiviso.

“Raggiungi questa pagina” apre l'esatto riferimento tramite l'estensione installata
e conserva il proprio punto. “Torna al mio punto” è disponibile soltanto nella
stanza in cui è stato salvato; lasciare o chiudere la stanza elimina il segnalibro.
In caso di riavvio imprevisto, si recupera solo dopo il rientro confermato nella
stessa stanza. La lettura normale mantiene i progressi della libreria senza
mostrare comandi delle stanze.
Si può cambiare manga dalla libreria senza cambiare stanza. I nomi e i numeri
del database non vengono usati per scegliere silenziosamente un'altra edizione.
La fonte deve essere installata e attendibile, i riferimenti devono appartenere
alla sua origine e il numero di pagine deve corrispondere prima dello spostamento.
Download con una diversa suddivisione delle pagine richiedono la stessa edizione.

Se manca l'estensione, «Trova l'estensione» apre la gestione delle estensioni.
«Usa la mia copia» richiede invece la scelta esplicita del manga e del capitolo
nella propria libreria o cronologia; l'associazione rimane locale per quella stanza.
Gli ID verificati ordinano i candidati, ma il titolo non dimostra un'identità.
Tra copie diverse, salti precisi e schizzi richiedono l'impronta SHA-256 corrispondente
dei file originali. Il calcolo avviene fuori dall'interfaccia, con concorrenza e
dimensioni limitate. Senza corrispondenza restano presenza e apertura del capitolo;
non vengono disegnati interventi su una pagina potenzialmente diversa.

Aprire il lettore come ospite sospende il proprio video, senza mettere in pausa chi
continua a guardare. Chi legge è escluso dalla barriera di buffering video.
Se il creatore passa al manga, la selezione video comune viene sospesa. Tornare al
player riutilizza la stanza e i normali controlli di ripresa e sincronizzazione.
Il controller della lettura non contiene riferimenti al player.

## Schizzi e note condivisi

“Disegna” attiva una modalità esplicita; la lettura normale conserva i suoi gesti.
La barra offre cinque colori, tre spessori, annulla e pulizia della pagina.
Due dita permettono di tornare ai gesti di navigazione. Un tratto completato viene
trasmesso come coordinate, mai come immagine. Per disegnare sulla pagina dell'amico,
raggiungila: l'amico resta libero di leggere o cambiare pagina.

L'occhio mostra o nasconde localmente le annotazioni, senza modificarle per gli altri.
Una nota breve si posiziona toccando la pagina. La cronologia della stanza permette
di nascondere e ripristinare per tutti i propri interventi; il creatore può moderare
anche quelli altrui. La pulizia della pagina richiede conferma. Le immagini originali
e i download non vengono modificati.

Gli interventi sono temporanei e limitati a 64 pagine, 256 operazioni per pagina
e 8192 operazioni complessive.
Un giornale cifrato conservato solo su questo dispositivo consente il rientro dopo
una chiusura imprevista finché l'invito è valido. La schermata propone esplicitamente
“Rientra nella stanza”; il video non riparte automaticamente. Lasciare la stanza,
chiuderla o far scadere l'invito elimina il giornale e la chiave locale.

Le coordinate sono normalizzate sulla pagina originale e trasformate per zoom,
lettura verticale, pagine divise, doppie pagine ruotate e impilate. Durante la stanza
il ritaglio dei bordi è sospeso, senza cambiare le preferenze salvate, per evitare
che due telefoni disegnino su rettangoli diversi.

## Protocollo e affidabilità

Il canale applicativo reading v2 è opzionale nel messaggio cifrato della stanza
[Guarda insieme](watch-together.md), senza nuovi segreti, relay o identità.
La capacità readingVersion viene negoziata prima che un ospite invii questi dati:
un host precedente non deve interpretare una presenza manga come stato video.
Entrambi i telefoni devono avere la nuova versione per leggere insieme.

La capacità opzionale `readingSessionVersion=1` aggiunge una selezione di capitolo
con revisione assegnata dal creatore, indipendente dalla presenza e dalle immagini.
Gli involucri `Snapshot`, `Choose`, `Propose` e `Receipt` non modificano il protocollo
reading v2 delle annotazioni. La ricezione della scelta e il caricamento riuscito
sono conferme distinte; l'interfaccia mostra apertura, lettura pronta, estensione
mancante, errore o lettura personale. Una richiesta esplicita scade dopo otto secondi;
un nuovo tentativo usa un nuovo identificativo. Duplicati, revisioni obsolete e
mittenti non autorizzati non cambiano la selezione. I client precedenti conservano
la lettura manuale, segnalata nella stanza.

Un coordinatore di navigazione invalida le risposte tardive dopo un cambio di stanza
o modalità. Il giornale cifrato conserva selezione e scelta personale; al rientro
riserva un nuovo intervallo di sequenze, così il riavvio non fa rifiutare i messaggi
dal telefono rimasto connesso. I comandi in attesa non vengono riprodotti dal backup
della stanza. Nessuna selezione viene trasmessa pubblicamente.

Il creatore autentica e ammette i mittenti usando la stessa lista della stanza.
Schizzi, note e visibilità sono operazioni immutabili: la loro unione converge anche
con duplicati o arrivi fuori ordine. Un riepilogo per pagina segnala le differenze;
lo scambio a blocchi recupera gli interventi dopo la riconnessione senza dipendere
dalla cronologia dei relay. La coda in memoria è limitata e il giornale locale
permette il recupero dopo il riavvio. Il vecchio canale reading v1 resta disponibile
quando il creatore usa una versione precedente, con i suoi limiti originali.

La posizione di lettura e l'indicatore della scena video restano presenza temporanea,
separata dalle annotazioni. I cambi di presenza sono aggregati, gli stati sono
rinnovati e una connessione scaduta non viene mostrata come attiva.
Dimensioni UTF-8, coordinate, autori, numero di tratti e frequenza di ingresso sono
limitati prima di applicare un messaggio. I payload rimangono entro il limite
dell'involucro cifrato esistente.

Nessuna immagine, credenziale, cookie o URL risolto delle pagine viene trasmesso.
Titolo, capitolo, riferimenti di catalogo e pagina viaggiano solo nel canale cifrato
della stanza, non nella presenza sociale pubblica. La modalità incognito e le fonti
impostate come incognito non pubblicano la lettura né inviano schizzi.

## Verifica

ReadingCrdtTest copre ordine, duplicati, note e moderazione. ReadingRoomTest copre
recupero CRDT dopo disconnessione, indipendenza dei lettori, cambio titolo, coda offline,
duplicazione, ordinamento, cancellazioni, autorizzazione, limiti e trasformazioni.
WatchRoomTest verifica il canale sulla medesima stanza, l'isolamento dal player,
la compatibilità con host precedenti e il ritorno al video.
ReadingSessionTest verifica permessi, proposte, richieste concorrenti e scadute,
reconnessione, lettura personale e invalidazione della navigazione. ReadingPageFingerprintTest
verifica corrispondenze, limite di lettura e chiusura degli stream. WatchRoomTest
include cambio modalità con snapshot tardivi e ripresa delle sequenze dopo un riavvio.
ReadingReturnPointTest verifica che il segnalibro non sia disponibile fuori dalla
stanza, si recuperi soltanto nella stessa stanza e venga eliminato alla chiusura
senza ricomparire al riavvio o entrando in un'altra stanza.
Una prova con osservatori immediati verifica che la pausa del video durante il recupero
non cancelli il capitolo scelto: la modalità comune viene applicata prima degli effetti
sulla riproduzione.
ReadingRelayTest è una prova facoltativa su entrambi i relay, con due client reali
e soli dati sintetici, attivata da NYANIME_WATCH_NETWORK_TEST=1.

Le anteprime Compose coprono schermo stretto, testo grande, landscape, attesa ed
errori. I controlli sul PC non sostituiscono una prova dei gesti, dello scorrimento
e dell'apertura dei capitoli su due telefoni fisici. Non vengono usati emulatori.
