# Novità Nyanime

Questo file descrive le modifiche del fork Nyanime. Le funzioni ereditate e mantenute
sono incluse nel [catalogo completo](docs/features.md). La documentazione corrente
è raccolta nell'[indice](docs/README.md).

Le nuove versioni usano quattro numeri `X.Y.Z.W`, con `versionCode` Android crescente.
Le vecchie revisioni `rNNNN` restano riconosciute dal sistema OTA. Gli hash qui sotto
identificano modifiche nel repository, non garantiscono che ogni commit sia stato
distribuito come APK.


## 20 ottobre 2026 — 0.51.0.0: tracking personale e controllo degli avvisi

### Novità

- Nuovo pannello di tracking comune per anime e manga: stati a scelta rapida, progresso con meno/più o inserimento diretto, voto interattivo e dettagli personali. Funziona anche senza account, con dati locali separati dai servizi collegati.
- Distribuzione reale dei voti della community e media AniList quando sono disponibili un ID verificato e il consenso alle informazioni pubbliche sulle opere. Il voto personale resta utilizzabile anche senza statistiche.
- Note, tag, date, rivisioni/riletture e volumi personali; inclusione dei record locali nei backup della libreria e del tracking.
- Gestione delle notifiche per singolo titolo, con ricerca e filtri anime/manga. Silenziare gli avvisi non rimuove il titolo da libreria, agenda o tracker.
- Opzione facoltativa per ricevere avvisi soltanto dai titoli seguiti esplicitamente, conservando l'attuale comportamento automatico come impostazione iniziale.

### Interfaccia

- Salvataggio automatico con Annulla, stato della sincronizzazione, Riprova e gestione delle modifiche in attesa. I servizi mantengono le proprie scale di voto e capacità; associazione e rimozione restano disponibili.
- Avviso immediato nelle impostazioni se Android blocca le notifiche, ricontrollo dei permessi al ritorno e prova di un promemoria programmato tra un minuto, anche uscendo dall'app.
- Bordi gialli nella Home e indicatore di attenzione accanto alla musica quando Android blocca gli avvisi. L'indicatore spiega come riabilitarli e sparisce al ripristino dell'accesso.
- Etichette e messaggi in italiano e inglese, transizioni coordinate e rispetto delle animazioni ridotte.

### Affidabilità e privacy

- Modifiche remote conservate prima dell'invio, limitate ai campi cambiati e vincolate all'account originale. Una risposta in ritardo non cancella modifiche più recenti o Annulla.
- Coordinamento con tracking automatico e refresh per proteggere gli arretramenti manuali del progresso. Le modifiche al tracking non segnano automaticamente come visti o letti i contenuti locali.
- Le stagioni automatiche rispettano le preferenze di avviso della serie; una scelta esplicita sulla singola stagione resta indipendente.
- Le scelte di avviso sono ricontrollate prima della consegna. Notifiche bloccate da Android o da un canale non vengono considerate ricevute.
- Nessuna nuova dipendenza, catalogo di fonti o logica specifica di siti nel core.

## 20 ottobre 2026 — 0.50.0.0: aggiornamenti differenziali

### Novità

- Gli aggiornamenti in-app possono scaricare soltanto le parti cambiate dell’APK quando è disponibile una patch compatibile e il risparmio è significativo. Nyanime ricostruisce il pacchetto completo senza modificare l’app installata.

### Interfaccia

- Preparazione del download, aggiornamento ridotto, ricostruzione e verifica hanno stati dedicati nella schermata e nella notifica, in italiano e inglese. È possibile annullare anche durante la preparazione.

### Sicurezza

- Verifica degli hash della base, della patch e del risultato, con limiti su dimensioni, comandi e memoria. Gli APK completi e ricostruiti devono superare la verifica crittografica della firma, dell’identità e della versione prima dell’installazione.

### Distribuzione

- Ripiego automatico sull’APK completo se la patch manca, non conviene o non supera i controlli. La prima versione con il nuovo aggiornatore richiede ancora il download completo; gli aggiornamenti successivi possono beneficiare delle patch pubblicate.
- Preparazione locale e pubblicazione manuale supportano materiali differenziali facoltativi verificati. Gli APK completi e i percorsi dei client precedenti restano disponibili.

## 20 ottobre 2026 — 0.49.0.0: Studio, scambio fonti e nuovo player

### Novità

- Studio delle fonti video e manga: canvas a nodi colorati, vista guidata e procedura Assistita per ricavare regole da esempi scelti dall’utente. Ricerca, dettagli, episodi, capitoli, contenuti, Home e filtri usano i contratti generici dell’app, senza siti o cataloghi preconfigurati.
- Bozze separate dalle versioni attive, prove esplicite dei flussi, importazione ed esportazione, consenso agli script e ripristino della versione precedente. L’attivazione viene rinviata durante l’uso della fonte; identità e progressi sono conservati.
- «Scambia fonti» nello Store e nello Studio: invio e ricezione tramite codice, link o QR, sulla rete locale o via Internet. Definizioni personali e pacchetti già supportati, incluse estensioni APK, passano dalla revisione e dai controlli esistenti prima dell’attivazione.
- Backup completi cifrati su WebDAV e integrazione facoltativa Google Drive: chiave di recupero, verifica del caricamento, anteprima, frequenza, rete e conservazione per destinazione. Drive richiede la registrazione OAuth propria di Nyanime; la guida mostra pacchetto e certificato della build installata.
- Compatibilità video Syncplay facoltativa con server, nickname, stanza e consenso espliciti. Le stanze Nyanime restano disponibili; l’app distingue buffering locale, stato condiviso e conferma del video.
- Attenzione alla visione sperimentale, inizialmente disattivata: pausa configurabile per distrazione o occhi chiusi, ritorno al punto perso e tasto con l’occhio nel player. La modalità «Solo rilevamento» conserva soltanto intervalli locali stimati, senza pause o avvisi; la pausa automatica non raccoglie tali intervalli.
- Opening ed ending nelle schede anime, con metadati, versioni, cache e protezione degli spoiler. I collegamenti video e le ricerche richieste dall’utente aprono un’app esterna: nessun motore YouTube è incluso.
- Episodi raggruppati per stagioni o raccolte dichiarate dalla fonte, con avanzamento, conteggi e ripresa nella sezione corretta; le sezioni separate vengono caricate all’apertura.
- Sandspiel offline fra le funzioni nascoste: cinque tocchi sul numero di versione in Info, simulazione, pennelli, annullamento e ripresa della creazione.

### Interfaccia

- Controlli, pannelli, timeline e indicatori del player nello stile FLOW approvato, isolati dal suo motore e adattati al player nativo Nyanime. Conservate animazioni, accessibilità e preferenza per il movimento ridotto.
- Trascinamento della timeline con anteprima del punto e dello scostamento, pausa temporanea e un solo seek al rilascio. Annullamento, tastiera e accessibilità mantengono comportamenti distinti e coerenti.
- Amplificazione del volume fino al 200%, gesti e indicatore dedicati; transizione Nuvola di SM ottimizzata con un solo video e passaggi temporanei rilasciati a fine effetto.
- Il tasto Musica resta nella testata e apre una pagina che annuncia l’arrivo della funzione nelle prossime settimane. Non include riproduzione, account o servizi musicali.
- Conservata l’identità rossa in scuro e arancione in chiaro, senza la variante blu sperimentale.

### Correzioni

- Le brevi attese dopo un doppio tap non fanno ricomparire per un istante barra di avanzamento e pulsanti nascosti. Le attese prolungate hanno un indicatore separato; errori, caricamento iniziale e preparazione delle stanze restano utilizzabili.
- Il feedback del doppio tap mantiene lato e valore durante la dissolvenza. Un seek orizzontale conserva il gesto anche se il dito devia in verticale, evitando il trascinamento involontario del player verso il basso.
- Conservate le correzioni a riproduzione, audio separato, selezioni manuali, PiP e ripristino delle schermate della versione ordinaria.
- Allineate e controllate le versioni delle librerie grafiche e l’accesso ai ViewModel per evitare incompatibilità nelle build ottimizzate.
- Il database delle attività in background conserva il costruttore usato all’avvio anche senza i moduli opzionali rimossi. La distribuzione controlla questo requisito direttamente negli APK ottimizzati.
- WorkManager e Room mantengono le versioni già distribuite in NyanimeB, evitando un ritorno al vecchio schema del database e la ricreazione delle attività programmate durante l’aggiornamento.
- I campi storici dei backup appartenenti a funzioni escluse restano riservati: non vengono riutilizzati con significati diversi.

### Prestazioni

- Anime4K Smart considera sovraccarico GPU e fotogrammi effettivamente persi, separandoli da buffering, seek, decoder e misure incomplete. Recupero graduale della qualità con tentativi limitati; le calibrazioni recenti non impongono limiti permanenti e le scelte manuali mantengono la precedenza.
- Analisi iniziale dell’immagine facoltativa e sperimentale, inizialmente spenta: piccoli campioni locali, una sola acquisizione e nessun salvataggio o invio delle immagini.
- Fotocamera, modelli e trasferimenti si avviano soltanto all’uso delle rispettive funzioni. Il rilevamento si sospende nei contesti esclusi e riduce il carico per proteggere la riproduzione.

### Sicurezza

- Gli script importati richiedono consenso e vengono eseguiti in un processo isolato con limiti; importare un file non lo rende automaticamente una fonte attiva.
- Credenziali delle fonti separate dai documenti condivisi; risultati delle prove e risposte sensibili esclusi dai backup dello Studio.
- Per l’attenzione non vengono salvate o inviate immagini o misure del volto. La registrazione delle statistiche upstream è sostituita da un’implementazione locale documentata; incognito e cancellazione separata dei dati restano rispettati.

### Compatibilità

- Conservati pacchetto e firma Android per aggiornare senza disinstallare. Versione 0.49.0.0 e codice 280, superiori alla prova NyanimeB 0.48.2.23 / 279.
- Nessun nuovo metodo obbligatorio per le estensioni esistenti; nessuna regola specifica di una fonte nell’app o nei test.
- Backup locali e Telegram continuano a usare i percorsi esistenti. File multimediali scaricati esclusi dai backup completi.

### Distribuzione

- Aggiornamento Consigliato, con controllo OTA automatico e accesso alle impostazioni degli aggiornamenti attivi nella variante firmata. Il nome tecnico della variante di compilazione non determina il canale della release.
- Rimossi integralmente moduli, submoduli e dipendenze dei motori FLOW/YouTube e MELD: nessuna Home YouTube, estrazione, account o servizio di quei client nella build ordinaria.
- Conservato soltanto il sottoinsieme grafico FLOW, con licenza GPL-3.0, attribuzioni, revisione originale e modifiche documentate; i componenti originali Nyanime del modulo mantengono Apache-2.0.
- Inventari delle dipendenze, licenze offline, sorgenti corrispondenti e ricette native aggiornati per la nuova distribuzione.
- Riunita qui anche la cronologia documentata delle prove NyanimeB: ogni sezione è marcata come storico sperimentale e viene esclusa dalle nuove note OTA. Le versioni ordinarie precedenti restano integralmente conservate; non vengono inventate note per numeri interni senza una voce documentata.

### Limitazioni

- Precisione del rilevamento dello sguardo sperimentale: nessuna garanzia di determinare l’effettiva attenzione della persona. Google Drive richiede un client OAuth proprio; disponibilità e puntualità dei servizi remoti dipendono dai relativi fornitori.
- Traduzione/OCR manga, motori musicali e client YouTube esclusi. Android TV non modificato.

## 19 ottobre 2026 — 0.48.2.23: controlli stabili durante i seek [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Le brevi attese dopo un doppio tap non riaprono più barra di avanzamento e pulsanti personalizzati quando i controlli sono nascosti.
- Il caricamento prolungato mantiene un indicatore dedicato, senza riaccendere l'intera interfaccia. Restano disponibili controlli iniziali, preparazione delle stanze ed errori.

Build privata di prova NyanimeB, senza OTA ordinaria.


## 19 ottobre 2026 — 0.48.2.22: ritorno ai controlli Flow [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Ripristinati controlli, pannelli, indicatori e animazione del doppio tap basati sulla presentazione Flow già usata in NyanimeB, con licenze e attribuzioni originali conservate.
- Il feedback del doppio tap mantiene lato e valore durante la dissolvenza, evitando cambi improvvisi quando termina il gesto.
- Un seek orizzontale conserva il controllo del gesto anche se il dito devia verticalmente: il player non viene trascinato verso il basso durante la ricerca del punto desiderato.
- Mantenute le correzioni di trascinamento, annullamento dei seek, intenzione di pausa e riproduzione, audio e PiP.

Build privata di prova. Il trasferimento verso Nyanime normale resta sospeso in attesa del riscontro sul player.


## 19 ottobre 2026 — 0.48.2.21: interfaccia del player indipendente [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Controlli, pannelli e indicatori del player gestiti da un componente Nyanime indipendente dal motore video opzionale.
- Conservati impostazione compatta e a schermo intero, animazioni native, strumenti e annullamento sicuro della barra temporale.
- Preferenze di animazione ridotta e accessibilità applicate anche al nuovo renderer.

Build privata di prova; verifiche e limiti nella documentazione del player. Nessuna pubblicazione OTA ordinaria.


## 19 ottobre 2026 — 0.48.2.20: backup remoti e compatibilità video [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Corretto un arresto nel pannello informazioni del video causato da versioni diverse della libreria grafica fra Musica, video e app. Aggiunti controlli sulle dipendenze e sull’APK ottimizzato.
- Backup completi cifrati su WebDAV e integrazione facoltativa Google Drive, con chiave di recupero, verifica dei caricamenti e anteprima prima del ripristino.
- Frequenza, reti senza consumo e conservazione configurabili separatamente per destinazione. I backup locali e Telegram continuano a usare i percorsi esistenti.
- Guida OAuth con pacchetto e certificato della build installata. Google Drive richiede ancora la registrazione Nyanime prima della prova con un account reale.
- Compatibilità video Syncplay esplicita: server e stanza personali, consenso alla visibilità dei dati, conferma del video e sincronizzazione nel player esistente. Le stanze Nyanime restano la modalità principale.
- Episodi organizzati in sezioni espandibili quando l’estensione dichiara stagioni o raccolte, con conteggi, avanzamento e ripresa nella sezione corretta.
- Ricerca, indice e azioni sugli episodi conservano identità e progressi; caricamento delle stagioni separate su apertura, senza recuperare tutto in anticipo.
- Trascinamento nel player con pausa temporanea, un solo spostamento al rilascio e ripresa coerente con lo stato precedente.
- Indicatore di destinazione e scostamento sempre visibile, anche senza miniature o con i comandi nascosti.
- Un gesto annullato sulla barra temporale non applica lo spostamento; il rilascio resta distinto dai comandi da tastiera e accessibilità.
- Gioco segreto Sandspiel offline su NyanimeB: cinque tocchi sul numero di versione in Info, materiali e fluidi originali, pennelli, annullamento e ripresa dell’ultima creazione in pausa. Corretti l’avvio locale e un errore durante il trascinamento con simulazione attiva.

Build privata di prova, senza pubblicazione OTA ordinaria. Traduzione e OCR manga esclusi da questa consegna.


## 18 ottobre 2026 — 0.48.2.19: scambio privato delle fonti [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Nuovo accesso «Scambia fonti» nello Store e nello Studio: invio e ricezione espliciti tramite codice o QR, sulla stessa rete oppure via Internet.
- Invio delle definizioni personali e dei pacchetti supportati, incluse le estensioni APK; i file ricevuti restano separati dalle fonti attive fino alla revisione e all’importazione.
- Sessioni temporanee con arresto esplicito, verifica del contenuto e limiti di dimensione. Il motore di trasferimento si carica in un processo dedicato soltanto all’avvio di uno scambio.
- La revisione di una nuova bozza con script conserva il consenso alla versione attiva mentre è ancora in uso; il ripristino di un backup richiede nuovamente l’approvazione locale.

Build privata arm64 installata e provata sul telefono USB, senza pubblicazione OTA ordinaria. Verificati trasferimenti locali e via relay Internet su Wi-Fi; limiti ed evidenze sono documentati in `docs/source-studio.md`.


## 18 ottobre 2026 — 0.48.2.18: creazione assistita delle fonti [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Procedura Assistita per scegliere esempi nel sito, generare normali nodi e approvare ogni passaggio dopo una prova HTTP: ricerca, scheda, episodi/capitoli e contenuti.
- Sezioni Home e generi/filtri configurabili separatamente. Le etichette dei filtri possono corrispondere a valori diversi nelle richieste.
- Il selettore visuale distingue navigazione e selezione; gli esempi che non sono riproducibili tramite le regole proposte richiedono una correzione o l’editor.
- Ripristinati fondo e marchi della splash precedenti alla variante blu, sia in chiaro sia in scuro.
- L’annullamento di una prova chiude anche lo stato di esecuzione dei nodi e impedisce ai risultati precedenti di riattivarlo.

Build privata di NyanimeB in collaudo. Lo scambio P2P delle fonti è un’integrazione distinta ancora in preparazione, non una funzione già distribuita.


## 18 ottobre 2026 — 0.48.2.17: Studio delle fonti su NyanimeB [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Studio per creare fonti personali video e manga: vista guidata e canvas con blocchi colorati, collegamenti, proprietà, annulla/ripeti e prove esplicite dei flussi.
- Selezione visuale nelle pagine, lettura HTML e JSON, campi delle schede, episodi, capitoli, video e sezioni Home tramite i contratti già presenti nell’app.
- Definizioni dichiarative importabili ed esportabili, bozze separate dalle versioni attive, ripristino della versione precedente e integrazione con il backup delle fonti. Credenziali locali separate dai documenti condivisi.
- NyanimeB mantiene soltanto l’identità rossa in modalità scura e arancione in modalità chiara, comprese icona e scritta; rimossi scelta, sblocco e risorse della variante blu.

Versione destinata alle prove private. Verificati su telefono USB ricerca, scheda, avvio del video, apertura e cambio pagina nel lettore manga, attivazione rinviata e interruzione degli script fuori tempo. Nessuna pubblicazione sull’OTA ordinaria.


## 18 ottobre 2026 — 0.48.2.16: Musica in NyanimeB [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

- Testata Nyanime con logo, categorie, Stanze e Musica; pulizia delle impostazioni obsolete durante avvio, backup e ripristino.
- Nuovo spazio Musica con interfaccia rossa, ricerca, libreria, playlist, podcast e controlli del lettore basati su MELD. Accesso dalla testata con il tasto cuffie e configurazione facoltativa degli account.
- Riproduzione in background, controlli durante la lettura e coordinamento con i lettori video. Database, download, cache e accessi musicali separati dal resto dell’app.
- Collegamento al Cast esistente attraverso un contratto audio generico; dati musicali portabili nel backup, senza file scaricati o segreti degli account.
- Crediti e licenze conservati, con sorgenti originali verificabili e materiali corrispondenti per la build privata.


