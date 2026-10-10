# Lettura guidata

Modalità facoltativa del lettore, ricordata per titolo. Il pulsante con mirino
si trova nella barra inferiore. La modalità mantiene il file originale:
inquadratura delle vignette, zoom manuale e pagina intera usano lo stesso
renderer a tasselli del lettore. Nell'editor gli angoli modificano i bordi;
i numeri si possono trascinare per cambiare ordine.

## Ordine delle vignette

In Impostazioni del lettore → Modalità di lettura, con Lettura guidata attiva,
«Ordine delle vignette» permette di scegliere destra→sinistra, sinistra→destra
oppure dall'alto in basso. La scelta è indipendente dallo scorrimento delle pagine
e viene ricordata per titolo. Destra→sinistra è il valore iniziale; una scelta
esplicita precedente sinistra→destra viene conservata all'inizializzazione.

Le modalità verticali mantengono il gesto verso l'alto per avanzare, anche quando
le vignette seguono l'ordine manga. Le geometrie già riconosciute vengono riordinate
senza eseguire nuovamente il modello. Cambiare ordine conserva la vignetta e lo
zoom personale; l'ordine modificato manualmente nell'editor resta prioritario.
«Automatico» nell'editor ripristina la sequenza della direzione attualmente scelta.

La selezione usa i bit 0x180 dei viewerFlags, separati da lettura, orientamento e
abilitazione guidata. I backup manga esistenti conservano questi bit. I nuovi
segnalibri aggiungono il rettangolo focalizzato alla fine di GuidedLocation, senza
cambiare i campi protobuf precedenti: l'indice può cambiare, la vignetta resta la
stessa. I vecchi segnalibri vengono associati mediante il centro salvato; se
l'associazione non è univoca, viene mostrata la pagina intera.

Pagina intera e vignetta adattata restano ferme durante i gesti a un dito.
Il pinch o il doppio tap ingrandiscono; solo dopo lo zoom personale il trascinamento
passa al renderer. Tornando al focus, scala e centro vengono recuperati con la
transizione esistente. Lo swipe di navigazione viene elaborato dopo gli eventi
del renderer, così l'evento di rilascio non interrompe la nuova transizione.
Quando barre di sistema, rotazione o strumenti dell'editor cambiano lo spazio
disponibile, il focus viene ricalcolato nello stesso layout, conservando soltanto
lo zoom personale. Il primo gesto non deve correggere una posizione ormai superata.

Le nuove posizioni includono `focusZoom`, facoltativo e relativo al focus della
vignetta: lo zoom automatico non diventa uno zoom personale su schermi diversi.
I record precedenti rimangono importabili; alla scala adattata i vecchi spostamenti
sono normalizzati, mentre gli ingrandimenti effettivi vengono conservati.

## Separazione delle responsabilità

- core/panels: modello, servizio isolato, client Binder, segmentazione Kotlin,
  geometrie, ordine, analisi per porzioni e archivio portabile.
- GuidedViewer: caricamento con i PageLoader esistenti, renderer, gesti,
  controlli ed editor. Una richiesta corrente e un solo precaricamento.
- Backup: campo protobuf 511, facoltativo; il campo ritirato 510 rimane riservato.
- GuidedCloudSyncPort: documenti cifrati attraverso il journal Cloud esistente.
  Non sposta il lettore attivo. Incognito e fonti escluse non vengono esportati.

Il bit 0x40 dei viewerFlags non interferisce con orientamento e direzione.
Le correzioni sono associate a fonte, URL titolo/capitolo, indice pagina e
impronta dei byte dell'immagine; un'immagine cambiata non eredita geometrie errate.
Nessuna conoscenza dei cataloghi o dei siti è necessaria nell'app.

## Motore

DeepPanelAndroid revision 75133803b9615cc37f744f998459d883789fd247, modello incluso
con verifica SHA-256 prima della build. LiteRT 1.4.2, CPU con due thread.
Solo il processo :panels carica l'interprete; nessun database o client di rete
viene inizializzato lì. Input Binder limitato a 224 × 224 pixel. La pagina
originale rimane nel processo del lettore e viene decodificata per tasselli.

