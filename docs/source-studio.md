# Studio delle fonti

> Versione ordinaria: integrazione 0.49.0.0. Le prove di build e dispositivo che citano NyanimeB descrivono esclusivamente quelle versioni storiche; le verifiche della build ordinaria sono riportate nella [migrazione](normal-migration.md).

Lo Studio è un editor per definizioni personali video e manga. Non è un catalogo e
non contiene siti preconfigurati. Indirizzi, selettori, trasformazioni, categorie,
filtri e flussi della Home appartengono al documento dell’utente.

## Percorso

**Sfoglia → Le tue fonti → Crea fonte**. Una fonte nasce come bozza. La vista
guidata e il canvas modificano lo stesso grafo: cambiare vista non converte i dati.
Lo Studio è raggiungibile anche da **Altro → Store → Le tue fonti**.
Le famiglie dei blocchi hanno colore e nome: ingressi blu, rete celeste, lettura
verde acqua, trasformazioni ambra, condizioni viola, script rosa, risultati verdi.
Zoom, inquadratura e selezione multipla hanno comandi compatti con etichette
accessibili e suggerimenti al tocco prolungato. In orizzontale, prova e attivazione
passano nella testata per conservare spazio al canvas; allineamento ed eliminazione
della selezione sono nel menu degli strumenti.

Il percorso base è: richiesta → lettura HTML/JSON → selezione degli elementi →
campi → risultato. Gli indirizzi e i selettori iniziali vanno compilati. Il browser
di selezione permette di scegliere un elemento; il selettore ottenuto è modificabile.
Per risposte API si possono esplorare i percorsi JSON. I test di rete sono espliciti.

Prima di attivare una fonte verificare ricerca, dettagli, elenco episodi/capitoli,
apertura del contenuto e ogni sezione della Home. Una validazione strutturale non
garantisce che i selettori rappresentino correttamente un sito.

## Formato e identità

File `.nyastudio`, JSON UTF-8, schema 1, massimo 2 MiB per documento. Le operazioni
sconosciute sono rifiutate. L’identità della fonte deriva da UUID e tipo di contenuto,
non dal nome, dominio o revisione. Duplicare genera una nuova identità; modificare
una regola conserva quella esistente. Non sovrascrive estensioni APK installate.

Flussi: `SEARCH`, `DETAILS`, `ENTRIES`, `CONTENT`; facoltativi `POPULAR` e `LATEST`.
Ogni flusso è un grafo aciclico con un risultato. La Home può aggiungere flussi
propri, fino a 16 sezioni, categorie, gruppi, classifiche e visualizzazioni manga.
Le dichiarazioni sono adattate al contratto Home già usato dalle altre fonti.

I risultati delle liste sono array di oggetti con `title`, `url` e facoltativamente
`cover`, `aliases` e metadati. I dettagli aggiungono descrizione, generi, autori,
stato e ID di catalogo effettivamente dichiarati dalla fonte. Episodi/capitoli
richiedono `title` e `url`; `number`, `uploaded` (millisecondi Unix) e `group` sono
facoltativi. Il contenuto video restituisce URL, etichetta, risoluzione, header e
tracce; il contenuto manga restituisce gli URL delle immagini, in ordine.

Il risultato di ricerca può essere `{ "items": [], "hasNext": false, "cursor": "" }`.
Nel canvas si collegano `hasNext` e `cursor` al blocco Risultato. Senza indicazione
esplicita non viene presunta una pagina successiva. Le variabili comprendono
`query`, `page`, `cursor`, `url`, `baseUrl`, `section` e `filter.ID`.
`{{query|url}}` codifica un parametro URL. Gli ingressi collegati sono disponibili
anche come variabili; i valori di altri blocchi devono avere dipendenze esplicite.

## Esecuzione e isolamento

- Massimo 128 nodi e 512 collegamenti per grafo, 32 sottografi, profondità 4.
- Massimo 512 operazioni per invocazione; timeout complessivo 45 secondi.
- Il broker limita a cinque richieste simultanee, 16 richieste per invocazione,
  sei passaggi di redirect per richiesta e 2 MiB per risposta.