## 18 ottobre 2026 — 0.48.2.13: Solo rilevamento [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Novità

- Nuova modalità facoltativa «Solo rilevamento»: il video continua senza pause, riprese o avvisi automatici, conservando soltanto intervalli stimati di attenzione per un futuro riepilogo della visione.
- Intervalli locali separati per video e inclusi nel backup delle impostazioni; nessun salvataggio in incognito. La pausa automatica non raccoglie questi dati e i progressi restano invariati.
- Scelta della modalità, conteggio dei video con dati salvati e cancellazione separata nelle impostazioni; lo stesso tasto con l’occhio attiva o disattiva la funzione nei due player.

### Prestazioni

- Aggregazione leggera in memoria e salvataggi fuori dal thread dell’interfaccia; nessuna registrazione di immagini o misure del volto. Fotocamera e raccolta si fermano nei contesti esclusi o disattivando la funzione.


## 17 ottobre 2026 — 0.48.2.11: Riconoscimento occhi nei controlli [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Novità

- Tasto con l'occhio nei controlli dei due player per attivare o disattivare il riconoscimento senza lasciare il video; accanto a SM nel player nativo, celeste quando attivo e barrato quando spento.
- Se manca il permesso della fotocamera, il tasto apre la spiegazione e le impostazioni della funzione. La disattivazione rilascia il rilevatore senza riprendere un video già in pausa.


## 17 ottobre 2026 — 0.48.2.10: Attenzione alla visione e sigle [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Novità

- Nuovi controlli facoltativi per mettere in pausa dopo una distrazione o una chiusura prolungata degli occhi, con tempi e ripresa configurabili separatamente.
- Recupero del punto in cui si è smesso di guardare, tramite pulsante o ritorno automatico scelto dall’utente; punti ordinari inclusi nei backup e punti in incognito soltanto in memoria.
- Verifica e adattamento facoltativi per la posizione di visione; le letture incerte non intervengono sulla riproduzione. La funzione è sperimentale e parte disattivata.
- Un solo rilevatore per i due player, senza riprese in background e con sospensione in PiP, Cast, stanze, Shorts e dirette. Il carico della fotocamera viene ridotto o sospeso per preservare il video.

- Opening ed ending nelle schede anime, con brano, artista e versioni fornite dai cataloghi pubblici. Sigla dell’anime e brano completo restano scelte distinte.
- Video YouTube aperti nel player integrato; ricerca guidata e scelta delle alternative quando manca un collegamento verificato. Le preferenze sui video scelti sono incluse nel backup delle impostazioni.

### Prestazioni

- Metadati delle sigle conservati in cache, richieste condivise e servizi indipendenti: un errore non elimina i dati disponibili. La ricerca dei video parte soltanto su richiesta.

### Sicurezza

- Anteprime delle sigle con spoiler protette e scelte in incognito mantenute soltanto in memoria, senza aggiungere le ricerche alla cronologia.


## 16 ottobre 2026 — 0.48.2.9: Icona celeste più leggibile [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Aspetto

- Icona Android con N celeste luminosa, sfumature blu e stelline: più riconoscibile sul fondo blu notte, anche su Home scure. Aggiornate sia l'icona adattiva sia quelle tradizionali.


## 16 ottobre 2026 — 0.48.2.8: Nuvola SM, disegno ottimizzato [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Player

- Il contorno luminoso della Nuvola usa un unico disegno a sfumatura continua e un livello grafico dedicato, per ridurre il carico dell'animazione durante la riproduzione.
- Forma organica, colori e visibilità sono mantenuti; gli shader, i preset e la qualità del video SM restano invariati.


## 16 ottobre 2026 — 0.48.2.7: Smart, qualità e fluidità a caldo [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Player

- Smart considera anche le perdite effettive nella presentazione del video: il solo margine GPU non può nascondere scatti ripetuti durante un carico di rendering significativo.
- Picchi isolati, problemi del decoder e misure incomplete restano distinti; il recupero graduale della qualità mantiene i limiti per episodio e rispetta le scelte manuali.
- Corretto il formato delle misure native di Anime4K: tempi GPU reali disponibili al controller e alla diagnostica, con ripristino dei preset superiori quando il margine torna sufficiente.
- Nuvola SM più visibile e leggera, con un solo video e una scia 2D morbida; rimossa la composizione sperimentale che poteva causare scatti.


## 16 ottobre 2026 — 0.48.2.6: Nuvola SM su NyanimeB [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Player

- Attivazione manuale di SM con rivelazione a nuvola dal pulsante: il video originale e quello migliorato continuano a muoversi insieme durante il passaggio.
- Ritorno discreto alla visualizzazione normale, rispetto delle animazioni ridotte e rilascio dei passaggi temporanei alla conclusione.
- La transizione resta separata dalla calibrazione Smart e conserva qualità, preset e impostazioni personali del renderer.


## 16 ottobre 2026 — 0.48.2.5: Smart più reattivo [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Player

- Anime4K Smart reagisce agli overload GPU consecutivi con fotogrammi persi e può recuperare gradualmente qualità dopo una fase stabile, con tentativi limitati e ripristino automatico se il livello superiore non regge.
- Le calibrazioni recenti restano un suggerimento locale, con scadenza: nessun limite permanente e nessuna impostazione degli episodi cancellata.
- Nel pannello del player sono visibili preparazione, preset attivo e recupero della qualità. Analisi iniziale dell’immagine opzionale e sperimentale, disattivata inizialmente: solo piccoli campioni locali, senza invio o salvataggio delle immagini.


## 16 ottobre 2026 — 0.48.2.4: Blu notte e stelline [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Aspetto

- Nuova identità blu notte per NyanimeB, con superfici petrolio, accenti indaco e loghi con stelline, anche nell’icona Android.
- Indicatore di aggiornamento a isola uniforme nelle Home e nelle altre schermate con trascinamento per aggiornare.
- Modalità chiara, scura, automatica e AMOLED conservate. Il tema classico ripristina i precedenti colori rosso/arancione, con una scelta indipendente dalla luminosità.
- Colore e sblocco vengono conservati nei backup completi; i loghi mantengono trasparenza e fori delle lettere.

### Distribuzione

- Revisione successiva alla build privata: completati i metadati e gli avvisi offline di componenti e script video; documentati licenze originali, modifiche e materiali richiesti prima di una distribuzione NyanimeB.
- Documentazione dell'identità blu aggiornata; i sorgenti esportati conservano anche il modulo video e la sua ricetta di integrazione.


## 16 ottobre 2026 — 0.48.2.3: Amplificazione fino al 200% [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Correzioni

- Il limite predefinito del volume arriva al 200% reale del mixer del video, conservando i limiti personalizzati nelle impostazioni audio. La regolazione fine e la lettura dei valori decimali sono applicate anche al motore incorporato.


## 16 ottobre 2026 — 0.48.2.2: Volume preciso con il gesto [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Correzioni

- Il trascinamento del volume regola anche il mixer del video fra i livelli di Android: niente più salti del 6–7%. Regolazione continua dalla modalità silenziosa fino all'amplificazione consentita, mantenendo distinti azzurro e viola.


## 16 ottobre 2026 — 0.48.2.1: Amplificazione leggibile [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Correzioni

- Il volume conserva la scala 0–100% azzurra e sovrappone in viola soltanto l'amplificazione: al 130%, lo 0–30 è viola e il 31–100 rimane azzurro.
- La stessa distinzione separa giallo e arancione negli indicatori di luminosità che supportano valori amplificati.


## 16 ottobre 2026 — 0.48.2.0: Player FLOW e ricerca integrata [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Interfaccia

- Il player di anime e serie utilizza la testata, la timeline, i comandi e il pannello delle impostazioni di FLOW. I comandi continuano a usare MPV, mantenendo tracce, qualità, Anime4K, gesti, stanze, timer e pulsanti personalizzati.
- Schermata di riproduzione riorganizzata con episodio in primo piano, riga della serie e azioni compatte. Anche i canali TV utilizzano i controlli condivisi, conservando guida, preferiti e capacità della diretta.
- Cerca su YouTube utilizza la barra inferiore di Nyanime, mantenendo suggerimenti, cronologia, filtri e risultati nativi. Le altre sezioni continuano a cercare nelle estensioni, senza includere YouTube.
- Home, ricerca e libreria video condividono lo sfondo e i colori dell'app, senza una superficie nera separata.
- Il comando SM rimane compatto e diventa giallo quando è attivo. Un tocco sul video nasconde i comandi anche durante la pausa.
- Indicatori dei gesti distinti per colore: volume azzurro e viola in amplificazione; luminosità gialla e arancione per valori oltre il 100%, quando supportati.

### Prestazioni

- I controlli condivisi non avviano il motore YouTube quando viene riprodotto un anime. Durante lo scorrimento della timeline MPV aggiorna l'anteprima e applica il salto al rilascio.


## 16 ottobre 2026 — 0.48.1.1: Riproduzione e ricerca contestuale [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Correzioni

- Corretto il crash all'avvio degli anime dovuto a un'incompatibilità fra le API AndroidX usate in compilazione e quelle presenti nell'app. Il modello del player utilizza ora la factory pubblica e la stessa versione di Lifecycle del modulo video.

### Interfaccia

- Cerca dalla Home YouTube e dalla libreria Video apre la ricerca dedicata di YouTube. Nelle altre sezioni Atlante cerca nelle estensioni ed esclude YouTube, anche dalle categorie e dai risultati di esplorazione.
- La pressione prolungata su Cerca continua ad aprire Sfoglia. Uscendo dalla ricerca YouTube si torna alla schermata di provenienza.


## 16 ottobre 2026 — 0.48.1.0: NyanimeB, integrazione video privata [storico sperimentale]

**Ambito: NyanimeB.** Note storiche della prova, non un elenco delle funzioni incluse nella versione ordinaria attuale.

### Novità

- Primo sviluppo privato di YouTube integrato nella Home, nella Libreria e nella ricerca Atlante. Il modulo video è compilato nella stessa app; non richiede un addon o un'app separata.
- Pagine video, Shorts, canali, iscrizioni e playlist utilizzano l'interfaccia nativa di FLOW, conservandone attribuzioni e licenza. La modalità musicale resta esclusa dalla navigazione.

### Prestazioni

- Avvio differito del modulo video, preparazione delle cache fuori dal thread dell'interfaccia e gestione esplicita del passaggio fra i motori di riproduzione.
- Le ricerche video condividono il limite di concorrenza di Atlante, con annullamento delle query superate e paginazione indipendente.

### Distribuzione

- Sviluppo nel repository privato NyanimeB, senza pubblicazione nei canali OTA dell'app principale. Su richiesta del proprietario l'APK conserva identificativo e firma di produzione per aggiornare l'installazione esistente.


## 16 ottobre 2026 — 0.48.0.2: Licenze e attribuzioni offline

Versione preparata nel codice, non ancora pubblicata.

### Correzioni

- Completati i testi delle licenze WebRTC e dei componenti nativi del player nell'inventario delle librerie, conservando gli avvisi originali già inclusi.
- La schermata delle licenze legge direttamente l'inventario incluso nell'app, mantenendolo disponibile anche dopo l'ottimizzazione delle risorse.

### Distribuzione

- Registri degli avvisi condivisi tra schermata e verificatore; controlli estesi all'inventario e ai testi effettivamente presenti negli APK.
- Aggiornati crediti, istruzioni per i sorgenti nativi e documentazione delle licenze.

## 16 ottobre 2026 — 0.48.0.1: Ritorno alla versione consolidata

### Correzioni

- Ripristinati il codice e le dipendenze della versione consolidata, mantenendo le correzioni audio già rilasciate.

### Distribuzione

- Nuova versione Consigliata con numerazione superiore alle prove locali, per aggiornare normalmente i dispositivi senza disinstallare l’app.

## 15 ottobre 2026 — 0.47.0.3: Audio e selezione delle tracce

### Correzioni

- L’audio scelto viene preparato prima di avviare il video. Le altre lingue e i sottotitoli restano disponibili e vengono aperti soltanto quando servono.
- I salti avanti e indietro con audio separato usano la ricerca precisa, evitando il percorso a fotogrammi chiave che può lasciare l’audio in ritardo.
- Aggiornare l’elenco delle tracce non ripristina più la lingua predefinita sopra una scelta manuale. Cambi di episodio e qualità annullano le selezioni ormai superate.
- Un vecchio salto non può riportare indietro l’indicatore dopo una nuova ricerca precisa. Gli errori di apertura di una traccia vengono segnalati senza selezionare silenziosamente un’altra lingua.

### Prestazioni

- Ridotte le letture native necessarie ad aggiornare audio e sottotitoli; le operazioni di rete sulle tracce restano fuori dal thread dell’interfaccia.
- Nessuna apertura preventiva delle alternative non utilizzate, anche quando un video offre molte lingue.

## 15 ottobre 2026 — 0.47.0.2: Il salotto delle stanze

### Novità

- Nuovo salotto con partecipanti, posti, microfoni e stato di caricamento. Inviti, voce e chat riuniti nella stessa stanza per video, manga e TV.
- Chat temporanea cifrata, reazioni disattivabili e pannello adattato a verticale e orizzontale. La bozza rimane quando chiudi il pannello; la lettura dei messaggi precedenti non viene interrotta dai nuovi arrivi.
- Voce facoltativa con microfono inizialmente spento, volume separato e comandi nella notifica. Nessuna videocamera, registrazione o condivisione del video: ciascuno mantiene il proprio streaming.
- Link HTTPS apribili con Nyanime, con codice di otto cifre e download dell’app come alternative. QR e inviti precedenti rimangono compatibili.
- Un solo accesso alla stanza nel player apre direttamente la conversazione, con microfono e opzioni nella testata. Partecipanti e inviti si raggiungono dalla stessa riga; bozza e posizione della chat restano disponibili alla riapertura. Il pannello lascia più spazio al video e non lo oscura. I messaggi sopra il video sono facoltativi e inizialmente disattivati.

### Correzioni

- Corretto il crash entrando in voce o attivando il microfono nelle build ottimizzate; le classi JNI sono conservate e controllate nell’APK finale.
- I frame silenziosi della voce rispettano il tempo reale anche senza acquisire il microfono, evitando invii incontrollati e arresti nel motore audio.
- Attivare il microfono non imposta più la modalità telefonata: video e voce conservano l'uscita multimediale scelta in Android, senza passare all'auricolare superiore.
- Corretto il doppio riquadro della stanza nella Home dopo il ripristino Android; un solo contenitore gestisce stanza e Cast.
- Un nuovo invito o un errore del collegamento resta visibile anche quando la stanza è in modalità lettura.
- In orizzontale la chat resta utilizzabile con la tastiera aperta, senza sottrarre due volte lo spazio dei comandi.
- Corretti backup e sincronizzazione Telegram che potevano restare in attesa con archivi grandi: le risposte non vengono più perse nel trasporto tra processi Android.
- In Continuità il buffering resta locale, anche per l’host. «Aspetta tutti» rimane una scelta esplicita; timer e pause locali di sicurezza non vengono annullati dai comandi remoti.
- La timeline comune non segue più gli arresti del decoder dell’host. Durate ancora sconosciute o instabili non vengono presentate subito come edizioni diverse.
- Invii offline con tentativi limitati, conferme e riprova senza duplicati. Espulsioni e nuove sessioni vocali aggiornano destinatari e chiavi, ignorando messaggi e callback ormai superati.
- Corretto il salvataggio Android della navigazione nelle impostazioni del player, evitando chiusure durante il ripristino della schermata.

### Prestazioni

- Correzioni graduali dei piccoli scarti e riallineamenti locali con attesa minima fra i seek, per ridurre le interruzioni della visione.
- Preparazione iniziale parallela e limitata nel tempo, senza moltiplicare l’attesa per il numero di partecipanti.
- Motore vocale caricato soltanto entrando in voce; i collegamenti sani vengono mantenuti mentre si recupera quello guasto. Chat limitata a 200 messaggi, esclusa dai backup e cancellata all’uscita.

## 15 ottobre 2026 — 0.45.0.1: Estensioni installate o importate

### Novità

- Uno stesso APK firmato può essere installato in Android oppure importato nella memoria privata di Nyanime. Anime, manga, notizie, TV, guide e addon usano la gestione comune; gli addon con componenti Android dichiarati richiedono l’installazione.
- Gli APK si possono importare manualmente senza catalogo. È possibile associare esplicitamente un’origine compatibile per gli aggiornamenti; senza associazione restano manuali.
- Gli importati possono aggiornarsi automaticamente senza aprire l’installer Android, con controlli ogni sei ore e una preferenza per ciascuna estensione.

### Correzioni

- Le verifiche dei pacchetti Android condividono una scansione leggera: gli errori temporanei del sistema mantengono i dati precedenti e vengono recuperati senza chiudere l’app.
- Disinstallare la copia Android conserva quella importata; gli aggiornamenti non eliminano preferenze e stato delle estensioni.
- Reimportare un pacchetto non ancora autorizzato richiede comunque il consenso, anche se i byte sono già presenti.
- Il primo consenso viene salvato prima di aggiornare l’inventario: l’estensione appena importata è disponibile subito, anche per la TV.

### Sicurezza

- Fiducia individuale nella firma e scelta facoltativa di considerare attendibili le estensioni di un catalogo, inizialmente disattivata e revocabile. Aggiungere un catalogo non installa o autorizza automaticamente tutte le sue estensioni.
- Eliminati le autorità dei cataloghi e i passaggi che imponevano sostituzioni o disinstallazioni. Un editore diverso non può sostituire silenziosamente quello approvato.
- Prima dell’attivazione vengono verificati firma, versione, API e identità. Aggiornamenti interrotti o incompatibili conservano la versione precedente; lettura, riproduzione, Cast e addon attivi rinviano l’attivazione.

### Interfaccia

- Installa e Importa sono disponibili nella stessa scheda, con modalità attiva, firma, avanzamento e risultato conservati anche cambiando schermata.
- Nuovi controlli per la fiducia nei cataloghi e per l’origine degli aggiornamenti. Le estensioni già disabilitate mantengono il proprio stato.

### Compatibilità

- Mantenuti lettura, importazione e ripristino dei vecchi file `.rin`; nessuna precedenza obbligatoria rispetto agli APK.
- I backup conservano anche la modalità privata, le preferenze degli aggiornamenti e l’identità usata per i dati degli addon. Le estensioni nuove ricevute da backup o sync attendono l’approvazione locale prima di eseguire codice.
- Contratti di base e capacità aggiuntive separati, con template APK, profili SDK, controlli dell’ABI e documentazione in inglese. Nessuna fonte o catalogo preconfigurato nell’app.

