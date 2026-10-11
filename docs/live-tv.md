# TV in diretta

La categoria **TV** compare nella Home quando viene abilitata almeno un’estensione
TV compatibile nello Store. L’app non installa un catalogo automaticamente e non
contiene canali, indirizzi o regole di una fonte.

## Canali e guida

**Canali** raccoglie preferiti, canali recenti, programmi in onda e sezioni
dichiarate dalle estensioni. Paese, lingua e categoria sono selezionabili dal
pulsante dei filtri. La selezione iniziale è dichiarata dall’estensione.

Un tocco apre la diretta. Tenendo premuto un canale si possono modificare i
preferiti e il loro ordine, aprire la programmazione, condividere un link oppure
creare una stanza. **Guida** offre una lista di programmi e una griglia oraria:
date, orari e programmi provengono sempre dall’estensione. Gli orari sono mostrati
nel fuso del telefono. Guida assente, caricamento, errore e copia salvata hanno
stati distinti.

Atlante cerca i canali nelle estensioni abilitate. La schermata iniziale legge
solo i cataloghi già salvati; non avvia ricerche vuote o verifiche di tutti gli stream.

## Player e Cast

La diretta usa MPV in un contesto separato dagli episodi. Non crea episodi
fittizi e non applica tracking, AniSkip, completamenti o download.

Pausa e caricamento sono locali. **Torna alla diretta** ricarica il canale:
non promette di conservare la posizione di una trasmissione non riavvolgibile.
Audio e sottotitoli disponibili sono selezionabili dalle opzioni del player.
Velocità e riavvolgimento richiedono capacità esplicite dello stream; il
riavvolgimento richiede anche una finestra confermata dal player.

Il PiP mantiene la riproduzione. Anime4K Smart segue la scelta abituale fuori
dalle stanze e viene disabilitato quando si entra in una stanza.
Il lettore live resta inizializzato fra due canali; riaprire il canale corrente
dal PiP conserva la riproduzione invece di riconnetterlo inutilmente.

Il Cast usa un contenuto live tipizzato, senza progressi di episodi o autoplay.
Google Cast riceve il tipo LIVE; DLNA non promette riavvolgimento. Un ricevitore
companion deve dichiarare la capacità live, altrimenti viene mostrato un errore.
Il telecomando resta verticale. I comandi disponibili dipendono dal ricevitore.

## Le stesse stanze

Non vengono create stanze TV separate: la selezione del canale passa attraverso
le stanze video/manga già esistenti. L’host sceglie il canale; ogni dispositivo
risolve autonomamente il riferimento con la propria estensione. Tutti i membri
devono dichiarare il supporto TV. Un client precedente non riceve comandi live
incompatibili.

Pausa, caricamento e riconnessione di una diretta non interrompono gli altri.
Quando l’estensione fornisce una relazione verificata fra posizione e orario UTC,
il coordinatore usa riferimenti temporali e misure degli orologi dei dispositivi.
Entro tre secondi non corregge; fuori dalla tolleranza usa piccole variazioni di
velocità soltanto se lo stream le permette. Senza riferimenti verificabili, l’app
mostra che il ritardo della diretta non può essere verificato.

Il Cast resta utilizzabile nelle stanze live: il cambio canale dell’host viene
risolto anche sul ricevitore. Pausa e buffering del televisore rimangono locali.
Poiché i ricevitori non espongono un orario verificato della trasmissione,
il coordinatore segnala il ritardo come non verificabile e non inventa correzioni.

## Dati, cache e backup

Il player distingue gli errori temporanei di connessione dagli indirizzi mancanti,
dall’accesso rifiutato e dai formati non supportati. Prova le alternative dichiarate
per lo stesso canale e, una sola volta, chiede all’estensione di risolverlo di nuovo.
Un indirizzo definitivamente fallito non entra in un ciclo di riconnessione. Se
non rimangono alternative, viene mostrato il motivo e si può scegliere un altro
canale. La diagnostica registra soltanto categorie di errore: non pubblica gli
indirizzi video né i dati di autenticazione ricevuti dall’estensione.

Il catalogo è riutilizzato per 24 ore; i palinsesti per un’ora. Le richieste della
guida riguardano canali visibili, preferiti e il giorno aperto. Il trasporto limita
a quattro le richieste dati simultanee per estensione; cambiando schermata o
selezione, le risposte superate non possono sostituire i dati correnti.

Preferiti, ordine, canali recenti e selezioni sono salvati nelle normali
preferenze e inclusi nei backup delle impostazioni. Le cache e gli URL temporanei
non vengono esportati. In incognito non vengono aggiunti canali recenti.
Rimuovere l’estensione conserva i riferimenti personali, senza scegliere una
fonte sostitutiva.

I link contengono soltanto tipo TV, identificatore della fonte, riferimento
opaco e nome di anteprima. Non contengono URL video, header, cookie o credenziali.

## Contratto per le estensioni

Il modulo `tv-api` definisce factory, client HTTP, cache privata, cataloghi,
facce dei filtri, feed, programmi UTC, stream alternativi, tracce e capacità live.
RIN schema 2 usa `medium: "tv"`, API 1, una factory e un intervallo di versioni
host. Il catalogo facoltativo `rinTv` è separato da `rinList`: i client precedenti
possono continuare a leggere il loro catalogo senza incontrare un medium sconosciuto.

Identità, parsing, palinsesti e scelta degli stream appartengono all’estensione.
Il motore non deduce capacità o associazioni dal nome della fonte. I test dell’app
usano soltanto fonti e riferimenti sintetici.

## Verifica

I test automatici coprono cache e annullamento, risposte parziali, riferimenti
opachi, recupero locale, invii di stanza, client precedenti e rilascio del player.
Le prove fisiche e i relativi limiti vengono registrati insieme alla consegna:
la compilazione non prova da sola il comportamento di una TV o di due telefoni
su reti differenti. Android TV non fa parte di questo intervento.
