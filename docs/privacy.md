# Privacy di Nyanime

Nyanime è un'app Android che gestisce localmente librerie, progressi di visione e lettura, preferenze e download. Non richiede un account Nyanime e non include profili social. Backup e sincronizzazione tramite Cloud rimangono facoltativi e separati dalle stanze.

## Dati sul dispositivo

Il pannello di tracking permette di conservare stati, voti, note, tag, ripetizioni
e progresso anche senza account. Questi dati personali sono locali e possono
essere inclusi nel backup selezionando libreria e tracking. Le modifiche destinate
a un tracker collegato sono conservate sul dispositivo finché non vengono
sincronizzate con quel servizio. Il collegamento di un account non trasferisce
automaticamente le note o il record locale.

La libreria, la cronologia, i punti di ripresa, le impostazioni, i file scaricati e i dati delle estensioni restano nell'archivio dell'app sul dispositivo. Backup ed esportazioni vengono creati solo quando li richiedi e sono sotto il tuo controllo. Il backup completo `.nyabk` può includere credenziali e token delle impostazioni selezionate: non è cifrato e va conservato in privato. Non include video o pagine scaricati. Disinstallare l'app o cancellarne i dati può eliminare queste informazioni; i file esportati separatamente seguono le regole della posizione in cui li salvi.

## Connessioni facoltative

Nyanime contatta servizi esterni quando usi le relative funzioni: cataloghi e tracker configurati, fonti delle estensioni installate, immagini e contenuti multimediali, ricevitore Cast o TV companion e GitHub per controllare gli aggiornamenti. Tali servizi possono vedere i dati tecnici della richiesta, incluso l'indirizzo IP, secondo le proprie informative. Le estensioni sono componenti separati e possono avere cookie e comportamenti propri.

Le stanze Guarda/Leggi insieme usano relay Nostr. Un codice o invito consente ai partecipanti di coordinare la sessione; gli eventi necessari alla stanza passano attraverso i relay scelti. Non condividere il codice con persone che non vuoi far entrare nella stanza. Le stanze non pubblicano automaticamente la cronologia della libreria.

La chat delle stanze è cifrata anche fra i singoli destinatari ammessi: conserva
al massimo 200 messaggi in memoria, esclusi dai backup e rimossi quando esci.
Non viene pubblicata una cronologia sociale. I relay possono osservare indirizzi
IP, identificatori temporanei, orari e dimensioni degli eventi, senza leggerne il
contenuto. L’espulsione impedisce di leggere i nuovi messaggi fra i partecipanti
rimasti, senza poter cancellare copie già ricevute o avvisare immediatamente un
dispositivo offline.

La voce è facoltativa, usa WebRTC e si collega soltanto dopo «Entra in voce».
Il microfono parte spento e il permesso viene richiesto solo quando lo attivi.
I partecipanti vocali e i servizi STUN gratuiti possono conoscere il tuo indirizzo
IP. La voce è trasportata cifrata direttamente fra i dispositivi, senza registrazione,
videocamera o condivisione dello schermo. In background resta la notifica Android
con i comandi per silenziare o uscire. Alcune reti impediscono il collegamento
diretto; in quel caso la voce non è disponibile, mentre chat e coordinamento
possono continuare. Ogni partecipante carica autonomamente il proprio video.
Ulteriori dettagli sono nella [guida alle stanze](watch-together.md).

L'approfondimento facoltativo delle schede contatta AniList, TVmaze e Wikidata
soltanto dopo l'attivazione. I cataloghi ricevono titoli e identificatori pubblici,
senza URL delle fonti, cookie, credenziali o progressi personali. Le informazioni
pubbliche consultate possono essere conservate in una cache locale ricostruibile;
in incognito non si registrano nuovi dati o associazioni persistenti. Il consenso
e le associazioni manuali sono inclusi nei backup delle impostazioni.

La distribuzione pubblica dei voti nel pannello di tracking riusa il consenso alle
informazioni sulle opere: AniList riceve un ID di catalogo verificato, senza
progresso, note o riferimenti alle fonti. Mancando consenso o dati verificati,
il voto personale resta disponibile senza statistiche inventate.

## Backup Cloud facoltativi

Se colleghi Telegram da **Nyanime Cloud**, Nyanime usa TDLib e un canale privato
riservato al tuo account per conservare backup completi `.nyabk`. Il backup può
contenere progressi, cronologia, preferenze private e credenziali già incluse
nell’esportazione completa. Video e pagine scaricati, sessioni e chiavi Telegram
sono esclusi. La cifratura è quella Cloud di Telegram, **non end-to-end**; non viene
aggiunta una password al file.