## 14 ottobre 2026 — 0.44.1.1: Traduzioni e Cloud

### Correzioni

- I singoli testi mancanti o vuoti nelle traduzioni usano l’inglese, compresi i plurali e le voci degli elenchi, conservando le traduzioni disponibili.
- La riconnessione a Telegram recupera anche lo stato di connessione già raggiunto dal servizio. Un controllo dell’account interrotto può ripartire senza disconnettere l’utente o cancellare gli invii locali.
- Gli allegati cifrati del sync vengono riconosciuti anche quando Telegram restituisce il file originale dell’invio. Il file viene conservato fino al salvataggio della copia ricevuta, evitando che un invio interrotto blocchi quelli successivi.
- Le pause richieste da Telegram verificano il tempo d’attesa aggiornato prima di riprovare, senza ripetere all’infinito quello di una vecchia ricevuta.
- Cloud segue la lingua e le impostazioni dell’app anche quando viene aperto in una schermata separata.
- Il sync riconosce i RIN locali senza catalogo e tutti i tipi di pacchetto supportati. Un pacchetto mancante resta in attesa dell’importazione locale, senza richiedere un catalogo.
- La cronologia viene sincronizzata insieme ai dati dell’episodio o del capitolo. Le vecchie voci incomplete recuperano soltanto la voce corrispondente dalla loro estensione, con richieste limitate e senza inventare progressi o associazioni.
- Recuperati una sola volta anche i metadati mancanti delle vecchie voci di cronologia sul dispositivo che li conserva, senza ripubblicare l’intera libreria.
- La Home senza estensioni non mostra categorie Anime e Manga vuote né carica cataloghi remoti: offre l’accesso allo Store. Le estensioni senza Home dedicata mantengono la visualizzazione nativa.

### Interfaccia

- Il pulsante di sincronizzazione mantiene posizione e aspetto durante il controllo, senza spostare il testo o oscurare brevemente le altre preferenze.
- Stato del sync più leggibile, con un titolo breve, dettagli dell’ultimo controllo e invii in attesa. I controlli e le preferenze non si ridimensionano a ogni aggiornamento di rete.
- Ridotti gli aggiornamenti grafici ripetuti e le scansioni duplicate dei dati durante la sincronizzazione.

### Distribuzione

- Le note degli aggiornamenti vengono controllate prima della pubblicazione per evitare caratteri corrotti; i checksum usano lo stesso formato su Windows e Linux.
- La pubblicazione manuale passa dal publisher GitHub per mantenere release e allegati attribuiti al bot, conservando i byte verificati degli APK.

## 14 ottobre 2026 — 0.44.0.0: Lettura e archivi più affidabili

### Correzioni

- Le richieste simultanee allo stesso dominio condividono la verifica nel browser, compresi i fallimenti. La chiusura del processo WebView non deve più trascinare con sé l’app.
- Corrette le date iniziali ricavate dalla cronologia dei tracker: il cambio di fuso non sposta più il giorno registrato. Coordinati i rinnovi dell’accesso e la disconnessione dell’account.
- I filtri con selezioni vuote o non valide si possono aprire e correggere senza arrestare la schermata.
- Corrette la gestione delle pagine divise tra capitoli, le aree da ridisegnare nello scorrimento continuo e i tocchi ritardati dopo la chiusura del lettore.
- L’indice dei download viene salvato in modo atomico e ricostruito in caso di cache danneggiata; una cartella temporaneamente inaccessibile non cancella l’ultimo indice valido.
- L’annullamento delle transazioni libera correttamente il database e annulla le scritture incomplete, evitando che un lavoro interrotto blocchi le operazioni successive.
- Il ripristino della cronologia distingue il titolo proprietario: percorsi uguali in opere o fonti diverse non causano più errori né associazioni al capitolo o episodio sbagliato.

### Prestazioni

- Caricamento delle pagine in parallelo con un budget condiviso tra capitolo corrente e successivo, precedenza alle pagine visibili e cancellazione dei trasferimenti superati.
- Gli accodamenti multipli leggono una sola volta la cartella del titolo. I ripristini grandi procedono in blocchi: gli elementi non validi sono isolati e segnalati, conservando progressi e dati aggiuntivi.
- Ridotti i ricalcoli dei filtri di tracking e le scansioni della cronologia per la libreria, mantenendo categorie e conteggi delle stagioni.
- Limitate le letture simultanee e serializzate le transazioni separatamente nei due database; i dispositivi con poca memoria usano un budget di lettura più piccolo.
- I controlli programmati riconoscono periodicità settimanali, mensili e uscite a gruppi, gestendo ritardi e pause senza lasciare i titoli esclusi per sempre. Le previsioni non sostituiscono le date verificate di agenda e notifiche.

### Interfaccia

- Aggiunte due scelte facoltative nel lettore: precaricamento solo su Wi-Fi e navigatore delle pagine verticale. Il trascinamento del controllo pagine mostra l’anteprima del numero e applica un solo salto al rilascio.
- Nelle impostazioni di tracking si possono aggiornare esplicitamente i dati dell’account AniList e scegliere una scala di voto locale, mantenendo la scala dell’account come impostazione iniziale. La ricerca manuale accetta anche `id:123`.

### Compatibilità

- I capitoli locali leggono numero, titolo, traduttore e date complete da ComicInfo, conservando i metadati precedenti se la data non è valida. Mantenuta la compatibilità delle API, delle preferenze e dei backup.
- Pubblicato un template separato per creare estensioni generiche, con esempi compilabili e un'unica guida completa in inglese: API con esempi, categorie Home, filtri, compilazione, firma, installazione, verifica e risoluzione dei problemi.

### Distribuzione

- Nella schermata Info, aggiunta una scheda sul modello bring your own content e sul rispetto del copyright, con dichiarazione contro la pirateria e collegamento alla posizione del progetto. Testi in italiano e inglese, traducibili tramite le risorse dell'app.
- Esplicitato il modello bring your own content e il rispetto del copyright, con una dichiarazione contro la pirateria e materiali demo originali. Aggiornate le istruzioni sui consensi per i RIN locali.

## 14 ottobre 2026 — 0.43.0.3: Rimozione del social dormiente

### Prestazioni

- Eliminati dal progetto profili, amicizie, bacheche, feed, chat social, presenza, Blossom e il precedente sync personale su Nostr, insieme alle schermate, ai test e alle dipendenze esclusive non più utilizzati.
- Player, lettore e Home non richiamano più il vecchio sistema. Le stanze video e manga e il sync Telegram continuano a usare i propri componenti indipendenti.

### Compatibilità

- L’aggiornamento rimuove soltanto archivi, chiavi, bozze e tabelle delle funzioni eliminate. Libreria, progressi, download, backup e dati Cloud sono preservati; una pulizia interrotta viene ritentata al successivo avvio.

## 14 ottobre 2026 — 0.43.0.2: RIN manuali senza catalogo

### Correzioni

- I file RIN si possono caricare direttamente anche senza cataloghi. La conferma verifica firma e compatibilità e autorizza soltanto l’estensione scelta; aggiornamenti manuali protetti contro cambi di editore, identità e downgrade.
- Un catalogo rimosso non impedisce più di aggiornare manualmente una propria estensione già installata.
- Le traduzioni descrivono in modo uniforme la gestione generica delle estensioni.

## 14 ottobre 2026 — 0.43.0.1: Riallineamento delle versioni installate

### Distribuzione

- Nuova build consigliata con numerazione e codice Android superiori alla 0.43.0.0 installata per le prove. Permette di ricevere di nuovo gli aggiornamenti OTA; nessuna nuova funzionalità o modifica al comportamento dell’app.

## 14 ottobre 2026 — 0.42.1.1: Riallineamento degli aggiornamenti

### Distribuzione

- Nuova build consigliata della versione già pubblicata per riallineare gli aggiornamenti OTA. Numerazione e codice Android incrementati; nessuna nuova funzionalità o modifica al comportamento dell’app.

## 13 ottobre 2026 — 0.42.1.0: Aggiornamenti sempre gestiti nell’app

### Interfaccia

- Gli aggiornamenti vengono sempre scaricati e installati dall’app, con avanzamento, verifica e conferma Android. Rimossi i due interruttori nelle Info e nelle Impostazioni aggiornamenti.

### Compatibilità

- La vecchia scelta disattivata non blocca più la procedura né il promemoria «Installa»: il comportamento è uniforme anche dopo aggiornamenti e ripristini di backup.

## 13 ottobre 2026 — 0.42.0.4: Collegamenti Nyanime nelle Info

### Interfaccia

- Riattivati i pulsanti Sito e GitHub nelle Info: aprono il sito e il repository ufficiali di Nyanime. Discord rimane non disponibile finché non sarà aperto un server ufficiale.

## 13 ottobre 2026 — 0.42.0.3: Aggiornamento senza chiusure inattese

### Correzioni

- La schermata delle novità resta aperta dopo «Aggiorna» anche con l’installazione automatica disattivata: mostra avanzamento, verifica ed eventuali errori, poi permette di installare il file scaricato.
- Nelle Info resta una sola voce «Cronologia aggiornamenti», con tutte le versioni; rimossa la voce duplicata «Le novità».

## 13 ottobre 2026 — 0.42.0.2: Sorgenti nativi e distribuzione verificabile

### Distribuzione

- Player nativo ricompilato da revisioni fissate per tutte le architetture, conservando bridge Android, sottotitoli, filtri, codec e patch di riproduzione.
- Ogni release include i sorgenti dell’app e del player, le dipendenze copyleft, le configurazioni e le istruzioni per compilare una propria versione. Gli archivi hanno hash verificabili.
- La versione ponte per il trasferimento dalla vecchia firma resta disponibile con gli APK e i link originali.

### Correzioni

- Completati i testi e i crediti dei componenti nativi, dei codec immagini e delle dipendenze dei torrent nella schermata delle licenze.

### Verifiche

- La pubblicazione confronta le librerie native degli APK con l’inventario verificato e controlla i materiali allegati; una libreria aggiunta o modificata senza aggiornare la provenienza blocca la pubblicazione.

## 12 ottobre 2026 — 0.42.0.1: Licenze, crediti e sorgenti

### Correzioni

- Le licenze delle librerie mostrano tutti i testi dichiarati, anche quando un componente usa più licenze; completati i metadati mancanti senza cambiare le dipendenze.
- I crediti del player identificano la versione binaria effettivamente inclusa, distinta dal nome della dipendenza Gradle.

### Distribuzione

- Codice dell'app, istruzioni di compilazione e patch pubblici, con un archivio dei sorgenti e hash per ogni nuova release.
- Copyright Nyanime per i contributi originali, Apache 2.0 conservata e attribuzioni upstream mantenute. Le licenze dei componenti nativi restano distinte e sono consultabili nell'app.
- Il canale Consigliati propone la nuova versione; i client con la vecchia firma continuano a trovare il ponte di migrazione, conservato con APK e link originali.

### Verifiche

- La pubblicazione controlla i testi delle licenze, l'integrità dei sorgenti e la corrispondenza tra archivio e snapshot pubblico; chiavi di firma e dati locali rimangono esclusi.

## 12 ottobre 2026 — 0.42.0.0: Aggiornamenti più leggibili

### Novità

- Schermata aggiornamenti con riepilogo delle versioni, cronologia espandibile e schede con categorie, icone e colori leggibili anche per compatibilità, interfaccia, sicurezza, distribuzione, verifiche e limitazioni.
- I nuovi changelog dichiarano le categorie delle modifiche; lo storico incluso nell’app è stato riclassificato e le vecchie note online non strutturate sono riconoscibili come «Note originali», senza classificazioni automatiche.
- La cronologia delle versioni successive a quella installata viene recuperata separatamente, senza ritardare download e installazione.
- Impostazioni → Info apre lo storico completo dentro Nyanime, disponibile anche senza rete; le categorie delle note storiche sono state revisionate conservando tutti i testi originali.

### Correzioni

- Il recupero del sync dopo la rimozione del canale Telegram può verificare le vecchie autorizzazioni nell’archivio sostitutivo quando mancano le copie locali, conservando identità, firme e modifiche in attesa.

### Prestazioni

- Espansioni coordinate, scorrimento unico e comandi sempre raggiungibili, con supporto ai temi, ai testi grandi e alle animazioni ridotte.

### Distribuzione

- Da questa versione i nuovi APK usano soltanto il pacchetto `it.owouwuiwi.nyanime` e il certificato di produzione. La 0.41.0.6 resta disponibile come ponte per trasferire i dati dalla vecchia app; i suoi APK non vengono sostituiti.

## 12 ottobre 2026 — 0.41.0.7: Stato della sincronizzazione più chiaro

### Novità

- Il Cloud distingue l’ultimo controllo riuscito dall’ultimo invio dei dati: un archivio già aggiornato non sembra più fermo a un orario precedente.

### Prestazioni

- I controlli periodici senza nuove modifiche mantengono stabile lo stato; i controlli manuali e le operazioni in attesa restano visibili.

### Correzioni

- Gli avvisi indicano il prossimo tentativo automatico e non dichiarano aggiornato il dispositivo quando Telegram non è connesso.
- I timeout di connessione, controllo, invio e ricezione diventano errori recuperabili: non interrompono il ciclo automatico e conservano gli invii già accodati.

## 12 ottobre 2026 — 0.41.0.6: Trasferimento e sync più affidabili

### Compatibilità

- L’aggiornamento ordinario conserva il vecchio pacchetto e la vecchia firma; il passaggio alla nuova identità rimane facoltativo.

### Novità

- Il trasferimento usa una copia separata: la versione precedente torna utilizzabile appena la preparazione termina.
- Aggiunti annullamento, ripristino dei dati precedenti in caso di errore e codici diagnostici leggibili.
- Il trasferimento può essere ripetuto dalle impostazioni anche dopo aver configurato la nuova app senza dati.
- Dopo la conferma, l’accesso alla cartella condivisa viene trasferito quando Android lo permette; altrimenti la procedura guida alla selezione della stessa cartella. I permessi vengono verificati prima di preparare i dati, senza bloccare la vecchia app.
- Dopo l’aggiornamento, la vecchia app propone una sola volta il passaggio alla nuova Nyanime. «Non ora» mantiene la versione attuale utilizzabile; l’azione resta disponibile nelle impostazioni.
- La procedura distingue preparazione, copia e verifica; il completamento rimane visibile anche ruotando lo schermo. La percentuale indica soltanto la copia, senza sembrare bloccata durante i controlli finali.
- Dopo un trasferimento verificato, il primo ingresso nella nuova app propone di disinstallare la precedente con conferma di Android. «Non ora» rimanda la scelta; il comando rimane nelle impostazioni.

### Correzioni

- Se il canale privato Telegram del sync viene eliminato, l’app ricrea l’archivio e ripubblica i dati locali senza cambiare l’identità dei dispositivi. Gli errori del canale non vengono più mostrati come assenza di connessione.
- La sostituzione dei database comprende i relativi file temporanei: corretto il trasferimento verso una nuova app già aperta e configurata, conservando il ripristino completo in caso di errore.
- Il passaggio alla nuova app usa soltanto APK effettivamente pubblicati e compatibili: una release in preparazione non apre più un collegamento inesistente.

## 12 ottobre 2026 — 0.41.0.2: Ripristino della versione originale

### Sicurezza

- Conservata la rimozione della fiducia automatica delle estensioni: l’autorizzazione resta esplicita.

### Correzioni

- Ritirata dall’OTA la versione con la nuova identità. Nessuna app viene disinstallata automaticamente e nessun dato personale viene cancellato.
- Ripristinati il pacchetto e la firma precedenti: questo aggiornamento si installa sulla Nyanime originale, mantenendone dati e impostazioni.
- Rimossi il trasferimento fra le due app, la relativa guida e il blocco di avvio: la versione originale torna direttamente alla Home, anche dopo un tentativo interrotto.

## 11 ottobre 2026 — 0.40.2.2: Store, menu delle schede e preferenze Smart

### Novità

- Lo Store permette di caricare e aggiornare file RIN locali verificati, conservando i dati dell'estensione e rifiutando pacchetti incompatibili o con provenienza modificata.
- Anime4K Smart non parte automaticamente nelle nuove installazioni. Gli aggiornamenti mantengono la preferenza esistente, compreso il precedente comportamento predefinito.

### Correzioni

- Ripristinate le immagini precedenti della Home e delle schede: rimosso il sistema sperimentale di copertine, fondali e loghi aggiuntivi, con pulizia delle sue preferenze e cache. Le copertine personalizzate restano conservate.
- Carosello della Home centrato anche sui tablet, con titolo e segnaposto allineati alla copertina senza spostare i controlli di navigazione.
- Menu «Altre azioni» con scorrimento interno stabile, altezza limitata e chiusura esplicita; eliminata l'oscillazione della finestra arrivando ai bordi della lista.
- Corretti i comandi di download multiplo che in italiano mostravano soltanto l'icona, senza testo.
- Ripristinata la possibilità di incollare il collegamento di un catalogo di estensioni.

## 11 ottobre 2026 — 0.40.1.7: Schede in armonia con la Home

### Interfaccia

- Conservati disposizione dei comandi, posizioni di scorrimento, gestione di episodi e capitoli e transizioni delle copertine.

### Novità

- Schede anime e manga con la stessa palette, fondale morbido e testata trasparente della Home, nei temi chiaro e scuro.
- Superfici evidenziate, sfumature sulle copertine e pulsante di ripresa coordinati con lo stile dell'app; rimossa la palette separata delle schede.

### Correzioni

- Testata delle schede stabile dopo finestre e cambi di sezione; fascia Episodi/Capitoli e Dettagli integrata nel fondale anche quando si fissa sopra la lista, senza lo stacco nero.

## 11 ottobre 2026 — 0.40.1.4: Stanze e lettura condivisa (in verifica)

### Verifiche

- La verifica completa tra due Android rimane richiesta prima della pubblicazione di questa revisione.

### Novità

- Il creatore sceglie la lettura quando apre un capitolo: gli ospiti aggiornati lo raggiungono al proprio punto, senza sincronizzare le pagine. Un nuovo capitolo dello stesso manga propone «Raggiungi» senza interrompere la lettura.
- Permessi per scegliere il manga, proposte dei partecipanti e modalità «Leggi per conto tuo». La stanza distingue capitolo ricevuto, apertura, lettura pronta ed errori recuperabili.
- Controllo dei partecipanti compatto ed espandibile nel lettore; barra e notifica della stanza rappresentano correttamente manga, video o TV.
- «Usa la mia copia» richiede una scelta esplicita del manga e del capitolo. Tra edizioni diverse, salti precisi e schizzi richiedono pagine originali verificate; immagini e download non vengono modificati.

### Correzioni

