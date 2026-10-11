# Community e sincronizzazione personale

## Stato: dormiente

La community è disattivata in tutte le varianti dell’app. Profili, amicizie,
bacheche, feed, chat, gruppi sociali e presenza non sono accessibili. Non vengono
aperte connessioni sociali. L’aggiornamento rimuove dal telefono le identità e le
relative chiavi Keystore, database privati (inclusi journal/WAL), bozze immagini,
notifiche e canali obsoleti. Libreria, download, progressi locali e stanze sono preservati.
Anche il [sync personale](personal-sync.md) è dormiente, compresi i dispositivi
configurati in precedenza; non compare nelle impostazioni.

Le stanze Guarda e leggi insieme restano attive e indipendenti: usano un codice
o link d’invito e chiavi temporanee, senza richiedere un profilo.

Il codice seguente è mantenuto per una futura riattivazione. Il gate
`communityEnabled` in `app/build.gradle.kts` controlla `BuildConfig.COMMUNITY_ENABLED`;
`personalSyncEnabled` controlla separatamente il sync personale.
I componenti Android della community sono rimossi dal manifest per consentire
alla build ottimizzata di eliminare il codice non usato dall’APK.
Con entrambi disattivati, a ogni apertura dei database la cattura delle modifiche
viene spenta prima delle scritture della libreria, senza leggere identità o preferenze del sync.
I trigger di raccolta, le code e le associazioni di categoria del solo sync vengono rimossi.
Le tabelle di schema restano vuote per compatibilità con le versioni del database installate.
La pulizia di file e chiavi è ripetibile e i fallimenti vengono registrati e ritentati al prossimo avvio.
Una futura riattivazione richiederà nuove identità, migrazioni di ripristino della raccolta
e la registrazione dei componenti Android, oltre alle verifiche del protocollo.

## Profili e contenuti pubblici

Il profilo comprende nome, biografia, avatar, copertina, accento e tre preferiti
ordinabili. Le liste pubbliche distinguono video e manga e cinque stati.
La libreria privata non viene pubblicata automaticamente: l’editor consente di
scegliere i titoli e mostra un’anteprima prima della pubblicazione.

Feed Amici ed Esplora sono separati. Post, consigli, immagini, risposte, reazioni
e sticker originali sono pubblici; gli spoiler richiedono un’apertura esplicita.
La bacheca è inizialmente riservata agli amici. Il proprietario può aprirla,
chiuderla, fissare o nascondere messaggi e bloccare profili. L’ammissione dei
messaggi in bacheca è firmata dal proprietario, non dichiarata dal visitatore.

Le amicizie richiedono richiesta e accettazione: due richieste incrociate o un
follow Nostr non bastano. Codice e QR identificano una chiave precisa. La ricerca
per nome usa cache e relay compatibili e non costituisce un elenco completo
della rete. L’elenco amici è privato.

Chat e gruppi usano cifratura per destinatario. I gruppi ammettono fino a dieci
persone, con invito esplicito, proprietario, nome, immagine, gestione membri,
uscita e silenziamento. Ogni messaggio di gruppo indica la revisione e l’hash
dell’elenco membri firmato dal proprietario. La cronologia precedente non viene
reinviata ai nuovi membri. Gli inviti alle stanze scadono.

La presenza è disattivata inizialmente. Può essere condivisa con gli amici o
pubblicamente e scade dopo 90 secondi. La cronologia dettagliata rimane privata.
Il player e il lettore comunicano l’attività aperta tramite dati già disponibili:
il recupero della libreria non genera una falsa attività. Questo funziona anche
con sincronizzazione personale disabilitata. Il payload opzionale `nyanime-activity`
contiene solamente titolo, episodio/capitolo, tipo e un token casuale temporaneo.

Dalla scheda di un amico che guarda un video si può proporre **Guarda insieme**.
`watch.request` è un comando cifrato con scadenza di tre minuti. Solo il
destinatario può accettare l’esatto contenuto richiesto; la sua accettazione
apre una stanza e l’episodio locale. `watch.response` restituisce l’invito nella
chat, dove il richiedente entra con un tocco. Rifiuto, annullamento, scadenza,
episodio cambiato, amicizia rimossa, Cast o un’altra stanza non causano un ingresso
automatico. Per i manga è disponibile la chat; le stanze rimangono per i video.

