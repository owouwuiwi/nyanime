# Verifica di regressione — 6 ottobre 2026

## Correzioni

- La categoria dichiarata da un'estensione identifica il tipo di contenuto; la
  lingua resta un attributo separato. Una dichiarazione errata deve essere
  corretta nell'estensione, senza introdurre riconoscimenti per nome nell'app.
- La Home manga aggrega tutte le sezioni in evidenza dei fornitori della
  categoria, conserva le alternative e unisce opere con identità pubblica
  verificata. Una fonte lenta non impedisce di mostrare i risultati già pronti.
- Il tracking automatico usa direttamente gli ID verificati, serializza le
  associazioni e distingue gli errori temporanei da assenza di accesso,
  incompatibilità e risultati ambigui. I tentativi sono limitati e rispettano
  l'incognito, anche per singola estensione. Il recupero storico non viene
  riattivato automaticamente dopo che è terminato.
- Un canale di notifica assente o disabilitato non consuma gli avvisi in attesa.
  Permessi e canale vengono ricontrollati prima di registrare la consegna.
- La CI cerca l'installatore SDK nel percorso Android configurato anche quando
  non è nel PATH, con supporto degli strumenti CLI correnti e propagazione
  degli errori. Il job precedente terminava con `sdkmanager: command not found`
  prima della compilazione.

## Controlli automatici

- Suite Preview e Debug usata dalla CI: 1.163 test per variante, zero errori
  o fallimenti, sette esclusi dal runtime in ciascuna variante.
  Gli esclusi non vengono considerati verifiche riuscite.
- Suite Python: 38 test riusciti, inclusi migrazioni SQLite reali, coda degli
  avvisi, metadati delle release, avvio della CI e runtime Lua.
- Copertura di Home e ricerca, identità per ID, ripresa locale, tracking,
  notifiche, backup, download, aggiornamenti, PiP e geometria video, ciclo di
  vita nativo, stanze, Cast e Anime4K Smart. Questi controlli verificano i
  contratti e le politiche esercitate dai test: non sostituiscono la prova di
  ogni percorso su un dispositivo fisico.
- Compilazione Preview ottimizzata, verifica delle librerie native e della
  firma; controlli separati del modulo di estensione e del contenitore RIN.
- Nessun riferimento a domini, nomi o regole delle fonti nei file dell'app,
  nei test e nelle guide modificati da questa correzione.

## Dispositivo fisico

Su Android reale, nell'installazione esistente, verificati avvio dell'app,
tracking automatico abilitato, accessi ai servizi di tracking e autorizzazione
Android alle notifiche. Consegnate entrambe le notifiche di prova, per nuove
uscite e promemoria. Non sono stati rimossi dati o riavviato il recupero
retroattivo della libreria.

## Limiti della verifica

La notifica di prova non dimostra la rilevazione di un'uscita reale né
l'esecuzione di un allarme a schermo spento. La sincronizzazione remota del
tracking al primo avvio di un nuovo titolo, l'intero percorso dei lettori di
pubblicazioni e ogni combinazione di estensione/rete richiedono prove fisiche
aggiuntive. Le sette verifiche escluse restano escluse; nessun emulatore è stato
utilizzato. Il nuovo job GitHub va valutato sul suo risultato effettivo.