- Richieste con scadenza, risposte obsolete ignorate e recupero dei contatori dopo il riavvio. Conservata la compatibilità delle annotazioni e dei client precedenti.
- Corrette la prima apertura dei capitoli già in cache e la conservazione della scelta condivisa durante il recupero della stanza.
- Riaprire la lettura dalla barra della stanza conclude correttamente il caricamento anche per il creatore.
- «Torna al mio punto» compare soltanto nella stanza che ha salvato quel punto. Chiusura, uscita e rinuncia al rientro eliminano il segnalibro; i vecchi segnalibri senza stanza vengono rimossi senza toccare i progressi della libreria.

## 11 ottobre 2026 — 0.40.0.1: Guida di benvenuto dalle Impostazioni (consigliata)

### Correzioni

- «Entra in Nyanime» chiude correttamente la guida aperta da Impostazioni → Avanzate. Anche Indietro dal primo passaggio torna alle Impostazioni.
- La consultazione della guida parte dall’inizio senza modificare il punto di ripresa del primo avvio; le nuove installazioni conservano il percorso guidato e la ripresa dopo un’interruzione.

## 11 ottobre 2026 — 0.40.0.0: Un benvenuto più semplice (consigliata)

### Novità

- Guida di prima installazione riunita in un percorso: benvenuto, tema, permessi facoltativi, canale degli aggiornamenti e ripristino.
- Cartella Nyanime creata e verificata automaticamente nello spazio dell’app, senza chiedere dove salvarla né un accesso generale ai file. Una cartella già configurata viene conservata; la scelta di una cartella personale resta nelle Impostazioni.
- Permessi presentati in schede leggibili, con spiegazioni e stato aggiornato al ritorno dalle impostazioni Android. È possibile continuare senza concederli.
- Ripristino locale o da Nyanime Cloud direttamente dalla guida, con anteprima, conferma, avanzamento e risultato; nessun accesso al cloud viene avviato automaticamente.
- La guida riprende dal passaggio raggiunto se interrotta, rispetta temi e animazioni ridotte ed è tradotta in italiano e inglese. Chi ha già completato il benvenuto non deve rifarlo aggiornando l’app.

## 11 ottobre 2026 — 0.39.3.0: Ripartenza più semplice e schede stabili (consigliata)

### Novità

- Schermata dopo un crash rinnovata: «Riapri Nyanime» in primo piano, rapporto condivisibile e dettagli dell’errore espandibili, con temi chiaro/scuro e testi italiano/inglese.
- Testata Home riordinata: indicatore delle novità a sinistra del pulsante delle stanze, con posizioni fisse anche quando non ci sono nuovi contenuti.
- Rimosso il pulsante grande del backup da Altro; creazione e ripristino restano disponibili nelle Impostazioni.

### Correzioni

- Le schede anime e manga mantengono la lista durante il passaggio dal caricamento ai contenuti: sottotitolo, avanzamento e pulsante di ripresa conservano i loro spazi e appaiono con una dissolvenza.

### Prestazioni

- Preparazione del rapporto fuori dall’interfaccia, con avanzamento, tentativi ripetuti gestiti e possibilità di riprovare in caso di errore. Nessun rapporto viene inviato automaticamente.

## 11 ottobre 2026 — 0.39.2.1: Home, fluidità e PiP (consigliata)

### Interfaccia

- Conservati grafica, sfocatura, qualità delle transizioni e rispetto delle animazioni ridotte.

### Novità

- Testata più morbida: le copertine scorrono sotto le categorie, con sfocatura graduale e senza lo stacco nero.

### Correzioni

- Il gesto di chiusura del mini-player disattiva subito il PiP automatico, prima dell'animazione di uscita: tornare immediatamente alla Home del telefono non riduce più la Home di Nyanime nella finestrella. Una chiusura annullata o rifiutata dalla stanza ripristina il comportamento del player.
- Chiudere il player revoca subito il PiP automatico: uscire dall'app senza un video non riduce più la Home in una finestra.
- Il PiP segue la schermata che ospita il video, senza restare attivo su quella precedente; l'ingresso richiede una sessione video locale disponibile.
- Tornare dalla Libreria e aggiornare la Home mantiene le copertine già caricate e la selezione del carosello; le risposte parziali non fanno sparire e ricomparire i titoli.
- Logo, categorie e comandi restano nitidi e fermi; l'effetto è presente dall'apertura e non si accende durante lo scorrimento.
- Ombre e sfondi accompagnano la copertina nel ritorno alla Home, senza zone scoperte durante il movimento.
- Copertina e titolo restano continui durante l'apertura della scheda, anche quando il caricamento termina a metà animazione.
- Spaziatura adattata alla testata reale, anche con categorie su più righe e caratteri grandi; mantenuti temi chiaro/scuro e caricamenti discreti.

### Prestazioni

- Copertine già in memoria mostrate subito, senza ripetere l'animazione a ogni scorrimento; un'unica dissolvenza per le nuove immagini.
- Aggiornamenti delle righe della Home indipendenti, conservando posizione e immagini durante il caricamento delle altre sezioni.
- Il refresh riprova le immagini fallite senza riavviare quelle sane; il carosello sospende la rotazione durante l'aggiornamento e quando si lascia la Home.
- Preparazione della libreria unificata fuori dall'interfaccia, mantenendo la lista visibile durante ricerca e aggiornamenti.
- Controlli del player aggiornati soltanto dove serve il minutaggio, senza ridisegnare gli strumenti durante la riproduzione.

## 10 ottobre 2026 — 0.39.1.1: Passaggio rapido tra anime e manga (consigliata)

### Novità

- Il collegamento identifica l’opera e cerca automaticamente una copia nelle fonti abilitate, anche fuori dalla libreria; gli ID discordanti impediscono l’apertura automatica.
- Stato di ricerca discreto, scelta delle copie e messaggi in italiano e inglese; nessun capitolo o episodio viene stimato.

### Prestazioni

- Le copie verificate compaiono senza aspettare i riferimenti sui capitoli. Un tocco durante la ricerca apre la destinazione appena pronta, senza dover ripetere il comando.
- Cache privata delle copie già riconosciute, mantenuta anche al riavvio, e richieste limitate e annullabili in entrambe le direzioni.

## 10 ottobre 2026 — 0.39.1.0: Aggiornamenti più semplici (consigliata)

### Novità

- Nuova pagina degli aggiornamenti: tutte le novità subito leggibili, versione installata a confronto e un solo pulsante sempre raggiungibile.
- «Aggiorna» mostra download e verifica, poi apre l’installazione protetta con la conferma di Android; permessi e annullamento restano gestibili senza aperture ripetute.
- Il download continua uscendo dalla pagina. Quando è pronto, l’avviso nell’app permette di installarlo anche dopo un riavvio; file rimossi o superati possono essere scaricati di nuovo.

## 10 ottobre 2026 — 0.39.0.3: Vignette nel verso giusto (consigliata)

### Compatibilità

- Conservate le sequenze riordinate a mano e la compatibilità dei backup precedenti; la ripresa identifica il riquadro attraverso la sua posizione nella pagina.

### Novità

- Ordine delle vignette indipendente dalla modalità di scorrimento: da destra a sinistra per i manga, con scelta per titolo fra destra a sinistra, sinistra a destra e dall’alto in basso.

### Correzioni

- Lo scorrimento verticale continua ad avanzare verso l’alto; cambiare ordine conserva la vignetta inquadrata e lo zoom, senza riavviare il riconoscimento.
- Dopo la rotazione il lettore ricrea la vista nella nuova schermata e ripristina la vignetta, senza riutilizzare componenti già distrutti.

## 10 ottobre 2026 — 0.39.0.2: Pubblicazione OTA affidabile (consigliata)

### Distribuzione

- Conservato il pacchetto temporaneo in caso di errore, con eliminazione dopo la conferma della release pubblica e dei riferimenti al codice privato.

### Correzioni

- Pubblicazione OTA indipendente dalla quota degli artefatti Actions: trasferimento privato verificato, senza esporre il codice o cambiare gli APK già pubblicati.

## 10 ottobre 2026 — 0.39.0.1: Video dentro Nyanime (consigliata)

### Novità

- Pagina di visione con video in alto, titolo, prossimo episodio, ricerca e filtri degli episodi, contenuti correlati e consigliati disponibili.
- Avvio diretto nella pagina di visione o a schermo intero, selezionabile dalle impostazioni del player; un lettore manga in background non riduce il video appena aperto.
- Mini player spostabile e ridimensionabile per continuare a navigare nell'app e leggere un manga durante la riproduzione.
- Mini player senza comandi sovrapposti: un tocco riapre il video, un trascinamento deciso oltre il bordo inferiore lo chiude con una breve uscita animata.
- Espansione e riduzione tramite gesti e pulsante Indietro, conservando la stessa sessione video, il punto di riproduzione e gli strumenti del player a schermo intero.
- Trascinamento ancorato al dito: dalla pagina il video si riduce verso il bordo inferiore; dallo schermo intero scende senza cambiare dimensioni fino al rilascio, poi torna alla pagina verticale.
- Linea sottile nella pagina di visione, con caricamento nella stessa linea e pallino che cresce durante il seek; a schermo intero torna la barra originale con segmenti, tempi e colori del tema.
- Il trascinamento orizzontale del video per avanzare o tornare indietro funziona soltanto a schermo intero.
- Play e pausa condividono una trasformazione continua tra pagina, schermo intero e stanze; feedback elastico dei pulsanti e del pallino, rispettando le animazioni ridotte.
- Barra della pagina con gradiente arancione-rosso e colore distinto per il video già caricato; avanzamento ordinario interpolato senza ritardare il trascinamento o animare uno spostamento richiesto.
- Audio in background abilitato inizialmente, disattivabile nelle impostazioni del player, con notifica e controlli di sistema; PiP fuori dall'app quando abilitato.
- Dirette interne nella stessa pagina di visione, con informazioni e scelta dei canali disponibili.
- Nelle stanze aggiornate, chiudere il video locale mantiene la partecipazione come osservatore, senza fermare gli altri. Rientro esplicito dal pannello della stanza; limitazioni segnalate con partecipanti su versioni precedenti.

### Correzioni

- Superficie video stabile durante le animazioni, con un solo tempo di movimento e riposizionamento coerente quando cambia l'orientamento.
- Il punto scelto resta stabile al rilascio della barra fino alla conferma del player, senza tornare al progresso precedente; minutaggio e pulsante schermo intero avvicinati alla linea, con aree di tocco distinte.
- Il PiP mantiene le dimensioni del buffer nativo: cambia la finestra visibile senza esporre un vecchio fotogramma ritagliato durante il ridimensionamento.
- Il ritorno in verticale conserva il fotogramma a schermo intero fino alla nuova disposizione della finestra, evitando un ridimensionamento intermedio prima della rotazione.
- Durante la rotazione il fotogramma viene disegnato nella finestra prima del cambio di orientamento; la superficie video rimane collegata ma nascosta sotto di esso, senza esporre trasformazioni separate.
- Il ritorno da schermo intero viene animato dal player nella nuova finestra verticale, evitando la rotazione del vecchio fotogramma da parte del sistema; barre di sistema ripristinate dopo il cambio.
- Scheda di fine episodio compatibile con lo scorrimento della pagina di visione, senza arresti durante il conto alla rovescia.
- Corretto il salvataggio ripetuto della cronologia con Cloud attivo: la coda conserva l'ultima revisione senza bloccare il progresso locale.
- Proprietà della riproduzione separata dalle schermate, con protezioni per superfici sostituite, callback tardivi e chiusura delle risorse native.
- Adattamento delle pagine affiancate alle dimensioni effettivamente decodificate, anche dopo il ritaglio dei bordi.

### Prestazioni

- La nuova selezione viene preparata mentre il video attuale continua; selezioni superate vengono annullate e un errore di preparazione conserva il contenuto precedente.

## 9 ottobre 2026 — 0.38.0.0: Manga a due pagine (consigliata)

### Novità

- Opzione Spread nella scelta della modalità di lettura: due pagine affiancate e unite al centro, in verticale e orizzontale, con lettura paginata o scorrimento continuo a coppie.
- Abbinamento regolabile per capitolo, con anteprima, pagine singole iniziali e un valore predefinito per tutto il manga.
- Panoramiche, pagine lunghe e ultima pagina dispari restano singole; rispettata la direzione di lettura.
- Zoom condiviso, navigazione e indicazione delle pagine originali, senza unire le immagini o cambiare i riferimenti di condivisione e stanze.

### Correzioni

- Una pagina ancora in caricamento o non visibile non completa il capitolo. Configurazione inclusa nei backup completi, con compatibilità dei backup precedenti.
- Conservati lettura singola, lettura guidata e relative preferenze; corretti i controlli di separazione e inversione delle pagine nel lettore paginato.

## 9 ottobre 2026 — 0.36.0.2: Lettura guidata stabile e TV nello Store (anteprima)

### Correzioni

- Pagina intera e vignetta adattata allo schermo rimangono ferme; il trascinamento esplora l'immagine solo dopo un ingrandimento manuale.
- Pinch e doppio tap permettono di ingrandire; tornando allo zoom iniziale, l'immagine recupera il focus senza mantenere spostamenti accidentali.
- Il focus segue subito lo spazio disponibile dopo rotazione o comparsa e scomparsa delle barre, senza assestarsi al primo trascinamento.
- Gli swipe cambiano vignetta senza trascinare prima la pagina e senza interrompere la transizione appena iniziata.
- Il punto di lettura distingue il focus automatico dallo zoom personale, anche dopo rotazione o ripristino; conservati attivazione per titolo, correzioni e backup precedenti.
- Le estensioni TV multilingua sono visibili nello Store anche sui telefoni dove non sono ancora installate, con i filtri italiano e inglese; invariati i filtri linguistici di anime e manga.

### Prestazioni

- La build OTA libera gli intermedi dopo la verifica degli APK e prima della firma, riducendo lo spazio occupato senza eliminare gli APK o i controlli di integrità.

## 9 ottobre 2026 — 0.36.0.1: TV in diretta (consigliata)

### Novità

- Il player distingue problemi temporanei di rete, indirizzi non raggiungibili, accesso rifiutato e formati non supportati.
- Quando una diretta resta indisponibile, il player mostra il motivo e permette di scegliere un altro canale; un catalogo non garantisce che ogni trasmissione sia raggiungibile.
- La diagnostica del player live conserva soltanto il tipo di errore, senza indirizzi video, header o credenziali.
- Home TV facoltativa, alimentata dalle estensioni compatibili: canali, preferiti ordinabili, recenti e programmi in onda.
- Guida a lista o griglia, filtri di paese, lingua e categoria; palinsesti salvati e caricamenti progressivi per i canali aperti.
- Canali ricercabili in Atlante e condivisibili con link Nyanime senza indirizzi di streaming o credenziali.
- Player live dedicato con PiP, audio, sottotitoli e funzioni abilitate soltanto quando supportate; nessun episodio fittizio, tracking o AniSkip sui canali.
- Le stanze esistenti supportano la scelta del canale da parte dell’host; pausa e buffering restano locali. Il ritardo è verificato solo quando sono disponibili riferimenti temporali affidabili.
- Cast live per ricevitori compatibili e telecomando verticale; preferiti e scelte TV inclusi nei backup delle impostazioni.
- Contratto RIN TV separato e generico, con controlli di compatibilità e firma; nessun catalogo o parser di una fonte nell’app.

### Prestazioni

- I collegamenti definitivamente falliti non vengono riprovati in continuazione: si provano le alternative dello stesso canale e un nuovo recupero dei collegamenti dall’estensione.
- Cambio canale e riconnessione conservano il lettore live; il ritorno dal PiP al canale già in riproduzione evita un caricamento aggiuntivo.

## 9 ottobre 2026 — 0.35.0.0: Lettura guidata (anteprima)

### Novità

- Nuova lettura guidata facoltativa, ricordata per titolo, per manga, fumetti e webtoon: focus sulle vignette, zoom libero e ritorno alla pagina intera.
- Navigazione tramite tocchi, scorrimento e tasti configurati; transizioni di posizione e zoom coordinate e rispettose delle animazioni ridotte.
- Riconoscimento offline con modello incluso, elaborazione in un processo isolato e precaricamento della sola pagina successiva.
- Editor delle vignette con modifica dei bordi, aggiunta, rimozione, riordino e ripristino del riconoscimento automatico.
- Correzioni e punto di lettura inclusi nei backup e nella sincronizzazione Cloud; nessuna immagine o cache automatica nei backup.
- Lettura insieme e scarabocchi conservano le coordinate originali; il capitolo si completa raggiungendo l'ultima vignetta dell'ultima pagina.
- Se il riconoscimento non riesce, la pagina intera resta disponibile.

## 9 ottobre 2026 — 0.34.0.0: Guide episodi, Atlante e nuovo logo

### Compatibilità

- Ricerca esatta, filtri, limiti di richieste e protezioni per stagioni ed edizioni rimangono disponibili.

### Novità

- Nuova categoria «Guide» nello Store, con estensioni facoltative aggiornabili separatamente.
- Guida nella scheda anime, badge discreti per filler e misti, associazione e numerazione correggibili.
- Nascondere i filler e saltarli automaticamente sono scelte separate, disattivate inizialmente; il salto può seguire una preferenza globale o specifica del titolo.
- Player, autoplay e Cast condividono la scelta del prossimo episodio; nelle stanze decide l'host. Gli episodi aperti esplicitamente vengono riprodotti e quelli saltati non diventano visti.
- Dati salvati disponibili offline, richieste condivise e annullabili, classificazioni incerte lasciate sconosciute. Preferenze e correzioni sono incluse nei backup.
- Nuova icona e logo della Home e di Altro vettoriali, con fori trasparenti e risorse separate per tema chiaro e scuro. La N ha più margine nell'icona, con sfondo bianco nell'aspetto chiaro e nero in quello scuro. Gli asset precedenti sono conservati per poterli ripristinare.
- Tutti i risultati restano nella stessa griglia: titoli completi e nomi alternativi verificati precedono corrispondenze parziali e risultati meno pertinenti.
- L'ordine dipende dalla pertinenza e dalle fonti configurate, anche quando le risposte arrivano in momenti diversi o vengono caricate altre pagine.
- Con corrispondenze convincenti, i titoli estranei restano accessibili con «Mostra altri titoli»; nessun risultato viene perso. Il titolo principale precede i contenuti collegati con nomi più lunghi.

### Correzioni

- Conservati gli alias già disponibili durante risposte incomplete e aggiornamenti della Home; le alternative di fonte rimangono apribili.

### Prestazioni

- Riordino fuori dal percorso grafico, schede con identità stabile e transizioni brevi che rispettano le animazioni ridotte.

## 8 ottobre 2026 — 0.33.0.1: Tornano le stanze su Nostr (anteprima)