L’editor del profilo separa Aspetto, Top 3, Liste e Bacheca. Il selettore visuale
mostra libreria e titoli aperti di recente; nessuno di essi diventa pubblico senza
selezione e anteprima. Le foto in bozza sono file locali finché non si pubblica.
Chat, richieste di amicizia e inviti hanno notifiche private e rispettano il
silenziamento. La ricezione fuori dall’app richiede la connessione continua
facoltativa e l’autorizzazione alle notifiche di Android.

## Identità e dispositivi

La chiave secp256k1 è generata sul dispositivo e conservata cifrata tramite
Android Keystore. Non servono email o password di accesso. Il codice pubblico
`NYU1` contiene solamente la chiave pubblica e un checksum.

Il nuovo dispositivo genera un codice temporaneo `NYD1` con chiave effimera,
nonce, relay e scadenza di tre minuti. Entrambi i dispositivi devono confermare
lo stesso codice di verifica; soltanto allora la chiave permanente viene
trasferita in un involucro cifrato monouso. Non occorre collegare entrambi i
telefoni via USB: il collegamento usa Internet.

Il file di recupero `NYR1` è protetto da password con PBKDF2-HMAC-SHA256
(600.000 iterazioni, sale casuale) e AES-GCM. File e password vanno conservati.
Una chiave già copiata su un altro dispositivo non può essere revocata
crittograficamente con un semplice comando di disconnessione.

## Eventi e versionamento

| Tipo | Scopo |
| --- | --- |
| 0 | Nome, bio e immagini del profilo secondo i metadati Nostr |
| 1 / 7 | Post pubblici, risposte e reazioni |
| 10002 / 10050 | Relay del profilo e casella privata |
| 30315, `d=general` | Presenza pubblica con scadenza |
| 30078, `nyanime.profile.v1` | Vetrina, liste e impostazioni pubbliche della bacheca |
| 30078, `nyanime.wall.admit.v1:…` | Ammissione di un intervento da parte del proprietario |
| 30078, `nyanime.pinned.v1` / `nyanime.hidden.v1` | Moderazione firmata della bacheca |
| 1059 → 13 → 14 | Involucro NIP-59, sigillo e messaggio privato NIP-17 |
| 1059 → 13 → 30079 | Comandi applicativi privati versionati |
| 30078, `nyanime.sync.v1:…` | Record personale cifrato con NIP-44 |
| 30078, `nyanime.sync.index.v1:…` / `nyanime.sync.checkpoint.v1:…` | Indice di recupero cifrato |

I comandi applicativi comprendono `friend.*`, `group.*`, `preference`,
`presence`, `watch.invite`, `watch.request`, `watch.response`, `pair.*` e `device.*`. Non sono comandi di
controllo delle stanze. I gruppi sono un protocollo Nyanime su NIP-59, non gruppi
pubblici NIP-29.

Gli eventi ricevuti sono limitati in dimensione e verificati per ID, firma,
autore e destinatario prima di essere applicati. Invii persistenti, ricevute dei
relay, deduplicazione e riconnessione sono separati dalla UI. Una ricevuta indica
accettazione da parte del relay, non lettura del messaggio da parte dell’amico.
La coda ritenta anche gli invii successivi quando un relay non conferma i primi.
Gli invii vengono selezionati per relay e priorità. Conferme e tentativi sono
persistenti; un relay limitato rallenta il proprio ritmo senza bloccare gli altri.
L’interfaccia distingue gli aggiornamenti senza conferme dalle copie aggiuntive
in attesa. Gli errori di autenticazione della casella privata restano visibili
anche quando il medesimo relay accetta altri eventi. Le richieste profilo usano
identificatori inferiori al limite NIP-01 di 64 caratteri.

## Sincronizzazione privata

Le transazioni ordinarie delle librerie alimentano un registro SQLite nello
stesso commit locale. Il player continua a salvare nel database esistente:
crittografia, rete e recupero lavorano su coroutine I/O indipendenti.

I riferimenti comprendono tipo di contenuto, ID stabile della fonte, URL del
titolo e URL dell’episodio/capitolo, solamente nel testo cifrato. Gli ID numerici
del database non viaggiano tra dispositivi. Gli indirizzi pubblici dei record
usano HMAC con una chiave privata, non hash indovinabili dei titoli o degli URL.

