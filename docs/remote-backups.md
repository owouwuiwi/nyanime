# Backup remoti: WebDAV e Google Drive

> Versione ordinaria: integrazione 0.49.0.0. Le prove di build e dispositivo che citano NyanimeB descrivono esclusivamente quelle versioni storiche; le verifiche della build ordinaria sono riportate nella [migrazione](normal-migration.md).

Implementazione privata NyanimeB. Accesso: **Impostazioni → Dati e archiviazione → Backup → Backup remoti**.
Locali, periodici su cartella e Telegram mantengono i loro percorsi esistenti.
Questa funzione conserva snapshot completi; non è una sincronizzazione continua del journal.

## Confini del codice

`RemoteFileStore` separa i trasporti HTTP dall'integrità e dal protocollo di commit.
`RemoteBackupEngine` opera su quel contratto e sui file, senza conoscere la UI o gli
account Android. `RemoteBackupRepository` riusa creazione e validazione esistenti;
`RemoteBackupVault` custodisce la configurazione locale. WorkManager governa durata,
vincoli e tentativi; la schermata osserva il risultato e non implementa crittografia
o parsing di rete. I test sostituiscono i trasporti, senza usare account reali.

## Contenuto e recupero

Il file viene creato con `BackupCreator` e `BackupOptions.complete()`, verificato con
`BackupFileValidator`, cifrato e caricato. Libreria, avanzamento, cronologia, impostazioni,
credenziali portabili ed estensioni seguono esattamente il backup completo esistente.
Video e pagine scaricati sono esclusi. Le credenziali operative di WebDAV/Drive e la
chiave di recupero stanno separatamente in `noBackupFilesDir`, protette da Android Keystore.
Su un nuovo telefono si riconnette lo spazio e si incolla la stessa chiave.

La chiave `NYK1-…` contiene 256 bit casuali. Non è una password umana né una chiave
derivata dal nome dell'account. Va salvata fuori dal telefono prima del collegamento.
Senza chiave non è possibile recuperare il contenuto; non esiste un servizio di recupero.
Il formato remoto cifrato non va passato direttamente al vecchio decoder `.nyabk`:
**Anteprima e ripristino** scarica in privato, autentica, decifra, valida e apre
`CloudRestoreScreen`, che mantiene selezione del contenuto e conferma esplicita.

La cifratura riusa `SyncBackupCipher`: Tink Streaming AEAD AES-256-GCM/HKDF con
segmenti autenticati da 1 MiB, chiave per file protetta e dominio distinto dal cloud Telegram.
Limite del contenuto chiaro 2 GiB. La copia chiara temporanea non sopravvive al termine
di un tentativo; l'app deve avere spazio per snapshot, cifrato e verifica.

## Commit, errori e conservazione

- Nomi immutabili con UUID, distinti fra ogni esecuzione periodica e tra dispositivi.
- Prima si carica il payload cifrato, poi si riscarica e si confrontano dimensione e SHA-256.
- Solo dopo si pubblica un piccolo manifest autenticato HMAC-SHA256, riletto a sua volta.
- Gli elenchi mostrano esclusivamente manifest validi per la chiave inserita. Un errore
  di autenticazione al server non viene trasformato in un elenco vuoto.
- Un'interruzione prima del manifest lascia al massimo un payload non elencato. Non si
  cancella mai l'ultima copia valida per rimpiazzarla con un caricamento incompleto.
- WorkManager impedisce doppi avvii manuali per destinazione; un mutex coordina gli snapshot.
  Il journal locale conserva l'identità del tentativo. I retry riusano il cifrato già completo.
- Annullare il tentativo dalla schermata o dalla notifica conserva la pianificazione
  futura. L'azione privata attende l'annullamento WorkManager e ricontrolla la
  destinazione prima di pianificare, senza riattivare un collegamento rimosso.
  La sostituzione del lavoro periodico è atomica. I file del tentativo vengono
  rimossi in caso di annullamento esplicito; uno stop per vincoli di rete mantiene
  invece il cifrato riutilizzabile dal tentativo successivo.
- Conservazione 3/7/14/30 copie solo del dispositivo corrente, dopo aver trovato e autenticato
  il nuovo commit. Gli errori di pulizia non invalidano un backup appena verificato.
- Nessun ripristino automatico su dati locali potenzialmente più recenti. Nessuna deduzione
  di identità o associazione di contenuti attraverso i nomi dei titoli.

Il server può vedere dimensioni, tempi e nome del dispositivo. Non riceve chiavi in chiaro.
I vecchi payload non committati non vengono cancellati indiscriminatamente: rimangono
nello spazio dell'utente per evitare di cancellare caricamenti di altri dispositivi.

## WebDAV

URL HTTPS esplicito senza credenziali/query incorporate, credenziali separate e,
preferibilmente, password per app del proprio servizio. La cartella padre deve esistere;
Nyanime crea soltanto `nyanime-backups-v1/` con MKCOL.