### Compatibilità

- Conservati codici stanza, inviti, lettura insieme e scarabocchi; mantenute le correzioni graduali dei piccoli ritardi e il recupero del buffer dopo una pausa.
- Backup, accesso Telegram e sincronizzazione personale Cloud rimangono disponibili.

### Correzioni

- Ripristinate le schermate precedenti per creare, raggiungere e gestire le stanze video e manga.
- Rimossi chat Telegram, gruppi automatici, chiamate e videocamere dalle stanze.
- Il player torna a schermo intero con i suoi comandi abituali, senza pannelli sociali o modifiche audio legate alle chiamate.

## 8 ottobre 2026 — 0.33.0.0: I tuoi dispositivi, collegati (anteprima)

### Novità

- Sincronizzazione Telegram facoltativa di librerie, categorie, cronologia, progressi video e manga, segnalibri e collegamenti ai tracker.
- Preferenze condivise con la possibilità di mantenere aspetto, player o lettore diversi su ciascun telefono; conversione dei riferimenti locali per categorie, novità e titoli nascosti.
- Associazione tramite QR monouso o codice di recupero, archivio cifrato e gestione dei dispositivi autorizzati.
- Nuove sezioni Sync, Dispositivi e Backup in Nyanime Cloud; copia di sicurezza cifrata prima della prima unione e protezione dei successivi backup Cloud dei dispositivi associati.
- “Continua a guardare/leggere” mostra il dispositivo di provenienza e permette di scegliere un punto conservato; i progressi remoti non spostano la riproduzione o la pagina già aperte.
- Invii persistenti, recupero dopo interruzioni e modifiche offline; download, incognito, sessioni Telegram e impostazioni hardware restano locali.
- Inventario RIN condiviso e supporto alle sole preferenze portabili dichiarate dalle estensioni, con consenso separato per le credenziali.

## 8 ottobre 2026 — 0.32.0.10: Protezione opzionale degli aggiornamenti

### Novità

- Nuova capacità generica per addon di recupero, con autorizzazione esplicita e revocabile.
- Copia completa verificata prima degli aggiornamenti installati nell’app, quando la protezione è abilitata.
- Esportazione e ripristino isolati dall’avvio normale, con verifica di libreria, progressi e impostazioni.
- Nessun processo di recupero o backup aggiuntivo in assenza di un addon autorizzato.

## 8 ottobre 2026 — 0.32.0.9: Stanza compatta e integrata nel player (anteprima)

### Novità

- Una sola barra con partecipanti, Chat, Voce e Video, con comandi secondari nel menu.
- Il video mantiene quasi tutto lo schermo finché la conversazione non è disponibile.
- Anteprime dei messaggi sotto il video, senza sovrapposizioni; videocamere compatte.
- Reazioni direttamente nel campo di scrittura, con selezione compatta; anteprime degli allegati ridotte.
- Campo di scrittura semplificato e spazio della conversazione più pulito.

## 8 ottobre 2026 — 0.32.0.8: Comandi della stanza accessibili dal player (anteprima)

### Novità

- Rimossi i comandi della chat duplicati nel pannello della stanza; avviso di chiusura aggiornato per i gruppi temporanei.

### Correzioni

- Pannello dei comandi della stanza sull’intero schermo, senza restringerlo alla nuova area del video.
- Pulsante Chiudi sempre raggiungibile e tasto Indietro che chiude i pannelli prima di passare in PiP.

## 8 ottobre 2026 — 0.32.0.7: Player condiviso, reazioni e chiusura delle stanze (anteprima)

### Novità

- Video in alto e un solo spazio sottostante per chat, anteprima messaggi, reazioni, voce e videocamera, anche in orizzontale.
- Ogni partecipante può scegliere un episodio e usare Play, pausa e ricerca nella riproduzione; Nostr mantiene un unico ordine dei comandi.
- Gruppi temporanei eliminati alla chiusura della stanza, con coda di cancellazione conservata in caso di assenza di rete.
- Correzioni della sincronizzazione più graduali e ripresa dal buffer disponibile dopo una pausa condivisa.

### Correzioni

- Microfono e videocamera indipendenti: un errore della videocamera non disattiva più la voce.
- Corretto l’avvio dell’uscita audio e rimossa la ricreazione dei dispositivi audio quando cambia soltanto la videocamera.
- Chiusura corretta del pannello chiamata e messaggi di errore distinti per i dispositivi.
- Conferme degli invii recuperate anche dopo un ritardo della connessione; gli invii incerti non vengono duplicati.

## 8 ottobre 2026 — 0.32.0.5: Chat nel player e chiamate nelle stanze (anteprima)

### Compatibilità

- La sincronizzazione della visione continua a usare Nostr; gli errori della chat o della chiamata non comandano la riproduzione.

### Novità

- Chat accanto al video in orizzontale e sotto il video in verticale, con comandi rapidi dedicati e campo di scrittura compatto.
- Anteprime dei messaggi e reazioni con avatar durante la visione, disattivabili dal menu della conversazione.
- “Guarda qui” condivide un minuto del video oppure capitolo e pagina del manga; ogni spostamento richiede un tocco esplicito.
- Menu comune per silenziare, cercare messaggi, vedere i partecipanti e tornare alla sessione.
- Comandi per voce e videocamera, con microfono e video inizialmente spenti; motore separato dal player e videocamera sospesa in background.
- Chat e chiamata accessibili anche dal lettore, mantenendo posizione personale e strumenti per disegnare.

### Correzioni

- Corretto il posizionamento della conversazione quando si apre la tastiera.

## 7 ottobre 2026 — 0.32.0.4: Collegamento della conversazione (anteprima)

### Novità

- Preparazione con scadenza e nuova richiesta esplicita tramite “Riprova”, senza interferire con il video.

### Correzioni

- Corretto il recupero delle conversazioni rimaste incomplete durante la creazione.
- Un aggiornamento precedente della stanza non cancella più il gruppo appena creato da un partecipante.

## 7 ottobre 2026 — 0.32.0.3: Chiusura della stanza dall’hub (anteprima)

### Novità

- Aggiunto il pulsante “Chiudi stanza” per il proprietario e “Esci dalla stanza”
  per gli altri partecipanti direttamente nella scheda della stanza in corso.
- Conferma prima della chiusura o dell’uscita, con indicazione che la conversazione
  e i messaggi Telegram rimangono disponibili.

## 7 ottobre 2026 — 0.32.0.2: Accesso alla chat e stabilità Telegram (anteprima)

### Novità

- Accesso Chat sempre visibile nel player quando si è in una stanza.
- Conversazioni accessibili anche dai comandi della stanza e dalla lettura:
  collegamento dell’account, attivazione e stato della preparazione nello stesso punto.
- Titolo dell’hub semplificato in “Guarda insieme”.

### Correzioni

- Corretto un arresto del servizio Telegram durante rotazioni e cambi di
  configurazione, che poteva interrompere la preparazione della chat.

## 7 ottobre 2026 — 0.32.0.0: Chat Telegram nelle stanze (anteprima)

### Limitazioni

- Questa anteprima riceve messaggi mentre l’app è aperta. Chiamate vocali,
  videocamera e notifiche ad app chiusa non sono ancora disponibili.

### Novità

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

## 7 ottobre 2026 — 0.31.0.7: Continuità degli aggiornamenti

### Correzioni

- Ripetere una pubblicazione o promuovere un'anteprima conserva APK e checksum già distribuiti, senza ripristinare documentazione più vecchia.
- I vecchi updater continuano a trovare la versione più recente anche quando cresce lo storico delle release.

## 7 ottobre 2026 — 0.31.0.5: Accesso Cloud più comodo

### Novità

- Numero Telegram con selettore del paese e del prefisso, ricercabile per nome o codice.
  Italia (+39) è preselezionata: basta inserire il numero senza riscrivere il prefisso.
- Incollare un numero internazionale completo aggiorna il paese senza duplicare
  il prefisso; gestiti gli zeri iniziali e la formattazione dei diversi paesi.

## 7 ottobre 2026 — 0.31.0.4: Ripristino delle animazioni precedenti

### Compatibilità

- Conservati Nyanime Cloud, accesso Telegram, backup automatici e ripristino guidato.
  I componenti di presentazione necessari a Cloud rimangono confinati alle sue schermate.

### Correzioni

- Annullato il rifacimento delle animazioni introdotto con 0.30.0.0:
  Home, Libreria, navigazione, player, lettore e pannelli tornano al comportamento precedente.

## 7 ottobre 2026 — 0.31.0.3: Nyanime Cloud

### Novità

- Accesso Telegram condiviso per le funzioni Nyanime, tramite il motore ufficiale TDLib.
- Accesso con avanzamento immediato ed errori specifici accanto al campo da correggere.
- Schermata Cloud integrata con il tema Nyanime: accesso guidato, ultimo backup
  in primo piano, cronologia per dispositivo e impostazioni in un pannello dedicato.
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

### Correzioni

- Corretto un difetto nel passaggio delle richieste al motore Telegram che poteva
  lasciare l’accesso in attesa senza proseguire.
- Invio dei documenti adeguato all’API TDLib inclusa; gli errori permanenti non
  vengono più rimandati all’infinito come semplici attese di rete.

Accesso, archivio privato, invio confermato ed esportazione verificati su Android.
Le prove e i limiti di verifica sono descritti nella [Guida Cloud](docs/telegram-cloud.md).

## 7 ottobre 2026 — 0.30.0.0: Animazioni coordinate e controlli più fluidi

### Novità

- Tempi e curve comuni per selezioni, finestre, pannelli e cambi di stato,
  con movimenti contenuti e chiusure più rapide.
- Apertura e ritorno dalle copertine coordinati con titoli, azioni e sfondi;
  immagini già visibili conservate durante caricamento ed errori.
- Play e Pausa si trasformano con un morph discreto nel player, nelle stanze
  e nel telecomando Cast, senza ritardare i comandi.
- «Riduci animazioni» disponibile anche in Aspetto e applicato all'interfaccia,
  mantenendo la stessa preferenza del player e rispettando la scala Android.

### Correzioni

- Pannelli e finestre gestiscono il ritorno durante l'apertura e i tocchi rapidi,
  impedendo interazioni con i contenuti in uscita.
- Calendario più stabile durante gli aggiornamenti; selezioni e passaggi tra
  Anime e Manga uniformati anche in Libreria e nelle Novità.

### Prestazioni

- Animazioni decorative sospese quando nascoste o con l'app in background.

## 6 ottobre 2026 — 0.29.0.5: Addon più chiari e integrati

### Compatibilità

- Conservata la compatibilità delle modalità già disponibili con pressione sul logo.

### Novità

- Supporto agli addon RIN: installazione, aggiornamento e disattivazione direttamente
  nello Store, con interfacce e logica mantenute nei pacchetti addon.
- Gli addon possono aggiungere azioni in Altro attraverso un contratto dichiarativo,
  con titolo, icona e spiegazione forniti dall’addon stesso.
- Descrizioni dettagliate disponibili nello Store e nelle impostazioni degli addon,
  con indicazioni chiare su come aprirli e disabilitarli.
- Gli addon possono dichiarare una versione minima dell’app e, solo se necessario,
  una massima: Store e apertura verificano la compatibilità con spiegazioni chiare.
- Tema e lingua condivisi senza incorporare motori, logica o dati specifici degli addon
  nell’app principale.

## 6 ottobre 2026 — 0.29.0.4: Rimozione della modalità Libri

### Compatibilità

- Conservate le correzioni recenti a Home manga, tracking automatico e notifiche.

### Novità

- Rimossa la modalità Libri con il relativo contratto RIN, lettori documenti,
  browser incorporato e download dedicati.
- Pulizia dei moduli e dei dati residui della funzione ritirata sulle installazioni
  aggiornate, senza modificare librerie, progressi e impostazioni anime e manga.

## 6 ottobre 2026 — 0.29.0.1: Home manga e tracking più affidabile

### Verifiche

- Ampliate le verifiche di regressione per Home, tracking e consegna degli avvisi.

### Novità

- Il carosello manga riunisce tutte le sezioni in evidenza delle fonti della
  categoria, unendo le opere con ID pubblici coerenti e mantenendo il cambio fonte.

### Correzioni

- Tracking automatico anime e manga tramite ID AniList verificato anche quando
  il titolo locale è tradotto o abbreviato. La ricerca manuale resta disponibile.
- Errori temporanei di rete e timeout del tracking vengono riprovati con un
  limite; le aggiunte alla libreria rimangono recuperabili dopo un'interruzione.
  Nessun collegamento forzato per risultati ambigui o servizi senza accesso.
- Tracking all'avvio conforme anche alla modalità incognito della singola
  estensione; tentativi dello stesso titolo serializzati per evitare doppioni.
- Avvisi delle nuove uscite conservati se manca il canale Android o se i permessi
  cambiano durante la preparazione della notifica.

## 6 ottobre 2026 — 0.28.0.4: Il nuovo Store

### Novità

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

### Correzioni

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

### Correzioni

- Icone originali delle estensioni RIN mostrate nello Store e nelle schermate di
  gestione anche dopo la rimozione dell’APK precedente.
- Icone del catalogo disponibili anche per le RIN non ancora installate e per i
  pacchetti precedenti senza icona incorporata.

## 6 ottobre 2026 — 0.28.0.1: Compatibilità RIN

### Correzioni

- Passaggio alle estensioni RIN disponibile anche per le fonti installate ma
  nascoste dal filtro dei contenuti adulti; il filtro resta rispettato nell’uso normale.
- Verifica delle fonti prima della migrazione indipendente dalle preferenze di
  visualizzazione, senza modificare libreria, progressi o impostazioni delle fonti.
- Icone delle estensioni conservate nei pacchetti RIN e mostrate anche dopo la
  rimozione dell’APK Android precedente.

## 6 ottobre 2026 — 0.28.0.0: Nyanime RIN

### Novità

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

### Novità

- Ripresa dei blocchi e dei segmenti verificati dopo un’interruzione; percorso
  precedente mantenuto quando il server o il formato non consentono l’accelerazione.
- Le due code condividono i limiti di rete. Aggiungere nuovi elementi conserva
  quelli in corso; l’impostazione dei download simultanei viene rispettata.
- Avanzamento con byte trasferiti, velocità, tempo restante quando disponibile
  e motivo dell’attesa; salvataggio verificato prima del completamento.
- Rimosso il vecchio avviso generico sui download di massa: la coda mostra
  lo stato effettivo e gli eventuali errori di trasferimento.
- Esecuzione dei download manuali tramite i trasferimenti avviati dall’utente
  su Android 14+, mantenendo WorkManager per richieste automatiche e recupero.

### Correzioni

- Salvataggio HLS nelle cartelle Android tramite il file già autorizzato, senza
  errori del protocollo SAF; recupero separato delle code anime e manga al riavvio.
- Corretti il nome della coda Manga, il conteggio della scheda selezionata
  e le indicazioni di attesa dopo il riavvio; testi dei repository aggiornati a Nyanime.

### Prestazioni

- Accelerazione automatica dei video HTTP e HLS compatibili, con connessioni
  adattate alla velocità e ai limiti del server, senza ridurre la qualità.
- Riconoscimento dei contenitori anche nei link senza estensione e aumento più
  rapido delle connessioni quando migliora la velocità.
- Download manga con trasferimenti adattivi, meno scansioni delle cartelle e
  lavorazioni delle immagini e degli archivi limitate per contenere il carico.
- Pausa dei download interni durante la visione, anche in PiP e nelle stanze;
  ripresa all’uscita dal player, senza annullare una pausa scelta manualmente.

## 5 ottobre 2026 — Aggiornamenti delle estensioni nello Store

### Correzioni

- Il refresh ricontrolla i cataloghi sul server anche se la copia locale è ancora
  recente: nuove versioni e nuovi addon non restano nascosti dalla cache.

### Prestazioni

- Se il catalogo è invariato, la verifica HTTP riutilizza i dati già scaricati.
  Controlli di firma, distribuzione e preferenze di aggiornamento restano attivi.

## 5 ottobre 2026 — Filtri dello Store semplificati

### Compatibilità

- Le estensioni installate rimangono disponibili per gestione e aggiornamenti.

### Novità

- Restano soltanto Italiano e Inglese, selezionabili singolarmente o insieme.
- Nuove schede con tutta la riga cliccabile, selezione animata e filtro per adulti
  distinto; le vecchie lingue salvate vengono eliminate dalla selezione.

## 5 ottobre 2026 — Introduzione agli addon all’avvio

### Novità

- La scheda degli addon compare una volta all’apertura di Nyanime, anche senza
  addon installati o cataloghi configurati.
- La conferma viene salvata soltanto premendo Continua; chiudere l’app prima
  della conferma conserva la spiegazione per il prossimo avvio.
- Pulsante sempre accessibile, testo scorrevole e comparsa animata nel tema scelto.

## 5 ottobre 2026 — 0.25.0.0: Addon opzionali

### Novità

- Le modalità aggiuntive si installano dal catalogo delle estensioni: compatibilità,
  firma, autorizzazione e aggiornamenti sono verificati dall’app.
- Ogni addon fornisce il proprio logo e il gesto di apertura. Puoi abilitarlo o
  disabilitarlo senza modificare la Home; il tema segue quello dell’app.
- Una pagina al primo accesso spiega come usare gli addon e il possibile percorso
  delle funzioni alpha e beta, senza riproporsi ai successivi accessi.
- L’app base conserva solo il contratto generico: codice, risorse, motore video e
  dipendenze delle modalità aggiuntive risiedono nei rispettivi APK.

### Correzioni

- Ripristinata la configurazione Gradle dell’app senza i moduli delle modalità
  aggiuntive; rimossi anche i relativi passaggi della compilazione principale.

## 4 ottobre 2026 — Informazioni sulle opere

### Novità

- Approfondimenti facoltativi nelle schede: autori, cast, struttura e opere
  collegate, con informazioni da AniList, TVmaze e Wikidata senza account o chiavi.
- Pagine native delle persone e delle opere collegate, ricerca nelle proprie
  fonti e collegamenti IMDb quando l'identificativo è verificato.
- Associazioni correggibili, precedenza dei dati della fonte e cache disponibile
  offline; consenso e scelte manuali inclusi nei backup delle impostazioni.

## 4 ottobre 2026 — Cinema in cuffia

### Novità

- Audio binaurale facoltativo per le tracce 5.1 e 7.1, con confronto
  «Originale / Cinema» direttamente dal menu Audio del player.
- Preferenza ricordata, riconoscimento delle cuffie e audio originale
  per le tracce non compatibili; nessun modello da scaricare.
- Elaborazione locale leggera con risposte acustiche misurate, senza
  cambiare volume, punto di ripresa o sincronizzazione delle stanze.