- Solo HTTP/HTTPS nelle origini autorizzate. Redirect con credenziali verso un’altra
  origine e downgrade HTTPS sono rifiutati. Cookie separati per fonte, soltanto in memoria.
- Le espressioni regolari usano RE2/J. Selezioni limitate a 2.000 elementi.
- JavaScript usa QuickJS in un servizio Android con UID isolato, senza oggetti Java,
  filesystem dell’app, database o rete diretta. Watchdog: 2 secondi e 64 MiB aggiuntivi.
  I limiti del processo richiedono anche verifica sul dispositivo.
- Le risposte delle prove non entrano nel documento o nel backup. Una cancellazione
  resta una cancellazione e non viene trasformata in errore della fonte.

Le credenziali locali sono cifrate con Android Keystore. Si richiamano negli header
HTTP con `{{secret.NOME}}`; non vengono esportate nei file della fonte o nel relativo
backup. Dopo un ripristino devono essere reinserite. Gli script importati o
ripristinati non ereditano l’autorizzazione di un altro dispositivo.
La cifratura lega ogni credenziale all’identità della fonte e alle sue origini
autorizzate: cambiando le origini, la credenziale va salvata di nuovo prima
di poter essere usata. Cambiare soltanto il percorso di una pagina non la invalida.

## Versioni attive e backup

Bozza, versione attiva e precedente sono conservate separatamente con scrittura
atomica. L’attivazione durante riproduzione/lettura o un’esecuzione della fonte è
rinviata; la bozza non cambia gli adattatori in uso. È disponibile il ripristino
della versione precedente. Eliminare una definizione conserva libreria e progressi.
I backup delle impostazioni delle fonti includono i documenti, non cache o segreti.

Importando una definizione già presente, l’interfaccia permette di aggiornare la
bozza mantenendo la sua identità oppure creare una copia indipendente. Nessuna
importazione sostituisce subito la versione attiva. Il canvas conserva la vista
passando fra flussi e modalità guidata; «Inquadra grafo» calcola i limiti dei nodi.

## Modalità Assistita

«Crea fonte» propone Assistita oppure Nodi. La prima usa esempi scelti dall’utente
nel browser: scheda dei risultati, titolo, collegamento, copertina, elenco episodi
o capitoli e contenuto. «Naviga» permette di aprire pagine e usare la ricerca del
sito; «Seleziona» intercetta il tocco ed evidenzia gli elementi corrispondenti.
Nome, indirizzo, categorie e regole non provengono da un catalogo interno.

Ogni proposta è un normale grafo. La prova effettua una richiesta con il medesimo
runtime delle fonti, richiede risultati strutturati validi e verifica che il titolo
scelto compaia fra quelli estratti. Il DOM renderizzato nel browser, da solo,
non dimostra che la richiesta HTTP possa riprodurlo. La conferma salva la bozza,
non attiva la fonte. Annullamenti o cambi degli esempi invalidano la prova precedente.

Sezioni della Home e filtri sono passaggi distinti e facoltativi. Per i filtri
singoli, `values` associa l’etichetta mostrata al valore osservato nella richiesta;
senza questa mappa resta il comportamento originario che usa l’etichetta.
La vista avanzata mantiene accessibili tali valori.

L’assistenza attuale supporta ricerca GET con un parametro osservabile, HTML
presente nella risposta, collegamenti di paginazione dalla prima alla seconda
pagina con un solo parametro numerico, immagini e video/source con URL diretto.
POST, player incorporati, richieste protette o dipendenze da JavaScript possono
richiedere i blocchi avanzati. Non vengono generate regole presunte per quei casi.
Le evidenze temporanee rimangono nel modello della schermata e non nel backup.

## Componenti e attribuzioni

