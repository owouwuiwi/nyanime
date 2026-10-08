# Condividere contenuti con Nyanime

## Uso

**Condividi** nella scheda invia il collegamento al titolo. Selezionando un singolo
episodio o capitolo compare anche l’azione Condividi per quel contenuto.

Nel player, **Altro → Condividi con Nyanime** permette di inviare la scheda,
l’episodio dall’inizio oppure dal minuto attuale. Nel menu del lettore si sceglie
tra scheda, capitolo dall’inizio e pagina attuale. La pressione prolungata su una
pagina offre **Condividi link a questa pagina**, separata dalla condivisione
dell’immagine. **Copia link** copia soltanto il collegamento.

Il telefono del destinatario apre Nyanime e usa l’estensione installata con lo
stesso identificatore. Se l’estensione manca o è disabilitata, viene mostrato
l’errore e, se disponibile, il suo nome. Un riferimento che non esiste più non
viene sostituito con il primo risultato di una ricerca.

I nuovi collegamenti sono HTTPS su `https://owouwuiwi.github.io/open/` e si possono
toccare nelle applicazioni di messaggistica. Android associa il dominio all'app
mediante il certificato della distribuzione pubblica. Se la versione installata
non supporta ancora questi link, il sito mostra un pulsante per aprirla usando il
contratto precedente. Si può anche condividere il messaggio ricevuto con Nyanime
tramite il menu Condividi di Android.

I link HTTPS già condivisi sul dominio precedente restano riconosciuti dall’app.
Il vecchio sito conserva l’associazione Android e inoltra al nuovo sito mantenendo
il frammento, senza inviare i riferimenti dei contenuti al server.

I file locali non sono condivisibili con un link: il destinatario non possiede
quel file. Condividere l’immagine di una pagina rimane un’azione separata.

## Contratto leggibile HTTPS v2 e compatibilità v1

Il frammento segue `v2/anime/titolo-leggibile?source=…&ref=…&title=…` (oppure
`manga`). Il titolo nel percorso è soltanto decorativo: la risoluzione usa sempre
l'identificatore dell'estensione e i suoi riferimenti esatti. I campi aggiuntivi
`item`, `itemTitle`, `at` e `page` indicano episodio/capitolo e posizione.
Tutti i riferimenti sono nel frammento, che il browser non invia al sito.
Il sito non risolve le fonti e non memorizza i contenuti condivisi.

Il contratto precedente `nyanime://open/v1#…` viene ancora riconosciuto.

I canali live usano il medium `tv` e un riferimento opaco senza episodio,
capitolo, pagina o minutaggio. Il destinatario deve avere l’estensione TV
compatibile. Il link non contiene lo stream risolto o le credenziali.

Il frammento è un JSON codificato in Base64 URL-safe senza padding. Il contratto
pubblico versionato contiene solo:

- tipo di contenuto (`ANIME` oppure `MANGA`);
- identificatore stabile dell’estensione e nome facoltativo per l’interfaccia;
- riferimento opaco del titolo e, quando presente, dell’episodio/capitolo;
- titoli per l’anteprima;
- posizione del video in millisecondi **oppure** pagina del capitolo, numerata da 1.

Non contiene ID numerici del database locale, header, cookie, credenziali,
cronologia, codici stanza o un URL estratto dal player per riprodurre lo streaming.
Il collegamento è pubblico, non cifrato: chi lo riceve può leggere i riferimenti
del contenuto. L’app non interpreta nomi, percorsi o logiche di un sito specifico.

Il decoder limita lunghezze, versione, tipo, intervalli numerici e combinazioni
di campi; rifiuta payload non UTF-8 e messaggi con più link ambigui. I campi
aggiuntivi di versioni compatibili possono essere ignorati; una versione di
protocollo sconosciuta produce un messaggio di aggiornamento/link non valido.

## Risoluzione e stato locale

La risoluzione attende il caricamento delle estensioni, cerca la scheda per
riferimento e identificatore della fonte e, se manca, ne richiede i dati
all’estensione. Per episodi/capitoli cerca il riferimento esatto; quando manca,
aggiorna soltanto quel titolo attraverso le normali primitive di aggiornamento
e rilegge il database completo. Il contenuto non viene aggiunto automaticamente
alla libreria solo perché il link è stato aperto.

Le operazioni di rete lavorano fuori dal thread UI, sono cancellabili e hanno
un limite di 30 secondi, con possibilità di riprovare. Un errore nell’episodio
o capitolo consente comunque di aprire la scheda quando è stata risolta.

Il minuto condiviso prevale sul progresso locale soltanto per l’episodio
richiesto e viene consumato al caricamento riuscito. Le normali aperture e i
cambi di episodio mantengono il comportamento di ripresa esistente. La pagina
viene validata sulle pagine caricate del capitolo; una pagina inesistente genera
un errore, senza spostare silenziosamente il lettore su un’altra pagina.

La riapertura durante una riproduzione attende il rilascio nativo del vecchio
player prima di avviare quello nuovo. L’attesa è cancellabile e dipende dal
ciclo di vita, senza ritardi fissi o una seconda inizializzazione MPV concorrente.

Una stanza o un Cast attivi fermano il collegamento a un episodio/capitolo
prima che venga chiuso il player corrente, con un messaggio che invita a
terminare la sessione. Se una sessione viene attivata durante la risoluzione,
viene proposta soltanto la scheda. Non vengono inviati comandi di sostituzione
del contenuto remoto. Le immagini condivise e le azioni per
aprire WebView/browser mantengono i rispettivi comportamenti.

## Verifica

`ContentLinksTest` copre i sei tipi di destinazione, identificatori `Long`, Unicode,
URL opachi, posizioni e pagine, varianti dall’inizio, versione sconosciuta,
payload malformati/sovradimensionati e assenza di campi privati nel contratto.
La verifica su dispositivo deve includere l’apertura sia a freddo sia mentre
player/lettore sono già aperti, il ritorno alla scheda e un’estensione assente.
`PlayerLifecycleTest` verifica che l’apertura attenda il rilascio e che annullare
il collegamento non cancelli la proprietà del player ancora attivo.
