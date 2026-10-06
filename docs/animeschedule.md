# AnimeSchedule: collegamento facoltativo

La normale agenda anime e manga funziona senza AnimeSchedule. Il collegamento
aggiunge date annunciate, première, variazioni di programmazione e piattaforme,
usando gli identificatori verificati dei cataloghi. Non cerca titoli per nome
e non richiede modifiche specifiche alle estensioni.

## Collegamento

Apri **Impostazioni → Libreria → AnimeSchedule**, oppure il pulsante di
configurazione nell’intestazione di **Le tue uscite**.

1. Tocca **Apri la configurazione guidata**, poi accedi o registrati sul sito.
2. Dopo l’accesso, la guida apre automaticamente le impostazioni API. Se non hai
   ancora applicazioni, mostra il modulo con il nome Nyanime già compilato.
   Leggi i termini API, confermali e tocca **Crea collegamento**. Non servono
   redirect OAuth né permessi di modifica delle liste personali.
3. Tocca **Collega a Nyanime** per verificare e salvare il token. Se ci sono più
   applicazioni, scegli esplicitamente quella da usare. Il token resta nascosto.

Non hai un account? **Crea account** mostra il modulo di registrazione, mantenendo
termini, controlli e verifica del sito. Se richiesta, completa la conferma email,
poi torna su **Accedi**. L’app non legge né conserva la password di questi moduli.

Nel solo browser guidato, prima dell’accesso, il sito genera il consenso ai cookie
necessari. I cookie di analisi non vengono attivati automaticamente; una scelta
già salvata sul sito viene conservata. Se il servizio del consenso non risponde,
resta disponibile la scelta originale del sito, senza dichiarare un consenso riuscito.

La vista guidata mantiene i moduli originali e nasconde la navigazione non necessaria;
i controlli si adattano alla larghezza e ai caratteri del dispositivo. **Sito completo**
ripristina la pagina senza ricaricare i campi. Il pulsante Browser permette di continuare nel browser di sistema
se il login o una verifica lo richiedono. Il sito può cambiare la propria
interfaccia: una pagina non riconosciuta resta visibile interamente. Non vengono
inviati comandi automatici di registrazione, accettazione dei termini o creazione
di credenziali. Solo **Collega a Nyanime**, premuto dall’utente, importa il token
Bearer della sezione applicazioni sul dominio HTTPS verificato. Password e secret
OAuth non vengono letti. L’inserimento manuale resta disponibile in ogni passaggio.

Il servizio richiede un token di applicazione per gli endpoint del catalogo.
Un token OAuth dell’account non è un sostituto. Il token personale non deve
essere inviato in chat, aggiunto al repository o condiviso con altri utenti.

## Date, agenda e promemoria

- **RAW**: trasmissione giapponese.
- **SUB · EN**: uscita con sottotitoli inglesi.
- **DUB · EN**: uscita doppiata in inglese.

Queste indicazioni descrivono il catalogo AnimeSchedule: non annunciano
sottotitoli italiani e non garantiscono che l’episodio sia già disponibile
nella propria estensione. I controlli della fonte e le relative notifiche di
disponibilità restano separati dai promemoria della trasmissione.

La trasmissione preferita decide la data principale e il promemoria.
Le altre date confermate restano visibili; in assenza del canale preferito si
usa RAW, se disponibile, oppure l’altro canale dichiarato dal servizio.
Le date vengono conservate come istanti assoluti e visualizzate nel fuso del
telefono, anche al cambio dell’ora legale. Le date sconosciute dei rinvii non
generano allarmi. Gli orari settimanali generici non vengono trasformati in
episodi futuri inventati.

Gli aggiornamenti sono condivisi fra i titoli, con cache settimanale e rispetto
dei limiti del servizio. I rinvii noti sostituiscono le vecchie date dello
stesso episodio. Le date lontane già confermate dal catalogo abituale restano
nell’agenda se AnimeSchedule non fornisce una sostituzione.

I link restituiti dal catalogo possono essere completi, iniziare con `//` o non
avere uno schema: vengono normalizzati prima della verifica del dominio e dell’ID.
Una risposta 404 al filtro per ID indica un titolo assente dal catalogo, non un
servizio irraggiungibile. In quel caso si conserva il catalogo abituale. Problemi
di rete, servizio o formato dei dati sono segnalati separatamente.

Il servizio restituisce 404 anche per settimane non ancora pubblicate. Fuori dalla
settimana corrente, queste risposte vengono conservate come orari assenti nella
cache condivisa: non cancellano le date del catalogo abituale. La settimana corrente
e la verifica iniziale del collegamento restano rigorose; errori di autenticazione,
limiti e guasti del server non sono convertiti in calendari vuoti.

La tempestività delle notifiche dipende dai permessi Android, dalla disponibilità
dei dati e della rete. Per gli orari annunciati si usa il sistema dei promemoria
esistente; **Stato delle uscite** permette di verificare notifiche e allarmi.
La comparsa nella fonte viene controllata in modo mirato, senza promettere un
avviso istantaneo per aggiornamenti non ancora pubblicati dall’estensione.

## Privacy e ripiego

Il token è cifrato tramite Android Keystore ed escluso dal backup portabile.
Dopo il cambio di telefono va collegato nuovamente. **Scollega ed elimina il
token** rimuove la credenziale locale. Il semplice interruttore mantiene il
collegamento salvato ma interrompe le richieste AnimeSchedule.

Vengono richiesti solo ID di catalogo e settimane pubbliche: nessun cookie di
fonte, URL di streaming, cronologia o credenziale delle estensioni viene inviato.
Il client non segue redirect con il token e non registra l’header Bearer nei log.
Gli errori di autenticazione richiedono un nuovo collegamento; le limitazioni
temporanee sospendono le richieste senza moltiplicare i tentativi.

Con il collegamento disattivato o privo di credenziale valida, torna subito
il catalogo abituale. Durante un errore di rete gli orari già verificati sono
utilizzabili per un massimo di due giorni, poi prevale il ripiego disponibile.

## Widget

Il widget **Le tue uscite** mostra le prossime uscite anime e manga dalla
stessa agenda locale, senza nuove richieste di rete. Si aggiunge dal launcher
Android o dal pulsante nella configurazione AnimeSchedule. È disponibile anche
senza attivare il servizio. Toccandolo si apre la scheda Uscite.

Anime e manga hanno accenti distinti; la modalità incognito e la preferenza
per nascondere i contenuti delle notifiche oscurano i titoli nel widget.

## Documentazione del servizio

[Accesso e token](https://img.animeschedule.net/api/v3/documentation),
[schema degli orari](https://img.animeschedule.net/api/v3/documentation/anime),
[limiti delle richieste](https://img.animeschedule.net/api/v3/documentation/ratelimits).
Orari forniti da [AnimeSchedule](https://animeschedule.net).

## Controlli della configurazione guidata

I test JVM `AnimeScheduleBrowserGuideTest` verificano origine, percorsi e risultati
di importazione. Per i moduli originali e le variazioni AJAX:

```sh
cd scripts/animeschedule-browser-tests
pnpm install --frozen-lockfile --ignore-scripts
pnpm test
```

Sono controllati registrazione, termini non preselezionati, CAPTCHA preservato,
recupero accesso, selezione esplicita fra applicazioni, assenza di token nei messaggi
di stato, sito completo senza perdita dei campi e ripiego con HTML non riconosciuto.
Questi controlli non sostituiscono la prova sul dispositivo o la verifica del token
con il servizio; nessuna credenziale reale è inclusa nei test.