Timeout, errore o arresto del servizio lasciano disponibile la pagina intera.
La cache automatica conserva al massimo 128 geometrie, senza immagini.
Correzioni e punti di lettura entrano nei backup; file originali e cache no.
Il riconoscimento può sbagliare: pagina intera e correzione sono sempre disponibili.

Licenza e provenienza: core/panels/NOTICE.md e assets/deeppanel/LICENSE.txt.

## Verifiche dell'anteprima 0.35.0.0

- 1.214 test dell'app e 8 del modulo panels, senza errori; lint release del modulo.
- Modello e librerie native verificati negli APK delle quattro ABI e in quello universale,
  inclusi hash, asset non compresso e segmenti ELF allineati a 16 KB.
- Galaxy Z Flip6 reale: lettura locale offline, navigazione, pagina intera, editor,
  aggiunta/rimozione, ridimensionamento, trascinamento dell'ordine e ripresa dopo riapertura.
- Layout orizzontale e configurazione di 320 dp con caratteri al 130%; contatori compatti
  quando lo spazio è ridotto. Controlli delle stanze posizionati rispetto alla barra misurata.
- Animazioni disattivate e arresto intenzionale del solo processo isolato: il lettore
  principale è rimasto attivo e il riconoscimento ha ricreato il proprio processo.
- Modalità disattivata: ritorno al lettore precedente sulla stessa pagina e nessun
  processo di riconoscimento mantenuto.

Il campione locale comprende tavole a colori, una conversione in scala di grigi,
impaginazioni regolari e irregolari e una striscia verticale sintetica. Non è un
benchmark su tutti i manga. Nelle vignette sovrapposte e in alcune porzioni della
striscia il modello ha unito vignette o riconosciuto zone vuote: il conteggio da
solo non dimostra una segmentazione corretta. Pagina intera e correzioni manuali
sono quindi parte dell'esperienza, non una promessa di riconoscimento infallibile.

La compatibilità dei backup è coperta dai test. Sincronizzazione Cloud e stanze
con due partecipanti non sono state provate da capo su due dispositivi in questa
verifica: è stata preservata l'integrazione con i rispettivi percorsi esistenti.

## Verifiche dell'anteprima 0.36.0.2

- Suite dell'app: 1.266 casi, di cui 7 saltati, nessun errore; 17 test panels,
  lint release e formattazione superati. Ripetuti i test dei backup dopo la
  correzione degli assestamenti del layout.
- Galaxy Z Flip6 reale: pagina intera e vignette ferme alla scala adattata,
  pinch e doppio tap, esplorazione dopo l'ingrandimento, ritorno al focus,
  swipe e ripresa dello zoom personale dopo la riapertura.
- Rotazione e barre di sistema: la nuova area viene adattata prima del gesto;
  confronto delle immagini prima e dopo il trascinamento senza spostamenti.
- Editor: aggiunta, rimozione e salvataggio delle vignette; il focus adattato
  torna stabile dopo la chiusura dei controlli dell'editor.
- Tavole a colori, in scala di grigi e verticali; configurazione di 320 dp,
  caratteri al 130% e animazioni disattivate, senza sovrapposizioni dei controlli.
- Arresto intenzionale del servizio isolato durante una nuova analisi: pagina
  intera disponibile, processo principale invariato e riconoscimento nuovamente
  operativo sulla pagina successiva. Nessun nuovo crash del lettore nelle prove.
- Modalità disattivata e riapertura: lettore normale sulla stessa pagina,
  senza processo di riconoscimento attivo. Rimossi i file di prova e ripristinate
  le impostazioni temporanee del telefono.

Queste prove riguardano la stabilità del focus e dei gesti. Non cambiano i limiti
del modello descritti sopra e non costituiscono una nuova prova Cloud o una
sessione manga con due partecipanti. Il codice dei relativi protocolli non è
stato modificato.