Progressi, completamento, segnalibri, cronologia e appartenenza alla libreria
hanno revisioni indipendenti. Riavvolgere è una modifica valida; il secondo più
alto raggiunto non prevale automaticamente. Revisioni ibride e ID del dispositivo
ordinano i conflitti; i marcatori di rimozione impediscono il ritorno di vecchie
categorie eliminate. Le categorie hanno identità indipendenti dagli ID SQLite.

L’indice di recupero contiene pagine cifrate indirizzate per contenuto, con un
massimo di 200 riferimenti per nodo. Consente richieste mirate dei record anche
quando un relay tronca una pagina generica di cronologia. Il recupero incompleto
rimane visibile; i relay restano servizi esterni con proprie politiche di
conservazione e disponibilità.

Una fonte mancante o una corrispondenza ambigua rimane in attesa. Dalle
impostazioni Community si sceglie esplicitamente il titolo e, quando necessario,
l’episodio o capitolo nella libreria locale. La corrispondenza non viene dedotta
dal primo risultato di ricerca.

Sono esclusi download, file Ultra, credenziali delle estensioni, cookie, URL di
streaming e impostazioni hardware. Incognito non produce progresso sincronizzato
o presenza. I ripristini manuali dei backup vengono raccolti in blocco senza
generare post o pubblicazioni della libreria.

## Trasferimento della visione

Un video deve essere pronto prima di iniziare il trasferimento. B propone il
passaggio, A risponde senza fermarsi, B conferma; A mette in pausa e conferma
il punto aggiornato, poi B riprende. Comandi e presenze dei dispositivi scadono;
le conferme duplicate non producono altre pause.

Gli aggiornamenti ordinari del database non invocano il player. Cast, stanze,
incognito, caricamento e player in chiusura escludono l’adattatore. Se il
trasferimento scade, B indica che sta usando il progresso locale disponibile.
Una risposta tardiva non deve riattivare il player dopo l’uscita.

L’app si connette quando è aperta. La connessione continua in background è
facoltativa e usa una notifica Android; non è necessaria per usare l’app.

## Immagini e limiti di privacy

Le immagini scelte vengono ridimensionate e ricodificate in JPEG, senza metadati
fotografici, poi inviate a un host Blossom con autorizzazione firmata. Vengono
verificati descrittore e hash dei byte scaricati. I fallimenti conservano la
bozza e l’app prova un host alternativo. L’autorizzazione usa Base64URL secondo
BUD-11, con un solo tentativo compatibile per host che segnalano esplicitamente
un decodificatore Base64 precedente. Nessuna autorizzazione viene inoltrata ai
reindirizzamenti della verifica pubblica. Le copertine delle fonti
vengono preparate tramite il caricatore dell’app prima della pubblicazione:
URL e credenziali della fonte non entrano nelle schede pubbliche.

Un’immagine su Blossom è un file accessibile a chi ne conosce l’indirizzo;
la cifratura delle chat non rende privati i byte di un’immagine pubblicata.
Nascondere un contenuto nell’app non garantisce la cancellazione delle copie
conservate da altri relay o utenti.

## Verifiche

- Test crittografici con vettori NIP-44 indipendenti, autenticazione degli
  involucri, destinatario errato e payload alterati.
- Convergenza di amicizie, revisioni, riavvolgimenti, segnalibri e rimozioni.
- Elenchi membri esatti e recupero di un indice con oltre 40.000 record.
- Relay TLS locale con autenticazione, ricevute, richieste simultanee,
  eventi alterati e recupero paginato.
- Trigger SQLite reali per video e manga: rollback, cronologia, categorie,
  importazioni e assenza di repliche delle scritture remote.
- Test dell’adattatore di trasferimento e anteprime grafiche senza emulatori.

Le prove Android su due dispositivi e le misure dei target di 2 secondi per gli
aggiornamenti finali e 5 secondi per il progresso continuo devono essere
registrate separatamente: i test sul PC non dimostrano questi tempi reali.

Riferimenti: [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md),
[NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md),
[NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md),
[Blossom](https://github.com/hzrd149/blossom).