## 4 ottobre 2026 — Categorie di lettura dalle estensioni

### Novità

- Home di lettura distinte secondo le categorie dichiarate dalle estensioni,
  con sezioni, generi e ricerca coerenti nella Home e in Atlante.
- Ripresa e novità nella categoria pertinente, mantenendo la libreria,
  i progressi e i controlli su lingue, privacy e contenuti per adulti.
- Filtri di esplorazione dichiarati dalle estensioni anche per la lettura,
  schede dei capitoli più leggibili e apertura nella categoria scelta.

### Correzioni

- Errori delle sezioni visibili e riprovabili, senza svuotare i contenuti
  salvati; eliminate le richieste agli ID superflue nelle Home con una sola fonte.

## 4 ottobre 2026 — Cataloghi tramite link e versioni consigliate

### Novità

- Pulizia una tantum dei cataloghi per chi aggiorna; le nuove installazioni,
  le estensioni installate, la libreria e i progressi restano esclusi.
- Cataloghi aggiunti tramite «Aggiungi a Nyanime» e link di importazione,
  con istruzioni al posto dell'inserimento manuale degli indirizzi.
- Autorità facoltative dei cataloghi, mostrate prima del consenso: possono
  consigliare le proprie versioni firmate, mantenendo la scelta all'utente.
- Sostituzione guidata delle versioni con firma diversa, anche senza Home,
  con controllo delle identità, download verificato e conferma prima della rimozione.

## 4 ottobre 2026 — Store Vetrina

### Novità

- Nuovo Store unificato per Anime, Manga e Notizie, con schede Nyanime Ready
  e un elenco Legacy distinto, ricerca, lingue e inventario delle installate.
- Installazione e aggiornamento dalla vetrina, mantenendo gestione delle fonti,
  impostazioni, fiducia, cataloghi e migrazione della libreria.
- Passaggio guidato dalle legacy compatibili alle Ready: download e verifica prima
  della rimozione, ripresa della procedura e aggiornamento diretto quando possibile.
- Testata stabile, dissolvenze coordinate, icone con spazio riservato e rispetto
  delle animazioni ridotte; italiano e inglese inclusi.

## 4 ottobre 2026 — Schede Primo piano

### Novità

- Elenco completo di episodi e capitoli direttamente nella scheda, con ricerca,
  indice rapido e salto al punto di ripresa; gestione multipla separata.
- Nuove schede anime e manga con copertina protagonista, progresso e ripresa immediata,
  mantenendo tracking, avvisi, stanze, categorie e tutte le azioni esistenti.
- Gestore di episodi e capitoli con ricerca per numero o titolo, indice a gruppi,
  novità effettive, offline, segnalibri e selezione multipla.
- Caricamento con spazi già riservati, icone coordinate e transizioni della copertina
  coerenti anche al ritorno; rispetto delle animazioni ridotte.

### Prestazioni

- Posizione conservata tra elenco e dettagli, righe caricate solo quando servono
  e caricamento più stabile di progresso, ripresa e avvisi opzionali.

## 4 ottobre 2026 — Cataloghi unificati e aggiornamenti delle estensioni

### Novità

- Possibilità di collegare un’estensione installata manualmente a un catalogo compatibile,
  scegliendo esplicitamente l’origine nei dettagli dell’estensione.
- Controllo di pacchetto, firma, distribuzione e supporto Home prima del passaggio;
  «Mantieni questa versione» continua a escludere gli aggiornamenti.
- Un’unica conferma per importare cataloghi che comprendono video, manga e notizie;
  ogni sezione mostra soltanto le estensioni del proprio tipo.
- Installazione e aggiornamento delle estensioni Notizie dalla schermata Fonti,
  con avanzamento, controlli di firma e consenso separato per abilitarle.
- Collegamenti Nyanime per importare cataloghi senza credenziali condivise
  e senza nomi o regole delle fonti incorporati nell’app.
- Sito e nuovi link di condivisione sul dominio dell’organizzazione; collegamenti
  precedenti ancora compatibili, inclusi episodio, minutaggio, capitolo e pagina.

### Correzioni

- Versioni e pulsanti delle estensioni Notizie aggiornati al ritorno dall’installatore,
  conservando preferenze e consenso quando l’identità della distribuzione coincide.

## 3 ottobre 2026 — Una nuova schermata per gli aggiornamenti

### Novità

- Schermata degli aggiornamenti ridisegnata con testata compatta, versione e comandi
  fissi; scorrono soltanto le novità, in schede leggibili nei temi chiaro e scuro.
- Download con avanzamento, annullamento e possibilità di continuare a usare l’app;
  «Installa ora» compare dopo il controllo del file scaricato.

### Prestazioni

- Controlli fuori dal thread dell’interfaccia, protezione dai tocchi ripetuti e recupero
  dei file non più disponibili, con messaggi in italiano e inglese.

## 3 ottobre 2026 — Nuovo repository e continuità degli aggiornamenti

### Compatibilità

- Compatibilità mantenuta con il vecchio indirizzo OTA e con le versioni `r…`,
  conservando pacchetto, firma e dati dell’app.

### Novità

- Aggiornamenti e collegamenti di aiuto usano il repository dell’organizzazione.
- Recupero tramite l’indirizzo precedente se quello principale non è disponibile;
  il cambio di repository avvia un nuovo controllo senza attendere la vecchia cache.

## 3 ottobre 2026 — Versioni e scelta degli aggiornamenti

### Distribuzione

- Pubblicazioni con note dedicate e APK immutabili, mantenendo pacchetto e firma per
  conservare i dati. [Numerazione e compatibilità](docs/versioning.md).

### Novità

- Nuova numerazione a quattro componenti, a partire da 0.19.0.0, con confronto numerico
  e versione Android crescente; le installazioni precedenti continuano ad aggiornarsi via OTA.
- Scelta iniziale tra «Consigliati» e «Anche le anteprime», chiesta una sola volta dopo
  la conferma, con schermata adattiva nei due temi e testi in italiano e inglese.
- Preferenza modificabile in Impostazioni → Aggiornamenti, con controllo manuale e
  installazione integrata; cambiare canale non propone versioni precedenti.

## 3 ottobre 2026 — Player più reattivo e compatibilità delle estensioni

### Correzioni

- Il caricamento delle tracce rispetta il cambio di episodio e la chiusura del player,
  evitando di applicare tracce a una riproduzione successiva.
- Ripristinata la compatibilità con le varianti della libreria video 16 usate dalle
  estensioni, evitando errori di apertura dovuti ai costruttori dei server video.

### Prestazioni

- Caricamento delle tracce audio e dei sottotitoli esterni fuori dal thread
  dell’interfaccia, per evitare blocchi all’apertura dei video con molte tracce.

## 3 ottobre 2026 — Rimozione del doppio tocco posteriore

### Novità

- Rimossi il gesto posteriore, la calibrazione, le relative impostazioni e
  l’ascolto dei sensori in tutte le schermate.
- Pulizia automatica delle preferenze obsolete, escluse anche dal ripristino
  dei vecchi backup. Restano disponibili i normali gesti del player e del lettore.

## 2 ottobre 2026 — Icone delle estensioni Notizie

### Novità

- Le fonti Notizie mostrano l’icona del rispettivo APK anche se disabilitate,
  con caricamento fuori dall’interfaccia e un segnaposto per i pacchetti senza icona.

## 2 ottobre 2026 — Notizie legate ai tuoi titoli

### Novità

- «Per te» riconosce anche i nomi alternativi e tradotti verificati nei cataloghi,
  gli ID equivalenti e le relazioni dirette tra opere, senza modificare il tracking.
- Le schede spiegano il collegamento al titolo seguito; le semplici citazioni possono
  comparire nel feed, ma non generano notifiche personali senza un riscontro affidabile.
- Recupero graduale dei dati mancanti degli articoli, cache degli abbinamenti e
  esclusioni valide anche per gli ID equivalenti. Restano esclusi gli avvisi storici.

## 2 ottobre 2026 — Notizie dalle estensioni

### Novità

- Nuovo tipo di estensione Notizie, separato dalle fonti video e manga: si abilita da
  Sfoglia e aggiunge una categoria nella Home, mantenendo la testata e la navigazione.
- Articoli compatti in Ultime, Per te e Salvati, ricerca dedicata e lettore integrato
  con testo regolabile, immagini ingrandibili e collegamento alla pubblicazione originale.
- Salvataggi per leggere il testo offline, punto di lettura e associazioni ai titoli
  inclusi nei backup. Gli articoli nuovi non spostano quelli che stai leggendo.
- Notifiche facoltative per fonte, con controllo periodico, deduplicazione e nessun
  invio dell’archivio storico alla prima attivazione o dopo un ripristino.

## 2 ottobre 2026 — Ripresa compatta nella Home

### Novità

- «Continua a guardare» usa segnalibri orizzontali compatti: piccola copertina verticale,
  titolo, episodio, minutaggio e avanzamento, con ripresa al tocco e menu separato.
- Altezza adattata ai caratteri grandi e skeleton delle stesse dimensioni, mantenendo
  apertura della scheda, nascondi con annullamento e ripresa del finale.

## 2 ottobre 2026 — Carosello senza intestazione

### Novità

- Rimossa la riga «In evidenza» con la freccia sopra il carosello, anche durante
  il caricamento: le copertine partono direttamente sotto le categorie.

## 2 ottobre 2026 — Ripresa dalla Home e fluidità

### Novità

- «Continua a guardare» nella Home diventa una fila di schede ampie: ripresa diretta,
  episodio, minutaggio e avanzamento leggibili, con le altre azioni raccolte nel menu.
- Rimossi gli indicatori sotto i titoli in primo piano per alleggerire la Home.

### Correzioni

- Conservati gli angoli arrotondati delle copertine durante apertura e ritorno dalla
  scheda, con una sola immagine e un cambiamento continuo di forma e proporzioni.

### Prestazioni

- Migliorata la fluidità delle transizioni tra Home e schede, limitando i ricalcoli
  agli elementi coinvolti; le azioni sulla copertina seguono la dissolvenza del titolo.

## 2 ottobre 2026 — Aumento della numerazione

### Distribuzione

- Aumento di numerazione, nessun cambiamento alle funzionalità dell'app.

## 2 ottobre 2026 — Home Panorama

### Compatibilità

- Categorie, sezioni, date, classifiche, capitoli, paginazione e fonti alternative
  continuano a usare i contratti delle estensioni, senza regole specifiche nell'app.

### Novità

- Nuova Home Panorama per anime e manga: copertina centrale ampia, anteprime laterali,
  scorrimento circolare con il dito e rotazione dopo sei secondi di inattività.
- Testata stabile, titoli in dissolvenza e skeleton con le stesse dimensioni delle
  copertine. Il titolo selezionato si conserva durante gli aggiornamenti delle sezioni.
- Ripresa compatta con progressi e opzioni; novità più leggibili, con tutte le voci
  raggiungibili e azioni di lettura, riproduzione e apertura della scheda conservate.

### Prestazioni

- Rotazione sospesa durante l'interazione, fuori dalla sezione visibile, nelle finestre
  di scelta della fonte e con animazioni ridotte o lettore schermo attivo.

## 1 ottobre 2026 — Lingua della Libreria

### Correzioni

- Il pulsante Libreria e le intestazioni della raccolta seguono la lingua scelta
  nell'app, mostrando «Library» in inglese e «Libreria» in italiano.

## 1 ottobre 2026 — Affidabilità della Home

### Correzioni

- Migliorato il coordinamento tra dati locali e inizializzazione asincrona delle estensioni.
- Gestiti gli aggiornamenti del registro delle estensioni senza modificare i dati
  persistenti o introdurre richieste di rete aggiuntive.

## 1 ottobre 2026 — Atlante, ricerca unificata

### Novità

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

### Prestazioni

- Richieste limitate e condivise tra i due tipi di contenuto, risultati obsoleti scartati
  e comportamento incognito/Solo scaricati conservato. [Guida](docs/atlas-search.md).

## 1 ottobre 2026 — Risultati completi per grafie equivalenti

### Verifiche

- Aggiunte verifiche della prima ricerca e delle successive con cache già alimentata.

### Correzioni

- La ricerca con spazi o punteggiatura diversa conserva la query breve e recupera
  anche stagioni, parti e titoli collegati, senza restringersi al primo nome completo.
- Un risultato iniziale non blocca il recupero delle grafie equivalenti note;
  refresh e paginazione conservano tutte le varianti verificate, entro i limiti esistenti.

## 30 settembre 2026 — Ricerca intelligente dei titoli

### Compatibilità

- Nessun aggiornamento obbligatorio delle estensioni e nessuna modifica automatica
  al tracking o alle associazioni dei titoli. [Dettagli e licenze](docs/smart-title-search.md).

### Novità

- Home, Sfoglia, singole fonti e libreria riconoscono punteggiatura diversa,
  parole unite, refusi e alias, mantenendo il testo originale e i filtri.
- Suggerimenti di titolo distinti dai risultati disponibili, risultati progressivi e recuperi limitati
  e comando per alternare ricerca esatta e intelligente.
- Recupero automatico del candidato plausibile anche con alternative visibili;
  numeri del nome e delle parti con precedenza, con recupero del titolo base
  quando il numero finale non corrisponde a una parte riconosciuta.
- Aiuto online facoltativo dei cataloghi, cache locale cancellabile e nessuna
  nuova memorizzazione persistente in incognito. La libreria resta tutta locale.

## 30 settembre 2026 — Impostazioni delle notifiche più facili da trovare

### Novità

- Aggiunta «Notifiche e uscite» direttamente nelle Impostazioni, con orari dei
  promemoria, avvisi per nuovi episodi e capitoli, permessi Android e notifiche di prova.
- Le scelte sono raggiungibili anche dalla ricerca delle impostazioni; la Libreria
  rimanda alla stessa schermata. Gli orari si conservano quando i promemoria sono spenti.
- I promemoria si possono attivare anche mentre si scelgono gli orari, senza tornare
  indietro. Il salvataggio riprogramma gli avvisi anche uscendo subito dalla schermata.

## 30 settembre 2026 — Traduzioni inglesi più complete

### Compatibilità

- L’italiano rimane disponibile; codici d’invito e riferimenti delle estensioni
  conservano il formato esistente.

### Novità

- Stanze, inviti, QR, lettura condivisa e schizzi seguono la lingua dell’app,
  inclusi messaggi di connessione, comandi nel player e notifica della stanza.
- Tradotti anche i testi e i filtri della Home, le opzioni della sigla e dei comandi del player,
  il download degli aggiornamenti nell’app e la visibilità dei manga in altre lingue.

## 30 settembre 2026 — Collegamenti condivisi apribili dall’app

### Compatibilità

- I riferimenti rimangono dati opachi forniti dalle estensioni: nessuna fonte o
  regola di sito entra nell’app.

### Novità

- Schede, episodi con minutaggio e capitoli con pagina vengono condivisi con link
  HTTPS e titolo leggibile, adatti alle applicazioni di messaggistica.
- Il sito Nyanime permette di aprire il contenuto anche nelle versioni precedenti;
  l’app aggiornata riconosce i collegamenti verificati. I vecchi link restano validi.

## 30 settembre 2026 — Orari personalizzati per i promemoria

### Compatibilità

- Le preferenze già esistenti per 24 ore prima e all’uscita restano attive dopo
  l’aggiornamento. Le notifiche di contenuti disponibili nella fonte restano separate.

### Novità

- I promemoria delle trasmissioni anime possono essere combinati: 24 ore prima o alle 09:00,
  15:00 e 20:00 del giorno precedente; il giorno stesso agli stessi orari,
  un’ora, 10, 5 o 2 minuti prima, oppure all’orario annunciato.
- Gli orari fissi seguono il fuso del telefono. Gli avvisi che arriverebbero dopo
  l’uscita o troppo tardi vengono saltati; scelte coincidenti non duplicano la notifica.

### Correzioni

- La riprogrammazione completa il salvataggio anche uscendo subito dalle impostazioni;
  cambiare la lingua del telefono conserva gli orari scelti.

## 30 settembre 2026 — Backup più semplici

### Novità

- Un backup completo si avvia con un tocco da Altro o da Dati e archiviazione,
  con avanzamento visibile anche cambiando schermata.
- I backup manuali vengono salvati in Download/Nyanime e verificati prima di
  comparire tra i file. Contengono anche impostazioni sensibili ed estensioni,
  ma non i video o i capitoli scaricati.
- Il ripristino mostra i backup trovati sul dispositivo e un'anteprima del loro
  contenuto; rimane possibile selezionare un file esterno o un vecchio `.tachibk`.

## 30 settembre 2026 — Home e navigazione

### Novità

- Copertine protagoniste, sezioni e stati di caricamento riorganizzati nella
  Home per rendere più chiari titoli, azioni e contenuti in evidenza.
- Barra inferiore flottante più contrastata, senza alone o fondale esterno. Le
  pagine scorrono sotto la barra e lasciano libero l'ultimo contenuto a fine elenco.
- In modalità scura il fondale della barra inferiore è più scuro e uniforme.
- Il logo vettoriale arancione della modalità chiara usa una tonalità più scura.

### Correzioni

- Categorie e filtri conservano la disposizione compatta originale; i comandi
  della testata mantengono una posizione stabile quando cambiano le novità.

## 30 settembre 2026 — Libreria Anime e Manga ridisegnata

### Novità

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

### Novità

- “Continua a guardare” propone il prossimo episodio quando restano pochi secondi
  oppure la riproduzione arriva a cinque secondi dall'inizio della sigla finale riconosciuta.
- Il finale resta accessibile dalla scheda di ripresa. Alla successiva apertura
  della Home o di una scheda di ripresa, il marker verificato aggiorna “visto”
  e accoda il tracking, senza modificare la riproduzione.
  I marker finali sono conservati nel backup.
- Home, scheda, libreria e cronologia condividono la scelta dell'episodio da riprendere.

## 29 settembre 2026 — Manga unificati e cambio fonte

### Sicurezza

- Identità discordanti restano separate; cambiare fonte non migra automaticamente
  libreria, download o progresso. Dati e logica dei siti restano nelle estensioni.

### Novità

- Riconoscimento delle fonti alternative tramite ID pubblici, condiviso tra
  Home, ricerca e scheda manga anche quando i titoli compaiono in sezioni diverse.
- Cambio fonte in un pannello compatto, con scelta della fonte predefinita;
  apertura diretta e collegamenti ai capitoli conservati per ciascuna estensione.
- Recupero progressivo delle identità senza limite ai primi otto risultati,
  mantenendo posizione delle schede e scorrimento durante gli aggiornamenti.
- La Home Manga apre direttamente con filtri e contenuti, senza intestazione e
  scorciatoia duplicate; la Libreria rimane nella navigazione principale.

## 29 settembre 2026 — Menu delle uscite nell’agenda

