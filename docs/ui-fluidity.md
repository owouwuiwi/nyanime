# Fluidità dell’interfaccia

## Politica visiva

Il rendering conserva layout, colori, blur e transizioni. Non riduce automaticamente gli effetti in base al dispositivo. La preferenza di animazioni ridotte continua a essere rispettata.

## Interventi

- Un solo blend per le nuove copertine. I risultati nella cache in memoria sono immediati; l’immagine precedente rimane visibile durante il caricamento ed è rilasciata dopo la dissolvenza.
- Richieste immagini legate alla revisione della copertina, senza invalidazioni per titolo, progresso o altri metadati. Restano distinti cambio URL, copertina personalizzata e passaggio alla libreria.
- Una sola osservazione della preferenza di movimento per interfaccia tematizzata.
- Righe della Home video con osservazione del proprio contenuto, chiavi e tipi di celle stabili. Le varianti conservano ciascuna il proprio scorrimento.
- Preparazione di Atlante dalle Home fuori dall’interfaccia, con una verifica incognito per fonte e annullamento delle elaborazioni superate.
- Preparazione della libreria unificata su un worker; la lista precedente rimane disponibile mentre viene preparata la sostituzione. La ricerca delle singole librerie ignora gli aggiornamenti che non cambiano la query.
- Il clock del player viene osservato dalla timeline visibile. I gesti leggono posizione e durata al momento del gesto; gli strumenti e le finestre non vengono ricomposti dal clock. L’anteprima conserva la propria dissolvenza di uscita.

## Verifica riproducibile

`AppInteractionBenchmark` usa cinque iterazioni, build ottimizzata, compilazione parziale e fixture generiche nel solo pacchetto benchmark. La navigazione attraversa la libreria unificata e mantiene percorsi per anime, manga, Tutti e lettore locale. Il benchmark non modifica le preferenze di movimento del sistema.

`ArtworkFluidityProbe` verifica sul dispositivo il caricamento della copertina reale della Home, il cambio di metadati senza nuove richieste e il ritorno dalla cache. Non entra nell’APK normale.

Le prove manuali distinguono cache calda, caricamento iniziale e animazioni di navigazione. Le acquisizioni e i log restano locali; non includere dati personali della libreria nelle fixture o nella documentazione. Registrare versione, tipo di build, dispositivo, numero di ripetizioni, percentile e fotogrammi in ritardo. Una compilazione riuscita non costituisce una prova sul dispositivo.

## Esito della prima verifica, 11 ottobre 2026

Build Preview ottimizzata e firmata, versione locale di prova 0.39.1.1, su Galaxy Z Flip6 con Android 16. Solo dispositivo USB; nessun emulatore e nessuna modifica alle preferenze di movimento del sistema. L’APK di prova conserva identificativo e firma dell’installazione esistente e non costituisce una nuova release OTA.

- 233 test JVM selezionati: nessun errore o fallimento. Controlli del formato Kotlin superati.
- Sei percorsi `AppInteractionBenchmark`, cinque iterazioni ciascuno: completati. L’avvio e la preparazione sono esclusi dalle misure degli scorrimenti; l’apertura del lettore locale è misurata separatamente.
- `ArtworkFluidityTest`: superato sul dispositivo. Primo caricamento: una richiesta e una presentazione; cambio di metadati: nessuna nuova richiesta/presentazione; ritorno dalla cache: nessun nuovo trasferimento e una sola presentazione aggiuntiva. Il test ha rilevato e permesso di correggere una seconda notifica dello stesso risultato Coil.
- Prova nell’app principale: riproduzione, controlli, pausa, schermo intero e PiP con video ancora in movimento; ritorno alla pagina di visione. Apertura di Home, librerie, Atlante, Uscite, Store, elenco backup, coda download e impostazioni; Home controllata anche in tema chiaro, poi ripristinata la preferenza scura originale. Nessun nuovo evento nel buffer crash durante queste prove.

| Percorso sintetico | p95 durata CPU del frame | p95 frame overrun |
| --- | ---: | ---: |
| Home, scorrimento | 9,2 ms | +5,5 ms |
| Libreria anime, scorrimento | 11,2 ms | +10,0 ms |
| Libreria manga, scorrimento | 13,8 ms | +11,2 ms |
| Libreria unificata, scorrimento | 8,8 ms | +4,8 ms |
| Ricerca in libreria anime | 15,0 ms | +13,4 ms |
| Apertura del lettore locale | 7,0 ms | +4,7 ms |

