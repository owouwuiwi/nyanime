# Lettura guidata

Modalità facoltativa del lettore, ricordata per titolo. Il pulsante con mirino
si trova nella barra inferiore. La modalità mantiene il file originale:
inquadratura delle vignette, zoom manuale e pagina intera usano lo stesso
renderer a tasselli del lettore. Nell'editor gli angoli modificano i bordi;
i numeri si possono trascinare per cambiare ordine.

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
