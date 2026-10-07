# Nyanime Cloud

Nyanime Cloud collega un vero account Telegram attraverso TDLib, la libreria
ufficiale. La sessione e il trasporto sono condivisi: future funzioni di messaggistica
potranno usare lo stesso account senza aggiungere un secondo login. Questa versione
gestisce soltanto i backup; non importa contatti e non pubblica un profilo.

## Usare Cloud

Al primo avvio Nyanime propone Cloud prima delle altre scelte di configurazione.
**Collega Telegram** apre subito l'accesso; **Più tardi** prosegue senza avviare
il motore o attivare backup. La scelta è locale e non viene importata dai backup.
Un account già configurato non riceve di nuovo questa proposta.

Puoi sempre aprire **Altro → Nyanime Cloud**. Collega Telegram con numero, codice e verifica
in due passaggi, oppure scansiona il QR con Telegram su un altro dispositivo.
Il modulo del numero propone **Italia (+39)**, modificabile tramite il selettore
ricercabile del paese. Basta scrivere il numero nazionale; incollare un numero
completo con `+` o `00` riconosce il paese e non aggiunge due volte il prefisso.
La normalizzazione usa i metadati di [libphonenumber](https://github.com/google/libphonenumber),
conservando o rimuovendo gli zeri secondo il paese selezionato. Nessun accesso ai
contatti, alla SIM o alla posizione è necessario.
L'archivio è un canale privato **Nyanime Backup**, del quale soltanto il tuo account
è membro. Un canale esistente viene riconosciuto tramite il descrittore, non dal
nome; se ne esistono più di uno, scegli quello da usare.

Su un nuovo telefono l'app propone di controllare e ripristinare una copia prima
di attivare il primo backup automatico. La copia più recente mostra dispositivo
e data; **Scegli un'altra copia** apre la cronologia. La ricerca delle copie prosegue
anche quando la prima pagina Telegram contiene soltanto messaggi di servizio.

La schermata di ripristino mostra anime, manga, episodi, capitoli e impostazioni,
con le opzioni avanzate espandibili. Per Cloud è preselezionato il recupero completo,
incluse le RIN, usando lo stesso validatore e lo stesso motore del backup locale.
Mostra poi avanzamento persistente ed esito, distinguendo eventuali avvisi dal
successo completo. Se la schermata viene chiusa, il lavoro continua; riaprire la
stessa copia ritrova il lavoro attivo senza duplicarlo. La copia nel canale resta
intatta. I backup automatici si attivano dopo **Continua su Nyanime** nell'esito,
oppure dopo la scelta esplicita di continuare senza ripristinare.

I valori iniziali sono **12 ore**, Wi-Fi e rete mobile, **10 copie per dispositivo**.
Puoi cambiare intervallo, usare solo Wi-Fi, scegliere la conservazione per numero
o per età e proteggere singole copie. La pulizia conserva l'ultimo backup di ogni
dispositivo e avviene dopo la conferma di un nuovo salvataggio valido.

**Salva adesso** avvia un lavoro persistente con avanzamento. Puoi uscire dalla
schermata e tornarci dalla notifica. Gli invii interrotti conservano il file e la
loro identità; l'app cerca la conferma prima di ritentare. Android e Telegram
possono rinviare un salvataggio in background: l'intervallo è una programmazione,
non una promessa di esecuzione all'esatto minuto. Durante lettura, video e Cast
il backup automatico viene rinviato.

## Contenuti e riservatezza

Il file `.nyabk` usa il backup completo già esistente: librerie anime e manga,
progressi e cronologia, categorie, tracking, impostazioni portabili e private
supportate, preferenze delle estensioni, RIN installate e relativi stati.
File multimediali scaricati, file Ultra, sessione Telegram, chiavi del database
Telegram, identificativi locali di Cloud e coda dei trasferimenti sono esclusi.
Impostazioni portabili Cloud vengono conservate; attivazione e nome del telefono
restano locali. Non è una sincronizzazione in tempo reale fra dispositivi.

Il canale usa la normale **cifratura Cloud di Telegram, non end-to-end**.
Come scelto per questa versione, il backup non riceve una password aggiuntiva:
può contenere preferenze private e credenziali già incluse nel backup completo.
Non condividerlo o aggiungere altre persone al canale. Nyanime verifica nuovamente
proprietario, membri e assenza di username pubblico prima di trasferire dati.

Per il ripristino e l'esportazione, dimensione, SHA-256 e formato del file vengono
verificati prima dell'uso. **Scollega Telegram** revoca la sessione tramite TDLib,
elimina cache, file temporanei e chiavi locali dopo la conferma di chiusura; il
canale e i backup su Telegram restano disponibili.

## Configurazione di sviluppo

Registra la tua applicazione Android su <https://my.telegram.org>, nella pagina
API development tools, seguendo <https://core.telegram.org/api/obtaining_api_id>.
Usa il nome Nyanime e un identificatore breve disponibile, per esempio `nyanime`.
Non usare credenziali prese da altri client Telegram.

Su Windows esegui `scripts/configure_telegram.ps1`: il dialogo salva `api_id` e
`api_hash` in `.local/telegram.properties`, ignorato da Git e leggibile soltanto
dall'utente corrente. Non incollare i valori in chat o nei log. In CI configura
i secret **TELEGRAM_API_ID** e **TELEGRAM_API_HASH**. La pubblicazione richiede
entrambi; `scripts/configure_telegram_ci.py` può trasferirli cifrati ai secret del
repository usando l’accesso GitHub già configurato, senza stamparne i valori.
Una build Cloud non configurata non viene pubblicata come funzionante.

Il workflow **Telegram native engine** compila tutte le ABI da revisioni fissate
di TDLib e OpenSSL. I workflow Android riutilizzano i suoi artifact, verificano
provenienza e allineamento ELF a 16 KB e controllano i nomi JNI nell'APK ottimizzato.
Il codice Kotlin parla con un servizio privato nel processo `:telegram`; il motore
non viene caricato nel processo del player o del lettore e non viene inizializzato
quando Cloud non è configurato. Non vengono distribuiti motori binari sconosciuti.

I contratti `TelegramGateway`, `TelegramAccountRepository`, `TelegramConversation`
e `TelegramMessagingGateway` centralizzano gli identificativi e la sessione.
`NyanimeDirectory` è soltanto il contratto per una futura adesione volontaria:
collegare Cloud non rende visibile l'utente ad altri utenti Nyanime. Chat, gruppi
e integrazione con le stanze non sono ancora implementati.

Riferimenti: [TDLib](https://core.telegram.org/tdlib/),
[regole API](https://core.telegram.org/api/terms),
[protezione Telegram](https://telegram.org/faq#q-how-do-you-encrypt-data).

## Stato delle verifiche

Verificati il 7 ottobre 2026:

- Suite dell'app: 1.171 test, nessun errore, 7 esclusi dalla suite esistente.
- Modulo Telegram: 15 test sui descrittori, sulla conservazione, sull'archivio
  privato, sulle conferme degli invii, sugli errori di autenticazione e sui numeri
  con prefisso esplicito. Non viene indovinato il paese dell'account.
- Compilazione ufficiale per quattro ABI, provenienza e allineamento ELF a 16 KB.
- Prova JNI sul telefono Android collegato: TDLib 1.8.67, creazione del client,
  stato iniziale e chiusura. Il pacchetto di prova non ha aperto un database utente
  e viene rimosso dopo l'esecuzione.
- Build configurata e firmata 0.31.0.0 (codice 164), installata come aggiornamento
  dell'app principale. Controlli JNI negli APK ottimizzati superati per tutte le
  quattro ABI e per il pacchetto universale.
- Avvio reale di Cloud sul telefono: motore nel processo privato `:telegram`,
  parametri accettati e schermata di accesso con numero di telefono. Nessun crash
  rilevato durante questa prova. Non equivale a un accesso autenticato.
- Diagnosi IPC sulla stessa installazione: soltanto 9 delle 50 richieste innocue
  `getOption("version")` hanno ricevuto risposta. Il callback Handler passava
  alla coroutine un `Message` Android già riciclabile. La correzione copia
  immediatamente il JSON immutabile. Sulla 0.31.0.1 la stessa prova ha ricevuto
  tutte le 50 risposte; il pacchetto di prova è stato rimosso.
- Accesso completato dall'utente e creazione del canale privato verificati.
- Build firmata 0.31.0.2 (codice 166), installata conservando sessione e coda.
  Corretto il contenuto `inputMessageDocument` per l'API ufficiale inclusa:
  il documento contiene ora un `inputDocument`, che racchiude `inputFileLocal`.
- Invio reale confermato dal server, inclusa ripresa della copia preparata con
  la build precedente. Esportazione `.nyabk` riuscita dopo controllo di hash,
  dimensione e validazione; hash identico tra file esportato sul telefono e copia
  trasferita sul PC.
- Anteprima di ripristino sul telefono: 150 anime, 49 manga, progressi e 909
  impostazioni. Ripristino eseguito; rilevato un avviso sul catalogo già presente,
  corretto nella versione seguente senza accettare chiavi diverse.
- Tre test del ripristino dei cataloghi: copia identica idempotente, chiave diversa
  rifiutata e stessa chiave a un indirizzo diverso non accettata silenziosamente.
- Build 0.31.0.3 (codice 167) firmata, verificata e installata. Reingresso controllato
  nella configurazione iniziale, conservando l'account autorizzato e tutti i dati:
  proposta Cloud visibile prima della Home, nessun processo Telegram prima della
  scelta, riconoscimento dell'archivio e della copia recente.
- Ripristino completo nella nuova schermata, incluse 24 estensioni: concluso in
  1 minuto e 23 secondi con **0 errori**. Avanzamento osservato anche dopo un cambio
  della dimensione dei caratteri da 1.0 a 1.3; il valore originale è stato ripristinato.
- Dopo la conferma finale, ritorno alla Home e nuovo backup automatico confermato.
  Nessun crash rilevato nelle prove. Il pacchetto strumentale è stato disinstallato.
- Modulo Telegram aggiornato: 19 test superati, inclusi numeri nazionali,
  incolla internazionale, spazi Unicode, input incompleti e gli esempi dei paesi
  supportati. Conservato lo zero italiano, rimosso quello di accesso britannico.
- Build 0.31.0.5 (codice 169) firmata, verificata e installata come aggiornamento
  dell'app principale. Cloud riconosce ancora l'account collegato e i backup.
  Controlli TDLib/JNI superati negli APK per quattro ABI e nel pacchetto universale.
- Sullo stesso APK ottimizzato, cinque casi di normalizzazione e quattro file
  di metadati nazionali verificati tramite il pacchetto strumentale, poi rimosso.
  La prova non ha richiesto codici di accesso né modificato l'account.
- Tre anteprime native del modulo numero controllate: tema chiaro, tema scuro
  e larghezza 320 dp con caratteri al 150%. Per il renderer sul PC sono state
  escluse classi R duplicate di Insetter da copie temporanee del solo classpath
  delle anteprime; gli APK e le dipendenze dell'app non sono stati modificati.

Restano da verificare recupero su un altro telefono, interruzioni prolungate della
rete e revoca della sessione. La configurazione
API è già disponibile privatamente per la build locale e tramite i secret cifrati
di GitHub Actions; nessun valore è conservato nel codice o nella documentazione.