I salvataggi automatici partono soltanto dopo la configurazione esplicita. Puoi
disattivarli, proteggere copie, eliminare singoli backup o scollegare la sessione.
Nyanime non importa la rubrica e non rende pubblico un profilo per il collegamento
Cloud. Cache e sessione Telegram sono locali e separate dai backup; lo scollegamento
revoca la sessione e rimuove questi dati dopo la chiusura del motore. I backup già
salvati su Telegram rimangono nel tuo archivio. Vedi [guida Cloud](telegram-cloud.md)
e [privacy di Telegram](https://telegram.org/privacy).

## Dati non sincronizzati

Le estensioni Notizie abilitate contattano i rispettivi editori. Preferenze,
lettura e collegamenti personali restano sul dispositivo. Per riconoscere
adattamenti e seguiti, la sezione Notizie può interrogare AniList usando soltanto
ID di catalogo, senza progressi, credenziali o cronologia. Il controllo periodico
degli articoli parte solo se si attivano gli avvisi.

Il precedente sistema di profili pubblici, amicizie, chat social e sincronizzazione personale su Nostr è stato rimosso. L’app elimina gli eventuali archivi, bozze e chiavi locali appartenenti soltanto a quelle funzioni. Le stanze e il Cloud Telegram conservano i propri dati indipendenti. Download e copie Ultra restano locali.

## Studio, scambio e backup remoti facoltativi

Lo Studio conserva definizioni e bozze sul dispositivo. Le prove di rete partono
su richiesta verso gli indirizzi della fonte scelta; script e cookie sono soggetti
ai permessi e ai limiti locali. Importare una definizione non autorizza i suoi script.
Lo scambio fonti è temporaneo ed esplicito: destinatario, codice o QR, anteprima e
importazione separata. La scoperta locale e i relay Internet possono conoscere
indirizzi di rete e tempi della connessione; non è un catalogo pubblico gestito dall’app.

WebDAV e Google Drive ricevono soltanto i backup configurati volontariamente.
Il contenuto viene cifrato prima dell’invio con una chiave di recupero da conservare;
nome, dimensione e data del file restano metadati visibili al gestore dell’archivio.
Drive richiede un client OAuth Nyanime proprio e il consenso del relativo account.
Le credenziali operative WebDAV/Drive e la chiave remota restano escluse dal backup;
il contenuto completo può invece includere altre credenziali portabili già previste. Le copie locali e
Telegram conservano i comportamenti descritti nelle rispettive guide.

## Attenzione alla visione facoltativa

La fotocamera frontale viene usata soltanto quando la funzione è abilitata e il
contesto di riproduzione lo consente. Le immagini sono analizzate localmente e
rilasciate, senza registrazione, invio remoto, riconoscimento dell’identità o
salvataggio di misure del volto. Fotocamera e modello non si avviano con la funzione
spenta. Le statistiche upstream di MediaPipe sono sostituite con una funzione locale.

«Solo rilevamento» può conservare intervalli temporali stimati sul video, senza
pause automatiche. Questi dati sono locali, cancellabili separatamente e inclusi
nel backup delle impostazioni; in incognito non vengono persistiti. La modalità
pausa automatica non raccoglie gli intervalli. Si tratta di stime sperimentali,
non di una misura certa dell’attenzione. [Controlli e limiti](viewing-attention.md).

## Compatibilità Syncplay

Il server scelto dall’utente riceve nickname, stanza, nome/durata del video e
stato condiviso. Non riceve URL, cookie o credenziali della fonte. La sessione
richiede consenso; TLS è la scelta iniziale e non viene degradato automaticamente.
Un server Syncplay non offre la riservatezza delle stanze cifrate Nyanime.
[Dettagli](syncplay-compatibility.md).

## Controlli e contatti

Puoi disattivare funzioni facoltative nelle impostazioni, rimuovere download, cancellare dati dall'app o dal sistema Android e scollegare tracker esterni. Per domande o segnalazioni su questa distribuzione usa le [issue del repository Nyanime](https://github.com/owouwuiwi/nyanime/issues). Le librerie e i servizi di terzi hanno informative e licenze separate.

Questa pagina descrive il comportamento del codice Nyanime distribuito in questo repository; una build modificata o un'estensione di terzi può comportarsi diversamente.