Lo Studio riutilizza Jsoup, OkHttp e QuickJS Android già presenti nell’app, oltre
a RE2/J 1.7 per le espressioni regolari a complessità limitata. Il testo originale
della licenza Go/BSD di RE2/J è già incluso nel registro offline delle licenze,
con la versione e il relativo hash. Non è una licenza Apache-2.0:
[testo della versione 1.7](https://github.com/google/re2j/blob/re2j-1.7/LICENSE).
La verifica di questo componente non cambia le condizioni GPL degli altri
moduli e dei binari già inclusi in NyanimeB.

## Verifica e sviluppi successivi

I test ordinari usano dati e siti fittizi. Il collaudo manuale può ricevere un
documento esterno con `-PstudioFixture=percorso-assoluto`: nessun indirizzo o progetto
reale viene incluso nell’app o nei test committati. I risultati sono salvati accanto
al file fornito. Gli esercizi su fonti reali restano in `.local/`, esclusa da Git.

Lo Studio è la prima milestone. OCR/traduzione, elaborazione delle tavole,
confronto FSR/Anime4K, compatibilità Syncplay, romanzi/TTS, adattatori di altri
formati e servizi cloud restano consegne successive, con verifiche e attribuzioni
separate. Questo documento non dichiara quelle integrazioni già disponibili.

### Collaudo della build privata 0.48.2.17

- 40 test superati: 16 del runtime e della rete, 24 per pubblicazione, backup,
  credenziali e migrazione dell’identità grafica. I test di rete ordinari usano
  server locali; il collaudo aggiuntivo di una definizione reale è facoltativo e privato.
- Su Android reale, USB: importazione e attivazione, ricerca, scheda, episodi,
  avvio nel player e apertura del lettore manga con cambio pagina.
- Una pubblicazione rinviata durante il video è stata applicata dopo la chiusura
  esplicita della riproduzione.
- QuickJS è stato avviato con UID isolato. Gli oggetti Android/Java e la rete
  diretta non erano disponibili; un ciclo infinito è stato interrotto in circa
  due secondi mantenendo vivo il processo principale.
- Provati selezione di un elemento nel browser e riconoscimento di una definizione
  già importata. Su un grafo da 128 nodi, una breve prova di spostamento sul telefono
  USB ha rilevato 642 fotogrammi, 95° percentile 7 ms e 8 fotogrammi in ritardo.
  È una misura circoscritta a quel dispositivo, non una garanzia per ogni telefono.

Queste sono prove funzionali circoscritte, non una certificazione di compatibilità
con ogni sito. Richieste protette, selettori, formati dei media e credenziali vanno
collaudati nella definizione personale. Le prove non attestano le integrazioni
successive né confronti prolungati delle prestazioni dei player.

### Collaudo aggiuntivo della build privata 0.48.2.18

- Sul telefono USB, una fonte manga fittizia è stata costruita scegliendo nel
  browser titolo, copertina, descrizione, capitolo e pagina. Ogni passaggio è stato
  provato e approvato separatamente prima dell’attivazione esplicita.
- La fonte così ottenuta ha aperto la lista nativa, la scheda con tre capitoli e
  il lettore con tre pagine. L’assistente ha mantenuto la pagina corretta durante
  il passaggio automatico da scheda a capitoli e da capitoli a contenuto.
- Verificato l’annullamento durante una richiesta: chiudendo la prova non rimangono
  tracce in esecuzione; dopo il reset di `gfxinfo` il canvas inattivo non ha generato
  fotogrammi per oltre un minuto.
- Verificata la splash rossa/arancione con avvio a freddo. La versione installata
  in queste prove precede lo scambio P2P: non costituisce prova di quel trasferimento.

## Scambio delle fonti

Accesso da **Altro → Store → Scambia fonti**, dalla schermata **Le tue fonti** oppure
dal menu **⋮ → Invia** di una definizione dello Studio. Non serve un account.

1. Sul dispositivo che invia, scegliere la fonte o un file supportato, poi
   **Crea codice di trasferimento**. La definizione attiva viene preferita alla
   bozza; il deposito separato dei segreti non viene esportato.
2. Sull’altro dispositivo, aprire **Ricevi**, scegliere la stessa modalità di rete
   e acquisire il QR o incollare il codice. Premere **Ricevi e verifica**.
3. Toccare il file verificato per esaminarlo. Una definizione viene importata come
   bozza; gli archivi e gli APK passano agli importatori esistenti. L’hash verifica
   l’integrità del trasferimento, non l’affidabilità dell’autore.

Il mittente deve mantenere aperta la sessione, anche cambiando schermata. Il codice
scade dopo quindici minuti o all’arresto esplicito; non è un collegamento permanente
per pubblicare file. Sono ammessi `.nyastudio`, `.nyasource`, `.rin`, `.json` e APK
singoli nei formati già supportati. Un file può occupare al massimo 128 MiB e i
pacchetti APK suddivisi non vengono esportati come un singolo file incompleto.

**Stessa rete** usa indirizzi locali senza relay Internet. **Internet** usa QUIC
cifrato e i servizi pubblici n0 quando necessari: i relay possono osservare i
metadati delle connessioni e la loro disponibilità dipende dal servizio. Non sono
servizi controllati da NyanimeB e non viene promessa disponibilità continua.

Il collegamento `nyanime://sources/v1#…` apre la schermata di ricezione senza
avviare richieste. Alcune app di messaggistica non rendono cliccabili i collegamenti
personalizzati: in quel caso usare QR oppure copia/incolla. Non è stato aggiunto
un server pubblico di catalogo o di caricamento dei file.

Il motore è separato dal processo principale, caricato solo avviando uno scambio
e senza accesso tramite DI ai database dell’app. I file ricevuti non vengono
ridistribuiti. Documenti, script e pacchetti richiedono le rispettive approvazioni.
La build privata iniziale del motore è arm64; le altre ABI richiedono binari
compilati e verificati separatamente, non la riutilizzazione di quel file `.so`.

### Collaudo della build privata 0.48.2.19

- 44 test pertinenti superati: 24 del runtime Studio, 11 dell'app per pubblicazione,
  protocollo e chiusura delle sessioni, 9 del motore nativo. Un ulteriore test su
  una definizione esterna è facoltativo e non è stato eseguito in questo giro.
  Questo conteggio non rappresenta l'intera suite dell'app.
- APK arm64 firmato con il certificato esistente, installato sul telefono USB.
  Verificati allineamento a 16 KiB, provenienza del motore e presenza delle classi
  JNI/TLS nell'APK ottimizzato; superato anche il controllo ABI dei ViewModel.
- Invio dal telefono a un peer Linux sulla rete locale: definizione dello Studio
  con quattro flussi, impronta BLAKE3 verificata indipendentemente. Acquisito e
  decodificato il QR effettivo; provata la condivisione del codice.
- Ricezione sul telefono via Internet su Wi-Fi, anche da un peer privato senza
  socket IP diretti: il trasferimento è passato interamente da un relay pubblico.
  La ricezione è distinta dall'approvazione della definizione come bozza.
- Provati l'invio di un archivio di estensione già installata e la ricezione di
  un APK originale: l'importatore esistente ne ha verificato la firma e mostrato
  l'anteprima, senza caricarlo o installarlo automaticamente.
- Ripetuti avvio e arresto sul telefono. Dopo il comando Interrompi, il servizio
  avviato è scomparso in circa 0,24 e 0,29 secondi nelle due ripetizioni misurate.
  La chiusura protegge anche il ritorno al dispatcher del servizio dall'annullamento.
- La prova su rete mobile non è eseguibile in questa sede: il proprietario ha
  confermato che la connessione dati non funziona. Wi-Fi ripristinato; nessuna
  modifica a DNS, APN o criteri di rete. Le fonti e la cronologia fittizie delle
  prove sono state rimosse senza toccare quelle personali.

Queste prove attestano quei percorsi su un telefono USB e un peer Linux, non un
collaudo fra due telefoni o su ogni operatore. L'APK resta privato e arm64, senza
pubblicazione OTA ordinaria.
