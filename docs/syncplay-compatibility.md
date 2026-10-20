# Compatibilità video Syncplay

Percorso: **Guarda insieme → Compatibilità Syncplay**. Le stanze Nyanime rimangono
la modalità principale. Non viene aggiunto un player o un decoder: si usa il video
già aperto nel player MPV e il contratto `WatchPlayer` esistente.

Si indicano esplicitamente server, porta, nickname e stanza. Nessun elenco pubblico
preimpostato. TLS è attivo inizialmente: STARTTLS precede Hello/password, usa la fiducia
di sistema e verifica il nome dell'host. Un server senza TLS non provoca downgrade
automatico. La scelta esplicita del trasporto non cifrato richiede di rileggere il consenso.

Syncplay non offre la stessa riservatezza delle stanze Nyanime: il server può leggere
nickname, nome e durata del video, stato e chat. Non inviamo URL, ID delle fonti,
cookie, header dei video o credenziali delle estensioni. La password del server non
è persistita né inserita nel backup. MD5 è usato solo dove imposto dal protocollo
ufficiale, all'interno di TLS quando abilitato; non è presentato come password hashing moderno.

Ogni nuovo video richiede una conferma locale. Non si apre automaticamente un titolo
in base al nome annunciato da un partecipante. Cambiare episodio revoca la conferma.
Pausa, ripresa e seek espliciti vengono condivisi; il buffering locale abbassa la
prontezza senza trasformarsi automaticamente in un comando di pausa per tutti.
Le correzioni di deriva riusano il controller graduale di Nyanime.
Chi entra senza un video pronto invia soltanto ping e conferme del protocollo,
senza pubblicare una posizione o una pausa. Dopo la conferma del video, il player
si allinea prima di diventare pronto e poter controllare la stanza.

Non possono controllare insieme il player una stanza Nyanime e una sessione Syncplay.
Cast, Anime4K e rilevamento dell'attenzione mantengono le restrizioni sulle stanze.
Velocità manuale e pressione per accelerare non prevalgono sulla linea temporale condivisa.
Alla disconnessione viene ripristinata la velocità precedente.

Supportati Hello, List, file, State, ping, readiness e chat se annunciati dal server.
Playlist condivise e stanze con operatore non sono annunciate né simulate. Un errore
lascia una riconnessione esplicita, senza tentativi di connessione infiniti. Socket,
linee, profondità JSON, code, membri e chat sono limitati; chiudere interrompe anche TLS pendente.

Protocollo indipendente Kotlin in `core/syncplay`. Riferimento esaminato:
Syncplay ufficiale `49e2a1bf36e5be1fc397c5bea14bed350609a9ed`, versione 1.7.7,
[Apache-2.0](https://github.com/Syncplay/syncplay/blob/master/LICENSE).
Nessun codice server, player Dart o asset Kazumi è incluso nell'APK.

Fonti: [protocollo](https://syncplay.pl/about/protocol/),
[comportamento del servizio](https://syncplay.pl/about/syncplay/).
Il test `SyncplayOfficialServerTest` è facoltativo e usa un server ufficiale locale
di prova, non una stanza pubblica: `-PsyncplayFixturePort=18999`.
Interoperabilità del protocollo, UI sul telefono e sessioni prolungate fra due
dispositivi sono verifiche distinte e vanno riportate separatamente.

Verifica del 19 ottobre 2026: 12 test JVM superati, inclusi due test contro il
server ufficiale locale 1.7.7. Il secondo controlla più heartbeat di un nuovo
osservatore durante una riproduzione già avviata, senza pause o riavvolgimenti.

Sul telefono USB reale, la build firmata 0.48.2.20 si è collegata come osservatore
al server ufficiale locale 1.7.7. Un secondo client indipendente ha ricevuto 12
campioni consecutivi con avanzamento da 33 a 44,61 secondi, senza pause o ritorni
indietro, e il messaggio di chat è comparso nell'app. Questa prova riguarda il
protocollo Android e l'interfaccia: non equivale a una sessione video prolungata
tra due telefoni, che resta da verificare separatamente.