### Novità

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

### Novità

- Condivisione delle schede tramite link `nyanime://open/v1`, aperti direttamente
  nell’app tramite l’estensione installata sul telefono del destinatario.
- Un unico pannello per schede, episodi e capitoli: scelta tra titolo, inizio
  dell’episodio/capitolo, minuto del player o pagina del lettore.
- Pulsanti Condividi/Copia sempre raggiungibili anche nel player orizzontale;
  disposizione compatta a due colonne sugli schermi larghi e bassi, con tutte
  le destinazioni subito visibili e contenuto scorrevole per i testi grandi.
- Azione di condivisione anche selezionando un singolo episodio o capitolo;
  link alla pagina distinto dalla condivisione dell’immagine nel lettore.
- Apertura del riferimento preciso, senza cercare un titolo simile; errori
  espliciti per estensione assente, link non valido e contenuto non disponibile.
- I link non includono URL di streaming, intestazioni, cookie, ID del database
  locale o dati delle stanze. Le normali azioni WebView/browser restano disponibili.

### Correzioni

- Aprire un episodio condiviso durante un’altra riproduzione attende il rilascio
  effettivo del vecchio player, evitando due inizializzazioni native sovrapposte.

## 29 settembre 2026 — Notifiche delle uscite più leggibili

### Novità

- Nome del titolo sulla prima riga compatta; nome completo a capo nei dettagli
  espansi, leggibile anche quando l'annuncio è lungo.
- Titoli espliciti: “Domani esce un nuovo episodio di…” e “Oggi esce un nuovo
  episodio/capitolo di…”, con plurali per le uscite multiple.
- Numero e ora in una riga breve; notifica espansa con data completa, trasmissione,
  piattaforme e dettagli disponibili. Orari nel fuso e nel formato scelto sul telefono.
- Struttura coerente anche per gli avvisi della libreria; azioni, canali, impostazioni
  di privacy e ricevute di consegna mantenuti.

### Correzioni

- Avvisi di disponibilità distinti dagli annunci: una data di rilevamento o un
  recupero storico non diventano una pubblicazione di oggi. Orario di rilevamento
  indicato come tale, senza inventare l’ora di uscita dei capitoli.

## 29 settembre 2026 — Protezione laterale integrata

### Novità

- Compatibilità rilevata dall’hardware e dalle API, senza vincolo sul firmware esatto.
- Protezione selezionabile per le schede dei titoli, con copertina, descrizione ed episodi o capitoli.
- Comando temporaneo nel lettore manga, condiviso con quello del player.
- Opzione “Solo in incognito” per le aree scelte, rispettando anche l’incognito della fonte corrente.
- Stato più chiaro nelle impostazioni e nei comandi della sessione: attesa, richiesta, sospensione ed errore.

## 29 settembre 2026 — Manga in altre lingue visibili inizialmente

### Novità

- “Mostra manga in altre lingue” è ora attivo di default per chi non ha ancora
  salvato una scelta. Le preferenze già impostate rimangono rispettate.

## 29 settembre 2026 — Splash coerente con il tema scelto

### Limitazioni

- Il primo avvio dopo l’aggiornamento può mostrare ancora la splash precedente
  prima che Android registri la preferenza per gli avvii successivi.

### Novità

- Gestione del tema nativo separata da quella dell’icona; preferenze e conferma
  iniziale conservate. Sulle versioni precedenti rimane la compatibilità AppCompat.

### Correzioni

- Su Android 12 e successivi, splash e sfondo iniziale rispettano la modalità
  Chiaro o Scuro scelta nell’app, anche se il telefono usa il tema opposto.
  Segui il sistema rimuove la forzatura e segue i cambiamenti di Android.

## 29 settembre 2026 — Promemoria del giorno prima

### Novità

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

### Novità

- Rapporti dei test e diagnostica di compilazione conservati in caso di fallimento,
  senza allegare dump di memoria del processo.

### Correzioni

- Profilo CI condiviso per preview e pull request: compilatore Kotlin nello stesso
  processo Gradle, memoria riservata aumentata e compilazione dei moduli senza
  parallelismo. Corretto il percorso che esauriva la memoria prima dei test.

## 29 settembre 2026 — AnimeSchedule facoltativo e configurazione guidata

### Novità

- Collegamento opzionale, inizialmente disattivato, con guida nel browser interno,
  verifica del token e salvataggio cifrato sul dispositivo; nessun token incluso nell’APK.
- Guida con moduli adattati allo schermo, scelta Accedi/Crea account, passaggio
  automatico alle impostazioni API e importazione del token con un comando esplicito.
  Sito completo e inserimento manuale sempre disponibili; password mai lette dall’app.
- Consenso ai soli cookie necessari configurato prima dell’accesso; scelte già
  salvate sul sito conservate. Termini e verifiche di registrazione restano visibili.
- Orari RAW, SUB inglese e DUB inglese distinti, première, rinvii, date eccezionali
  e piattaforme integrate nell’agenda esistente e nelle schede anime.
- Associazione tramite ID di catalogo verificati, cache condivisa degli orari e rispetto
  dei limiti del servizio. Il catalogo abituale rimane disponibile senza configurazione.
- Trasmissione preferita per promemoria e controlli mirati delle disponibilità;
  orario annunciato distinto dal contenuto effettivamente trovato nell’estensione.
- Widget “Le tue uscite” basato sulla stessa agenda locale anime e manga, con
  apertura diretta e rispetto della modalità incognito e della privacy delle notifiche.

### Correzioni

- Moduli guidati senza larghezza aggiunta dai margini del sito: campi e collegamenti
  contenuti nello schermo stretto, messaggi di conferma della registrazione preservati.
- Conto alla rovescia della scheda e dettaglio degli orari coordinati con la
  trasmissione preferita; un rinvio senza data non conserva un vecchio conto alla rovescia.
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

## 29 settembre 2026 — Agenda futura e verifica degli orari

### Novità

- Verifiche degli orari in una coda indipendente dagli aggiornamenti degli
  episodi: il primo controllo prosegue a piccoli gruppi fino a raggiungere
  tutti i titoli con identificatori, anche senza aprire le loro schede.
- Titoli senza ID esclusi dal controllo del catalogo degli orari e dai relativi
  avvisi. Il monitoraggio dei contenuti disponibili nella fonte continua.
- Verifiche in attesa distinte dagli ID non confermati e dagli errori di rete;
  i titoli coinvolti possono essere consultati direttamente dall’agenda.

### Correzioni

- Uscite future conservate nell’agenda anche quando il numero dell’episodio
  è già registrato localmente; date lontane e deduplicazione fra edizioni mantenute.
- Fine agenda con un messaggio esplicito, senza una schermata vuota aggiunta
  allo scorrimento. Apertura su oggi mantenuta anche quando ci sono solo uscite passate.

## 29 settembre 2026 - Estensioni e distribuzioni protette

### Novità

- Schermate Estensioni anime e manga con viste Installate e Catalogo, schede
  compatte e filtri per lingua, repository e supporto Home, anche con UI legacy.
- Provenienza e Home distinte: badge Adattata a Nyanime, Home integrata,
  Home parziale e Senza Home con spiegazioni nei dettagli.
- Aggiornamenti soltanto per pacchetto, firma, distribuzione e API compatibili.
  Le edizioni locali manuali e quelle protette non vengono sostituite dai cataloghi;
  repository duplicati non vengono scelti arbitrariamente.
- Mantieni questa versione nei dettagli, conservato nei backup e rivalidato
  contro la firma degli APK presenti dopo il ripristino.
- APK scaricati verificati crittograficamente prima di qualsiasi installatore,
  comprese le installazioni private; controlli di pacchetto, versione, API,
  firma e descrittore della distribuzione.

### Correzioni

- Contatori, notifiche e Aggiorna tutte usano la stessa politica. Un catalogo
  irraggiungibile mostra Controllo non riuscito e viene ricontrollato, senza
  dichiarare obsolete le estensioni locali.

## 28 settembre 2026 — Identificatori verificati per gli orari

### Novità

- Il filtro Tutti usa ora il violetto uniforme, con riempimento soltanto
  quando selezionato; Anime e Manga mantengono arancione e celeste.

### Correzioni

- Il tracking già salvato ha precedenza sugli identificatori forniti dalle
  estensioni. Un ID AniList inesistente può essere risolto tramite l’ID
  MyAnimeList collegato, soltanto dopo conferma della corrispondenza nel catalogo.
- Identificatori contraddittori non selezionano un altro titolo in silenzio.
  Una corrispondenza assente viene distinta da un errore di connessione.

## 28 settembre 2026 — Recupero delle verifiche degli orari

### Correzioni

- Una richiesta degli orari fallita non eredita più le sei ore di validità
  della verifica precedente: il monitor e la scheda usano la stessa regola
  per ritentare, conservando le date salvate e limitando richieste ravvicinate.
- Quando l’ultimo orario è passato, il calendario può ricontrollare il titolo
  senza aspettare la scadenza dei metadati. Un cambio dell’orologio non blocca i tentativi.
- Gli avvisi degli orari anime non compaiono nel filtro Manga. Il controllo
  riuscito rimuove l’avviso; gli errori sono registrati per poterli diagnosticare.

### Prestazioni

- Richieste degli orari con il DNS configurato nell’app e il limite condiviso
  con Home e tracking, evitando un secondo flusso indipendente di richieste.

## 28 settembre 2026 — Filtri delle uscite e verifica della navigazione

### Novità

- Filtri Tutti/Anime/Manga centrati e distribuiti sulla larghezza disponibile,
  con etichette leggibili anche negli schermi stretti e con caratteri grandi.
- L’interno dei filtri si colora soltanto quando sono selezionati.
- Selezione dei filtri con transizione morbida del riempimento, senza spostare
  i pulsanti o ridisegnare il testo. Animazione ridotta rispettata.

### Correzioni

- Corretto il test della navigazione rimasto precedente all’aggiunta di Uscite,
  che impediva la build GitHub; verifica delle destinazioni conservata anche per
  le vecchie preferenze di navigazione.

## 28 settembre 2026 — Agenda continua, aperta su oggi

### Novità

- L’agenda si apre su oggi: scorrendo verso l’alto si consultano le uscite
  precedenti e verso il basso quelle future, senza il limite di sette giorni.
- Filtri e scelta Agenda/Calendario sempre accessibili; Oggi riporta al punto
  corrente. Se oggi non ci sono uscite, resta una posizione chiara nella lista.
- Il giorno scelto nel calendario diventa il punto di apertura dell’agenda.
  Gli aggiornamenti dei dati mantengono la posizione senza riportare la lista all’inizio.

### Prestazioni

- Consultazione basata sui dati locali e sulle date effettive, senza nuove
  richieste alle fonti durante lo scorrimento.

## 28 settembre 2026 - Uscite seguite e calendario affidabile

### Novità

- Nuovo comando Segui nelle schede anime e manga, con preferenze separate
  per nuove disponibilità e promemoria delle trasmissioni annunciate.
- Vista Agenda con schede raggruppate per giorno e Calendario con giorni
  vuoti selezionabili;
  date annunciate tramite ID verificati, distinte dalla disponibilità nella fonte.
- Uscite accanto a Libreria nella navigazione principale. Anime e manga insieme,
  filtri Tutti/Anime/Manga e preferenza per consultarli separatamente.
- Schede anime con bordo sfumato arancione; manga in celeste, con contrasto
  adattato al tema chiaro e scuro e indicazione testuale del tipo di contenuto.
- Uscite dello stesso anime riunite tra fonti ed edizioni tramite i criteri della
  Home e identificativi verificati; una scheda con scelta di fonte ed edizione, senza
  confondere le stagioni. Visto in una fonte non resta annunciato in un'altra.
- Avvisi salvati insieme agli episodi e capitoli, deduplicazione e recupero
  quando le notifiche vengono riabilitate. Test degli avvisi e stato dei controlli
  nelle impostazioni; promemoria puntuali facoltativi.
- Preferenze Segui incluse nei backup, mantenimento dei progressi quando cambiano
  gli indirizzi e coordinamento tra scheda e aggiornamento in background.

### Correzioni

- Corretta la data dei capitoli storici: l'agenda usa soltanto date annunciate o
  di pubblicazione fornite dalla fonte; il rilevamento nell'app non diventa
  un'uscita di oggi. Riparazione degli avvisi esistenti al controllo del titolo,
  senza cancellare letture, capitoli o ricevute delle notifiche.
- Ottimizzatore Android aggiornato alla versione compatibile con Kotlin 2.4,
  per completare correttamente gli APK firmati.

### Prestazioni

- Aggiornamenti automatici attivi inizialmente, controlli mirati, recupero
  persistente degli errori e rotazione delle librerie grandi, senza aggiornare
  tutto a ogni apertura o fermarsi ai primi titoli.
- Agenda caricata dai dati locali, senza attendere una verifica in rete di tutta
  la libreria; giorni e mesi si cambiano senza nuovi controlli remoti.

## 28 settembre 2026 — Protezione laterale del display, verifica hardware

### Novità

- Aggiunta una protezione hardware facoltativa in Impostazioni → Sicurezza,
  con selezione separata di video e sottotitoli, lettura manga, cronologia,
  ripresa e ricerca. Il comando principale parte spento.
- Opzione aggiuntiva per le aree NSFW dichiarate dal contenuto o dall’estensione,
  senza oscuramento frontale o riconoscimento dei nomi delle fonti.
- Comando temporaneo in Player → Altro; geometria aggiornata insieme alla finestra
  e gestione degli errori separata dalla riproduzione. Preferenze incluse nei backup,
  senza esportare lo stato hardware o l’avviso locale.
- Modulo indipendente con adattatore Samsung originale. Sui dispositivi incompatibili
  il comando è disabilitato. Le build ordinarie mantengono disabilitate le modalità
  senza verifica fisica; l’APK locale di prova permette la valutazione sul Galaxy
  S26 Ultra. PiP, multifinestra e display esterni restano esclusi.
  [Requisiti e validazione](docs/privacy-display.md).

### Prestazioni

- Regione gestita tramite una vista dedicata, senza spostare o ridisegnare i contenuti
  dell’app. Gestione dei margini richiesti dal pannello e aggiornamenti della posizione
  senza ripetere l’attivazione hardware.

## 28 settembre 2026 — ModernUI chiara e scelta iniziale del tema

### Novità

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

### Interfaccia

- Conservati posizione di scorrimento, ricerca, loghi delle estensioni, ripresa
  e novità di ogni Home, inclusa Manga. La riduzione delle animazioni è rispettata.

### Novità

- Le categorie della Home condividono una sola testata: cambia soltanto il
  contenuto sottostante con una dissolvenza, senza scorrimento laterale della pagina.
- Hero e righe in caricamento usano skeleton con una pulsazione discreta e
  dimensioni adattate allo schermo e al testo. I dati già disponibili restano
  visibili durante gli aggiornamenti; errori e sezioni vuote non restano in caricamento.

## 28 settembre 2026 — Scegliere la stagione dal manga

### Novità

- La scelta degli anime collegati a un manga mostra i capitoli documentati di
  inizio e fine per ogni stagione, in un riquadro compatto sotto titolo e anno.
  Sono conservati anche i riferimenti a una pagina interna al capitolo.
- I due percorsi anime/manga condividono dati e cache di catalogo. Film, speciali
  e stagioni senza un riferimento pertinente non ereditano intervalli di altre
  stagioni; l'apertura del titolo continua a verificarne gli ID nelle estensioni.

## 28 settembre 2026 — Home torna prima in cima

### Novità

- Toccando di nuovo Home, una pagina scorsa torna in cima; il cambio categoria
  avviene soltanto quando si è già all'inizio della pagina.
- Lo stesso comportamento vale per le Home video, Manga e catalogo. Durante
  il ritorno in cima, tocchi ripetuti non cambiano categoria.

## 28 settembre 2026 — Collegamenti anime e manga sempre compatti

### Novità

- Rimossi il pulsante di espansione e i dettagli estesi. Restano il gradiente,
  i riferimenti di stagione e capitolo e l'apertura diretta del titolo.
- Se sono disponibili più adattamenti o copie, una breve scelta al tocco conserva
  tutte le destinazioni senza ingrandire la scheda.

## 28 settembre 2026 — Dettagli manga senza ripetizioni

### Correzioni

- La scheda espansa mantiene i riferimenti compatti di stagione, inizio e fine
  una sola volta. Rimossi i paragrafi duplicati dai dettagli; restano le copie
  alternative, la copertina e le azioni di apertura.

## 28 settembre 2026 — Capitoli della stagione aperta

### Interfaccia

- Conservati il gradiente, l'apertura diretta del manga e il controllo circolare
  per espandere e richiudere i dettagli.

### Novità

- La scheda compatta anime/manga mostra due righe con stagione, inizio/fine e
  capitolo documentato. Il numero si riferisce alla stagione aperta nella scheda.

### Correzioni

- Riconosciuti anche i riferimenti «S1» senza episodio e i campi con più stagioni:
  i capitoli di un'altra stagione o dell'intera serie non vengono riutilizzati
  come fine di un sequel. Gli archi con riferimenti pertinenti hanno la precedenza.

## 28 settembre 2026 — Riferimenti manga a colpo d'occhio

### Novità

- La scheda compatta mostra subito il capitolo iniziale e l'ultimo capitolo
  documentato, quando disponibili. Il gradiente è condiviso con la versione
  espansa, mantenendo un solo controllo circolare per aprire e richiudere i dettagli.

### Correzioni

- Il passaggio dal manga all'anime verifica l'identità della copia nei metadati
  dell'estensione: vecchie associazioni di tracking da sole non fanno comparire
  adattamenti diversi tra le copie dell'anime.

## 28 settembre 2026 — Dal manga all'anime

### Novità

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

### Novità

- Quando un anime deriva da una novel, Nyanime mostra anche i manga collegati
  alla stessa opera, distinguendo il rapporto indiretto da un adattamento diretto.
  Gli intervalli stagione/episodio documentati aiutano a scegliere l'arco, senza
  usarli per indovinare un capitolo esatto.

### Correzioni

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

### Novità

- La scheda anime riconosce i manga collegati tramite ID di catalogo e, quando
  l'estensione lo fornisce, apre direttamente una copia verificata senza cercarla
  per titolo.
- I punti di inizio e fine adattamento compaiono quando sono documentati. Il
  passaggio a un capitolo preciso è proposto soltanto se il riferimento
  all'episodio è esplicito; non vengono stimate corrispondenze mancanti.
- Le estensioni possono fornire ricerca per ID e link correlati tramite contratti
  generici, mantenendo nell'estensione ogni logica del rispettivo sito.

## 27 settembre 2026 — Categorie Home adattive

### Novità