Un benchmark completato indica che il percorso è stato eseguito, non che ha raggiunto l’obiettivo di fluidità. Un overrun positivo rimane un limite misurato. Nei campioni Perfetto analizzati, la libreria anime presenta anche scadenze mancate dall’app; in Home prevale la coda dei buffer. Questi due fenomeni non sono equivalenti: [FrameTimeline di Perfetto](https://perfetto.dev/docs/data-sources/frametimeline) distingue il lavoro oltre la scadenza dall’aumento di latenza quando i buffer attendono la presentazione. Nessuno dei due viene eliminato dal rapporto.

Le fixture della libreria sintetica mostravano segnaposto: queste misure verificano carico del layout e ricerca, non provano lo scorrimento di copertine già decodificate. Quest’ultimo è stato controllato separatamente nell’app principale e nel test della cache.

Le misure manuali con le Home reali non costituiscono un confronto controllato: i contenuti sono cambiati tra i passaggi. Il p95 delle locandine video è passato da 18 a 12 ms nel primo confronto, ma la quota di frame in ritardo non è migliorata uniformemente. L’ultimo scorrimento verticale della Home ha registrato 4,42% di frame in ritardo e p95 13 ms; la libreria unificata aveva registrato 0,30% e p95 10 ms in un passaggio precedente. L’obiettivo di massimo 1% non è quindi dimostrato per tutta l’app.

Rimangono da verificare un confronto con contenuti congelati e copertine caricate, partenze a freddo ripetute, misurazioni comparabili nei due temi e gli scenari completi con peer Cast e stanze. Non sono dichiarati verificati da una prova con un solo telefono. Le ottimizzazioni preservano gli effetti visivi, ma questo checkpoint non garantisce assenza di scatti in ogni schermata.

## Ritorno dalla Libreria e refresh della Home

Durante la riconvalida di una sezione già caricata, il coordinatore mantiene il suo
snapshot completo fino alla conclusione delle risposte. I sottoinsiemi temporanei
dei provider non rimuovono e reinseriscono le stesse copertine nel carosello.
Il primo caricamento rimane progressivo; cambiare data continua a rimuovere gli
eventi della data precedente e le risposte annullate non sostituiscono quelle nuove.

Il refresh delle immagini recupera soltanto gli errori. Una copertina sana conserva
richiesta e painter; una revisione effettiva del contenuto continua a caricare la
nuova immagine. La rotazione del carosello si ferma durante l'aggiornamento, mentre
le schermate uscenti diventano inattive all'inizio del cambio di sezione.

La fixture fisica `ArtworkFluidityTest` copre anche refresh ripetuti dopo aver
svuotato la cache e recupero di un errore simulato attraverso il componente reale.
La fixture è esclusa dall'APK normale e usa esclusivamente contenuti generici.

## Chiusura rapida del mini-player

La revoca del PiP avviene quando il gesto di chiusura è accettato, prima dei 180 ms
dell'animazione di uscita. La sessione impedisce alle notifiche native e ai cambi
di geometria di riattivarlo durante questa fase. Se la chiusura è annullata o la
stanza rifiuta il passaggio a osservatore, il blocco viene rimosso e il player
ancora disponibile ripristina i propri parametri. La pulizia parte nella stessa
chiamata del gesto, anche se la coroutine dell'animazione viene annullata subito.

## Verifica della consegna 0.39.2.1, 11 ottobre 2026

- Build ottimizzata firmata, codice Android 201; installazione sopra quella esistente
  sul Galaxy Z Flip6 USB, conservando dati, identificativo e certificato.
- 423 test JVM eseguiti, zero fallimenti/errori; due test esclusi. Controlli Kotlin
  e 32 test della numerazione e pubblicazione superati.
- Tre test del componente immagini sul telefono: metadati e ritorno dalla cache,
  tre refresh sani con cache svuotata senza riavviare la richiesta, recupero di una
  copertina fallita. La fixture usava la build benchmark 0.39.2.0; il componente
  delle copertine non è cambiato nella successiva correzione del PiP.
- Nell'app finale, tre passaggi rapidi Libreria → Home → refresh mantengono titolo
  e selezione del carosello. La registrazione del percorso controlla separatamente
  che non compaiano copertine vuote o immagini di un altro titolo durante il refresh.
- Tre chiusure del mini-player seguite immediatamente dal tasto Home del telefono:
  nessun task PiP e nessun servizio di riproduzione residuo. La stessa sequenza
  aveva riprodotto il difetto nella build di verifica 0.39.2.0.
- Il PiP normale conserva lo stato PLAYING e l'avanzamento della posizione;
  dopo il ritorno e la chiusura del mini-player, la Home resta fuori dal PiP.
  La pagina Info mostra 0.39.2.1. Nessun nuovo evento nel buffer crash durante
  le prove di questa build; i due pacchetti benchmark temporanei sono rimossi.
- Dieci gesti sul carosello con copertine caricate, build di verifica 0.39.2.0:
  865 frame, p95 11 ms, 20 frame in ritardo (2,31%). È un campione distinto dai
  precedenti scorrimenti verticali: non dimostra un miglioramento uniforme né
  l'obiettivo massimo dell'1% per tutta l'app.

Nessun emulatore; impostazioni di movimento del sistema invariate. Restano validi
i limiti delle prove con un solo dispositivo descritti sopra.