PUT condizionale e oggetti immutabili, PROPFIND Depth 1 limitato, parsing XML senza DTD
o entità esterne. Nessun redirect HTTP, cookie di app o eccezione al certificato TLS.
Gli href fuori origine o fuori cartella vengono ignorati. Nessuna regola per singolo host.

## Google Drive: registrazione ancora da fare

L'integrazione usa Google Identity Services `AuthorizationClient`, senza client ID
upstream, client secret o refresh token conservati dall'app. Per i file lo scope è
`https://www.googleapis.com/auth/drive.appdata`; `userinfo.email` serve a verificare
l'indirizzo dell'account e vincolare i worker a quella destinazione, senza dipendere
da `GoogleSignInAccount` deprecato. I file stanno nell'`appDataFolder`,
riservato all'app e non visibile come normale cartella di My Drive.

Per questa build privata il proprietario non ha ancora registrato l'OAuth Android:
**Drive richiede configurazione e non ha ancora una prova reale di connessione**.
La guida nell'app copia pacchetto e SHA-1 effettivi della build installata:

1. Creare/selezionare un proprio progetto Google Cloud, abilitare Drive API.
2. Configurare Google Auth Platform (branding, contatti, pubblico e utenti di test).
3. Creare un client OAuth Android per quel pacchetto e quel certificato SHA-1.
4. Per Play App Signing registrare il certificato di firma Play, non soltanto l'upload key.
5. Aggiungere `drive.appdata` e `userinfo.email`, poi provare il consenso dalla build registrata.

La registrazione è un passaggio di sviluppo: non va chiesto a tutti gli utenti di
crearsi un progetto quando Nyanime disporrà della propria registrazione pubblicata.
Il consenso e l'eventuale selettore di account rimangono UI Google, senza ritagli del sito.
Un consenso necessario durante un worker interrompe il backup e richiede riconnessione;
non viene aperta una finestra in background e non viene usato un altro account in silenzio.

Upload resumable a porzioni da 1 MiB, Range confermato dal server, tentativi limitati e
sessione vincolata all'origine Google. Nessun token viene inviato a Location arbitrarie.

Riferimenti: [AuthorizationClient](https://developer.android.com/identity/authorization),
[appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata),
[upload resumable](https://developers.google.com/workspace/drive/api/guides/manage-uploads).

## Provenienza e verifiche

Esaminati Kototoro `f707f60fb7449a45dd654d908ebcdcb5f5556c5a` (WebDAV/Drive) e
Kazumi `4effe14ee78b790a92c23ed86f969093d2ed376d` (WebDAV). Implementazione nativa
indipendente, senza copiare runtime Dart, asset, credenziali o cataloghi. La sincronizzazione
Bangumi di Kazumi è tracking, non un ulteriore formato di backup completo.
Google Play services Auth 22.0.0 proviene dal Maven ufficiale Google, con le proprie
condizioni e attribuzioni; non è presentato come codice Apache di Nyanime.
Tink e i suoi avvisi erano già parte della cifratura esistente.

22 test JVM superati il 19 ottobre 2026: round trip multi-segmento, manomissioni, chiave errata,
retry immutabili, conservazione tra dispositivi, XML ostile, redirect, risposte brevi,
XML UTF-16, cancellazione e interruzione di rete dopo gli header, stato dell'ultimo backup,
scope/cartella Drive, conferma dei Range e consenso scaduto. Le prove simulate non
costituiscono una verifica di un account Drive o di tutti i server WebDAV reali.

Il profilo esplicito `-PremoteBackupProbe=true` compila soltanto la sonda in
`app/src/remoteBackupProbe/java`. La sonda usa file casuali privati per verificare
DOM Android, rifiuto delle entità, vault Keystore e cifratura/recupero. Crea anche
un backup completo temporaneo dell'installazione e lo valida in privato, senza
inviarlo o ripristinarlo; i file temporanei vengono eliminati a fine prova.
È distinto dagli altri profili
di strumentazione e non cambia i test unitari ordinari.

La sonda è passata anche sul telefono USB reale il 19 ottobre 2026, sulla build
firmata 0.48.2.20: parser XML Android, rifiuto delle entità esterne, Keystore,
cifratura a più segmenti, manifest autenticato, chiave errata, payload corrotto
e creazione/validazione di un backup completo temporaneo. Nessun dato dell'utente
è stato ripristinato o caricato su servizi remoti durante questa prova.

Provati anche accesso dalle impostazioni e dal collegamento in Altro, temi chiaro/scuro,
testo ingrandito, errore HTTPS e guida Drive con il certificato reale. Sul candidato
arm64 `540a3574642315247998ec1faf3920ff4c2d20c5a0362fac16b39c2c47174b74`,
il campo resta interamente leggibile con la tastiera Samsung in orizzontale:
la testata cede spazio soltanto quando necessario e ritorna chiudendo la tastiera.
La rotazione conserva l'URL non salvato. La sonda finale elimina i propri file prima
di terminare la strumentazione e ha rimosso anche le impostazioni della stanza locale di prova.
