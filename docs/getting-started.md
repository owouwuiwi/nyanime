# Primi passi e FAQ

## Installare e aggiornare

Nyanime richiede Android 8.0 o successivo. Usa un APK fornito dal manutentore
o una build del repository e scegli la variante compatibile con il dispositivo:
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` oppure universale.

Apri il file sul telefono e, se Android lo richiede, consenti all'app che lo apre
di installare applicazioni. Un aggiornamento deve avere lo stesso package e
certificato della copia installata. Le build di sviluppo e quelle distribuite
possono essere distinte o avere firme diverse.

Le versioni nuove usano quattro numeri, per esempio `0.19.0.0`. Al primo avvio
scegli gli aggiornamenti **Consigliati** oppure **Anche le anteprime**; puoi cambiare
in seguito da **Impostazioni → Aggiornamenti**, dove trovi anche **Controlla aggiornamenti**
e la scelta di scaricare e installare nell'app. Le vecchie `rNNNN` possono ancora
aggiornarsi direttamente all'ultima versione compatibile, senza installare quelle intermedie.
Il nome del file da solo non prova che l'APK sia compatibile o che abbia la stessa firma.

Prima di cambiare variante, crea un backup da **Impostazioni > Dati e archiviazione**.
I backup non includono i file multimediali scaricati. Gli APK di questa
distribuzione sono pubblicati nelle [release Nyanime](https://github.com/owouwuiwi/nyanime/releases);
l'app controlla gli aggiornamenti lì e puoi anche aggiornare manualmente.

## Preparare la libreria

1. Scegli la cartella di archiviazione richiesta dall'app.
2. Configura le estensioni e le lingue che vuoi usare, oppure aggiungi file locali.
3. Cerca un titolo e aggiungilo alla libreria anime o manga.
4. Organizzalo nelle categorie e imposta, se desideri, aggiornamenti e download automatici.
5. Collega un tracker solo se vuoi sincronizzare l'avanzamento con quel servizio.

I repository delle estensioni sono configurabili dall'utente. Nyanime non include
un catalogo di fonti private e non installa una fonte quando apri una locandina.
Una Home dedicata compare solo con un'estensione compatibile e utilizzabile.

## Le preferenze da conoscere

| Preferenza | Dove e perché |
| --- | --- |
| Aspetto ModernUI | **Impostazioni > Aspetto**. Scegli Scuro, Chiaro o Segui il sistema, con anteprime del marchio e dei colori. |
| Sfondo lettore manga | **Impostazioni > Lettore**. Lo sfondo di lettura resta indipendente dall’aspetto generale. |
| Avvio automatico di Smart | **Impostazioni > Lettore interno > Anime4K**. Attivo di default; disattivarlo lascia disponibile l'attivazione manuale SM. |
| AniSkip | Nelle impostazioni del lettore interno, sezione salto dell'introduzione. Richiede l'associazione del titolo tramite un tracker supportato. |
| Fiducia automatica nelle estensioni | **Impostazioni > Avanzate**. Attiva di default; disattivala e riavvia per tornare ai consensi espliciti. Le estensioni eseguono codice nell'app. |
| Timer di sospensione | Nel menu **Altro** del player. Durata rapida o personalizzata, oppure fine episodio. |
| Ordine degli episodi | Nei dettagli del titolo, apri i controlli dell'elenco **Episodi** e scegli ordine e filtri. |
| Solo scaricati e incognito | Dal menu **Altro** dell'app; influiscono anche sui contenuti mostrati nelle Home. |

## Guardare con gli amici

Apri **Guarda insieme** dalla Home o dal menu del player. Chi crea la stanza
condivide il codice, il link o il QR e sceglie l'episodio; gli altri entrano e lo
aprono attraverso la propria estensione.

Per l'apertura automatica occorrono la stessa estensione utilizzabile, accesso
al contenuto e un'edizione compatibile. Tenete aggiornate entrambe le app.
Non serve aprire porte sul router: servono connessioni in uscita ai relay Nostr.

Il Play mostra subito la preparazione; quando i partecipanti sono pronti compare
il conto alla rovescia comune. Puoi vedere chi sta ancora caricando e riprovare
un comando non confermato. L'host può limitare i controlli condivisi o l'attesa di tutti.
Il buffering può mettere in pausa la stanza, secondo queste impostazioni.

Anime4K resta disattivato finché fai parte della stanza. Dopo un timer scaduto
o una pausa locale protetta devi premere Play sul tuo telefono per ripartire.
La barra della stanza permette di tornare al player mentre navighi nell'app.

## Trasmettere alla TV

Collega telefono e ricevitore Google Cast o UPnP/DLNA alla stessa rete, apri
il selettore Cast e scegli il dispositivo. Il telefono diventa un telecomando:
puoi usare l'app, cambiare episodio o scegliere un altro video.

Se la TV non compare, verifica che la rete consenta ai dispositivi di comunicare.
Se compare ma non riproduce, il formato o le tracce potrebbero non essere supportati.
La luminosità remota è disponibile soltanto sui ricevitori DLNA che la espongono;
Anime4K non viene trasmesso. La [guida Cast](casting.md) descrive gli altri limiti.

## Domande frequenti

### Perché una categoria o una Home non compare?

Le categorie delle fonti dipendono dalle capacità dichiarate dall'estensione.
Controlla installazione, fiducia, abilitazione e lingua. Non tutte le estensioni
espongono una Home. Senza Home manga compatibile rimane la biblioteca legacy.

### Perché mancano copertine o sezioni?

Le immagini e i feed dipendono dalla fonte o dal catalogo. La Home mantiene
i dati disponibili, recupera le immagini tramite l'estensione e limita i tentativi.
Usa il nuovo tentativo della sezione o dell'immagine, oppure trascina per aggiornare.
Controlla anche Solo scaricati e lo stato dell'estensione. Il calendario indica
la messa in onda, non necessariamente la disponibilità dell'episodio nella fonte.

### Perché non vedo un titolo in Continua a guardare?

La riga usa la cronologia locale. Controlla incognito, filtro Solo scaricati,
avanzamento e titoli nascosti. Il comando di ripristino dei nascosti non richiede
di cancellare o ricostruire la libreria.

### Come cambio tra tema chiaro e scuro?

Apri **Impostazioni > Aspetto** e scegli **Scuro**, **Chiaro** o **Segui il sistema**.
Il tema chiaro usa Arancio solare, quello scuro mantiene il rosso. La scelta
iniziale viene richiesta una sola volta dopo la conferma; cambiare tema conserva
librerie, cronologia e progressi e non richiede reinstallazione.

### Disattivare l'avvio di Smart disattiva Anime4K per sempre?

No. Impedisce l'avvio automatico di Smart; SM resta selezionabile nel player.
Le scelte manuali salvate per episodio mantengono il proprio comportamento.
Nelle stanze tutte le modalità Anime4K sono temporaneamente indisponibili.

### Perché il timer continua a scendere durante la pausa?

Misura il tempo trascorso, non i minuti effettivamente riprodotti. Alla scadenza
ferma il video e blocca l'autoplay. Per fermarti alla conclusione scegli
“Alla fine dell'episodio”. Non è previsto lo spegnimento forzato dello schermo.

### “App non installata”: devo disinstallare tutto?

Non come primo tentativo. Verifica che il trasferimento sia completo, che l'ABI
sia compatibile e che package, firma e versione permettano l'aggiornamento.
Con ADB, il messaggio di installazione può fornire il motivo preciso del rifiuto.
Conserva prima un backup e i file locali: la disinstallazione può rimuovere dati.

### Il video carica a lungo: è colpa della UI o di Smart?

L'indicatore di buffering da solo non identifica la causa. Riporta se accade
all'avvio, dopo un avanzamento o dopo la ripresa; specifica stanza/Cast, modalità
Anime4K, qualità selezionata, dispositivo e versione. Rete, risoluzione del link,
contenitore e decodifica vanno distinti dalle animazioni. Vedi
[come segnalare un problema](../CONTRIBUTING.md).

### I backup mi permettono di guardare o leggere offline?

Il backup conserva i dati selezionati, non gli episodi o le pagine. Per l'uso
offline scarica i contenuti o usa file locali supportati. Conserva separatamente
i file multimediali quando cambi dispositivo.

### Incognito rende anonima la connessione?

No. Riduce la registrazione dell'attività nell'app; non nasconde l'indirizzo IP
alle fonti, ai tracker o ai relay utilizzati.