- Le categorie della Home si distribuiscono su una o due righe secondo la larghezza
  dello schermo. Se sono ancora troppe, restano raggiungibili scorrendo lateralmente.
- Una pressione lunga porta una categoria all'inizio e conserva l'ordine scelto.
  Toccare nuovamente Home nella barra inferiore passa alla categoria successiva,
  tornando alla prima dopo l'ultima.

## 27 settembre 2026 — Esplorazione manga e filtro lingue

### Novità

- La sezione personale si chiama ora «Libreria». Nella Home manga i generi sono
  subito disponibili sotto l'intestazione, con chip e ricerca nell'elenco completo.
- Etichette equivalenti in italiano e inglese confluiscono in un solo genere;
  ogni fonte riceve comunque il proprio filtro originale.
- La preferenza già presente per le altre lingue ora vale anche in ricerca,
  elenco fonti, Libreria, cronologia e novità manga. Nascondere un titolo non
  cancella letture, download o dati salvati.

### Prestazioni

- La ricerca usa una corsia separata dai caricamenti della Home e presenta i
  risultati di ogni fonte appena arrivano, senza attendere le altre.

## 27 settembre 2026 — Compatibilità completa con le estensioni manga 1.6

### Novità

- Le schede manga, la biblioteca, il tracking, la ricerca e le stanze usano ora
  l'aggiornamento combinato di dettagli e capitoli delle estensioni 1.6.
  L'apertura di un titolo non invia più richieste ai vecchi endpoint separati.
- Le estensioni meno recenti continuano a funzionare tramite il contratto
  precedente. Il modello e i metodi delle nuove estensioni sono disponibili
  nell'API generica dell'app, senza logica legata a una fonte.

### Prestazioni

- Le richieste simultanee per lo stesso manga vengono coordinate, evitando
  aggiornamenti concorrenti tra scheda, Home e tracking.

## 27 settembre 2026 — Home Manga e librerie unite

### Novità

- Manga entra nella Home moderna accanto alle categorie video, con sezioni, ricerca
  nel catalogo completo e filtri forniti dalle estensioni installate. I risultati
  di fonti diverse si uniscono soltanto quando condividono ID pubblici concordi;
  i titoli ambigui restano separati e la fonte predefinita si può cambiare.
- La barra inferiore riunisce la libreria anime e la biblioteca manga in
  «Biblioteca & Libreria». La vecchia interfaccia manga viene disattivata.
- Una preferenza nasconde dalla Home Manga i cataloghi non italiani, senza
  rimuovere titoli già presenti nella biblioteca personale.

### Correzioni

- Il tracking manga continua ad avviarsi dalla lettura del primo capitolo;
  i tentativi simultanei sullo stesso titolo vengono serializzati per evitare
  associazioni duplicate.
- L'interfaccia generica delle estensioni manga ora conserva i metadati
  temporanei richiesti dai client più recenti per caricare titoli e capitoli.

## 27 settembre 2026 — Prossima uscita nella scheda del titolo

### Novità

- Il tempo che manca al prossimo episodio compare anche sotto lo studio,
  prima dello stato e della fonte. Usa lo stesso conto alla rovescia della lista
  episodi e rispetta la preferenza che ne controlla la visibilità.

### Correzioni

- Un refresh conserva la data nota se il tracker non è ancora disponibile o la
  richiesta alla rete fallisce. La aggiorna soltanto dopo una risposta valida.

## 27 settembre 2026 — Prossima uscita degli episodi

### Novità

- La previsione del prossimo episodio è ora una scheda discreta nella lista:
  mette in primo piano data e ora locali, seguite dal tempo rimanente; il numero
  e il titolo dell'episodio restano leggibili sotto, senza la vecchia scritta rossa.
  Il tempo si aggiorna quando cambia il minuto e la scheda si adatta agli
  schermi stretti e alle griglie della UI moderna e legacy.

## 27 settembre 2026 — Home Anime con più fonti

### Novità

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

### Correzioni

- La barra dei generi mostra di nuovo tutte le categorie disponibili. Le due
  scelte casuali sono raccolte in Esplora, fuori dalla barra dei generi.
- Uscendo dal player dopo l'apertura diretta di un episodio, la scheda conserva
  lo stato dell'avvio e non rilancia una seconda volta il player.

## 27 settembre 2026 — Tracking dei titoli nella libreria

### Novità

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

### Novità

- Aprendo una scheda anime, l'elenco degli episodi salvato viene mostrato subito e
  controllato in sottofondo con la sua estensione, senza dover trascinare per aggiornare.
- Nella Home i titoli visti di recente vengono controllati a rotazione, compresi
  quelli che aspettano un episodio per riapparire in «Continua a guardare».
  Nessun titolo viene escluso perché la lista è lunga; quelli nascosti non
  partecipano. I titoli mai controllati o più arretrati passano per primi;
  a parità di attesa hanno precedenza le serie in corso. Limiti persistenti
  per titolo e per fonte evitano raffiche di
  richieste anche dopo il riavvio dell'app.

### Prestazioni

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

### Novità

- In Impostazioni → Tracking, «Riesamina i titoli iniziati» riprova anime e manga
  guardati o letti che non sono ancora collegati ai servizi configurati. Si può
  avviare anche dopo il recupero iniziale e con il tracking automatico spento.
- I collegamenti già presenti restano intatti. La schermata mostra l'avanzamento,
  i nuovi collegamenti e se il controllo è stato interrotto.

## 26 settembre 2026 — Riproduzione in finestra

### Correzioni

- Il video continua a riprodursi quando si passa alla modalità picture-in-picture:
  la pausa avviene solo quando il player esce davvero dallo schermo.
- Corretto il ridimensionamento che poteva lasciare metà finestra nera al primo
  ingresso in picture-in-picture, specialmente uscendo dal player orizzontale.

## 25 settembre 2026 — Schermata Novità

### Novità

- Filtri e stato vuoto hanno una presentazione più chiara, con conteggi separati
  per i titoli ancora da vedere o leggere e per tutti gli episodi o capitoli.

### Correzioni

- Lo scorrimento orizzontale tra Anime e Manga resta continuo: il tema della
  pagina manga non ricrea più l'intera schermata a metà gesto.

## 25 settembre 2026 — Installazione degli aggiornamenti

### Novità

- Quando il download OTA termina, un avviso nell'app offre «Installa ora» e
  «Non ora». L'APK pronto resta accessibile in Altro anche dopo aver lasciato
  la schermata delle novità; l'avviso scompare quando la versione è installata.

## 25 settembre 2026 — Novità nella Home

### Novità

- Un indicatore circolare appare nella Home anime o manga soltanto per novità non
  ancora viste. Ha il colore delle altre icone della barra superiore. Toccandolo,
  la pagina scorre fino alla sezione «Le tue novità».
- Per impostazione iniziale l'indicatore si spegne anche quando la sezione entra
  nello schermo scorrendo a mano; questa scelta si può disattivare nelle
  impostazioni Libreria. I titoli restano nella sezione finché non vengono
  aperti o ignorati; le novità arrivate dopo riattivano l'indicatore.

## 25 settembre 2026 — Recupero iniziale e sigle personalizzate

### Novità

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

### Novità

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

### Novità

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

### Novità

- L'owner può attivare nelle opzioni della stanza il precaricamento prima del
  primo avvio di ogni episodio. Ogni telefono carica in parallelo fino a 15
  secondi di video; se la stima non è disponibile, usa un margine breve.
  L'attesa massima per il precaricamento è 15 secondi.

### Correzioni

- Le piccole differenze di durata riportate da manifest e qualità diverse dello
  stesso episodio non bloccano più la stanza. Durante l'apertura, una durata
  ancora sconosciuta viene mostrata come preparazione e non come video diverso.

## 24 settembre 2026 — Pulizia dei dati disattivati

### Correzioni

- La pulizia dei vecchi dati di profili e sincronizzazione termina correttamente
  anche nell'APK ottimizzato, senza un avviso a ogni avvio.

## 23 settembre 2026 — Stanze con relay verificati

### Novità

- Le nuove stanze tollerano fino a 5 secondi di caricamento dell'ospite
  prima di fermare anche chi ospita; chi era rimasto indietro si riallinea quando
  torna pronto. La pausa immediata per tutti resta disponibile nelle opzioni.

### Correzioni

- Le stanze usano due relay che hanno superato una prova di scambio cifrato in
  entrambe le direzioni, senza account. I precedenti rifiutavano gli invii.
- La connessione viene mostrata come pronta solo dopo che il relay accetta un
  messaggio di prova; rifiuti e limiti di frequenza non fanno più lampeggiare lo
  stato della stanza come se fosse disponibile.
- Un singolo aggiornamento di presenza perso non ferma più entrambi i video;
  con l'opzione di attesa attiva, il buffering reale continua a mettere in pausa la stanza.

### Prestazioni

- Una stanza vuota non invia più aggiornamenti periodici inutili e le richieste
  di ingresso ripetute sono meno frequenti.

## 23 settembre 2026 — Inviti brevi coerenti in tutta la stanza

### Compatibilità

- I link completi delle versioni precedenti continuano ad aprirsi.

### Novità

- Link, messaggio condiviso e QR del creatore ora contengono il codice stanza a
  otto cifre, anche quando si invita dal lettore manga. L'amico chiede di entrare
  e il creatore conferma l'accesso.
- La schermata delle stanze mette in primo piano Crea/Entra, codice e richieste;
  il nome facoltativo resta modificabile senza affollare il percorso iniziale.

## 23 settembre 2026 — Accesso alle stanze con codice breve

### Novità

- Per invitare un amico in una stanza video o manga ora bastano otto cifre temporanee.
  Chi crea la stanza vede la richiesta e può accettarla o rifiutarla.
- L'ingresso mostra lo stato del collegamento; gli inviti completi già condivisi
  continuano a funzionare.

## 23 settembre 2026 — Rimozione della traduzione manga sperimentale

### Novità

- Rimossi pulsanti, impostazioni e componenti della traduzione manga offline.
- Dopo l'aggiornamento, l'app elimina i modelli e la cache della traduzione scaricati
  in precedenza, senza alterare manga, progressi o annotazioni delle stanze.

## 23 settembre 2026 — Annotazioni condivise più affidabili

### Novità

- Ogni partecipante può nascondere e ripristinare i propri interventi per tutti;
  il creatore può moderare quelli della stanza. La visibilità locale resta separata.
- Dopo una chiusura imprevista, la schermata offre il rientro esplicito nella stanza
  con le annotazioni temporanee salvate in forma cifrata sul dispositivo.
- La stanza mostra temporaneamente a che punto del video stanno guardando gli altri.

### Correzioni

- Nella stessa stanza video e manga, schizzi e note sulla pagina si riallineano
  dopo disconnessioni e messaggi fuori ordine; gli interventi nascosti non ricompaiono.

## 23 settembre 2026 — Pressione prolungata nel player personalizzabile

### Novità

- Nelle impostazioni dei gesti puoi scegliere cosa accade tenendo premuto sul video:
  velocità temporanea 2× (predefinita), 1,5× o 1,25×, cattura schermata oppure
  nessuna azione.
- Rilasciando il dito viene ripristinata la velocità che avevi scelto prima del
  gesto; la velocità temporanea resta disabilitata nelle stanze.

## 23 settembre 2026 — Backup trasferibili e note OTA per versione

### Novità

- I nuovi backup manuali e automatici usano `.nyabk`; il ripristino continua ad
  accettare i vecchi file `.tachibk` e il formato interno resta compatibile.
- Esporta dati offre un backup completo di librerie, progressi e segnalibri di
  visione e lettura anche fuori dalla libreria, cronologia, impostazioni, preferenze delle
  fonti ed estensioni. Video e pagine scaricati restano esclusi.
- Le note di aggiornamento OTA mostrano soltanto le novità aggiunte dal tag
  precedente, senza riproporre ogni volta l'intera sezione del changelog.

### Correzioni

- Sul nuovo dispositivo vengono ripristinate anche le preferenze non ancora
  inizializzate; categorie, impostazioni e titoli sono ripristinati nell'ordine
  corretto. Le categorie anime e manga sono mappate separatamente e le stagioni
  recuperano episodi e progressi, anche quando non erano state aggiunte
  singolarmente alla libreria.
- I titoli nascosti da “Continua a guardare” sono ricollegati sul nuovo telefono
  tramite riferimento alla fonte e al titolo, senza copiare ID del database.
- Le preferenze che non si riescono a ripristinare compaiono nel resoconto degli
  errori invece di essere ignorate senza avviso.

## 23 settembre 2026 — Licenze, aiuto e aggiornamenti OTA

### Compatibilità

- Gli APK delle nuove release mantengono nomi riconosciuti anche dalle versioni
  già installate, per consentire l'aggiornamento OTA diretto.

### Novità

- Il pulsante Aiuto e le guide collegate aprono la documentazione Nyanime.
- La schermata di aggiornamento mostra le novità di questa release, prese dal
  changelog, anziché istruzioni tecniche per scegliere un APK.

### Correzioni

- La testata Info ora mostra il marchio Nyanime senza lo spazio vuoto sopra l'icona.
- Nella Home moderna, logo, Stanze e Cerca restano sulla stessa riga anche sugli
  schermi stretti; il pulsante Cerca mantiene la sua forma a pillola.
- La pagina delle licenze delle librerie usa un elenco compatibile con la versione
  Compose dell'app, con ricerca e dettaglio della licenza, evitando il crash.

## 23 settembre 2026 — Link Nyanime e aggiornamenti OTA

### Novità

- Le release preview firmate vengono pubblicate automaticamente da `main` con APK
  per ogni architettura, riattivando la verifica degli aggiornamenti OTA.
- I pulsanti Sito web, Discord e GitHub nella schermata Info mostrano un avviso
  finché i relativi canali non saranno disponibili; rimossa la voce per tradurre.
- Licenze e privacy aprono i documenti di questo repository.
- Supporto alle estensioni video con versione 17 della libreria, conservando le
  versioni precedenti e gli identificatori tecnici necessari alla compatibilità.

## 22 settembre 2026 — Sync disattivato e avvio Ultra più accessibile

### Novità

- Sync personale rimosso dalle impostazioni e disattivato anche sulle installazioni
  già configurate. Pulizia automatica di chiavi, archivi privati e code di sync/community;
  librerie, progressi locali, download e stanze restano disponibili.
- Ultra può elaborare a schermo acceso e a batteria; le due restrizioni diventano
  facoltative. Soglie termiche meno prudenti, carico GPU ancora dosato e attese
  ricontrollate senza accumulare ritardi crescenti. Aggiornamento delle code esistenti.

### Prestazioni

- Codice social e sync inutilizzato escluso dall’APK ottimizzato.

## r8200 — Guarda insieme e controlli del player

Riferimenti: `bc660e1b0`, `7ee9e9a4f`, `51d484a1e`, `83115106a`.

### Novità

- Play con risposta immediata, animazione play/cuore/cerchio e conto alla rovescia
  integrato nel video.
- Iniziali e stato dei partecipanti, indicazione di chi sta ancora caricando
  e messaggi brevi sulle azioni manuali.
- Barra della stanza nella navigazione e nei dettagli, con episodio, partecipanti
  e ritorno al player.
- Caricamento individuale, inviti al salto e scheda del prossimo episodio più compatti,
  conservando legacy, riduzione del movimento e timer.
- Anime4K disabilitato per l'intera stanza, con ripristino delle scelte individuali
  all'uscita.

### Correzioni

- Scadenza dei comandi non confermati, nuovi tentativi controllati e riconnessione
  senza riapplicare richieste di episodi precedenti.
- Protezioni del ciclo di vita nativo durante apertura e chiusura, anche senza stanze.

Per questa revisione: 411 test automatici superati, quattro facoltativi saltati,
85 render Compose generati, build preview e controlli di firma/allineamento completati.
Non è stata eseguita una nuova prova con due telefoni su questa revisione.

## Stanze cifrate e sincronizzazione

Riferimenti: `2ec1bd009`, `51ba63dfb`, `6019330d5`.

### Novità

- Creazione e ingresso tramite codice, link e QR, fino a otto partecipanti.
- Trasporto cifrato su relay Nostr, senza account Nyanime o apertura di porte.
- Episodio scelto dall'host e apertura automatica attraverso l'estensione locale.
- Play, pausa, seek e velocità condivisi, attesa della disponibilità,
  correzioni temporali graduali e recupero dopo perdita della connessione.
- Protezioni per timer, focus audio e background; salti e prossimo episodio coordinati.

## Home manga delle estensioni

Riferimento: `10fb2a381`.

### Compatibilità

- Lettore, tema manga, librerie e avanzamento mantenuti.

### Novità

- Home opzionale accanto alla biblioteca, basata sulle capacità dell'estensione.
- Aggiornamenti, classifiche, metadati di presentazione e azioni dei capitoli.
- Apertura del capitolo esatto e “Continua a leggere”.

## ModernUI, navigazione e strumenti del player

Riferimenti: `27b30551a`, `f4455e11c`, `a7364253d`, `f5c1210ca`,
`73a899d03`, `9575ea5b3`, `f70647847`, `d91acfa62`, `b1f9a8e69`.

### Novità

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

### Novità

- Google Cast e UPnP/DLNA, scoperta sulla rete locale e relay dei contenuti supportati.
- Telecomando, volume, avanzamento, coda episodi, notifica e navigazione durante il Cast.
- Cambio del video e ritorno alla riproduzione locale con posizione.
- Luminosità sui ricevitori DLNA che espongono il controllo; limiti documentati
  per codec, sottotitoli, DASH e shader locali.

## Scoperta, Anime4K e affidabilità

Il lavoro precedente comprende:

### Novità

- Catalogo video, associazione automatica a titolo e stagione, fallback Kitsu,
  calendario e Home dichiarative delle estensioni.
- “Continua a guardare” indipendente dai feed remoti e gestione dei titoli nascosti.
- Anime4K integrato in mpv, Smart con misure del rendering, calibrazione,
  telemetria limitata e verifica degli shader.
- Download HTTP riprendibili quando validabili, controllo dello spazio,
  recupero dei link e protezioni dei backup.

### Correzioni

- Aggiornamenti delle estensioni deduplicati, scelta corretta degli allegati APK.
- Correzioni PiP/JNI, demuxer HLS per ripresa e seek, accessibilità con testo grande,
  verifiche di rendering e strumenti di misura delle prestazioni.

### Prestazioni

- Ricerca globale con annullamento e concorrenza limitata, cache e retry per sezione.

Dettagli e vincoli sono nelle [guide tecniche](docs/README.md).

## Storico upstream

Il precedente changelog AniYomi è conservato integralmente in
[docs/history/aniyomi-changelog.md](docs/history/aniyomi-changelog.md).
La sua sezione “Unreleased” appartiene allo storico upstream importato:
non è una roadmap o una dichiarazione di release di Nyanime.
