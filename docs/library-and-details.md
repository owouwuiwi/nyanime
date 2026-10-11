# Libreria e schede

La direzione scelta dal mockup del 21 ottobre 2026 usa un'interfaccia nativa
per la libreria e le schede anime/manga, senza un secondo archivio dei contenuti.

## Percorso

- Libreria: testata fissa, Tutti/Anime/Manga a larghezza piena, categorie, ricerca,
  filtri rapidi Da continuare/Novità/Tutti e visualizzazioni già disponibili.
  Tutti permette di scegliere anche una categoria, identificata per tipo e ID;
  categorie omonime di anime e manga non vengono fuse arbitrariamente.
- Le righe mostrano copertina a tutta altezza, titolo, avvisi reali, progresso e
  azioni di ripresa/download. Le novità continuano a usare gli avvisi esistenti.
  I filtri rapidi non modificano i filtri persistenti o la libreria.
- Il filtro Da continuare comprende anche la prima puntata/capitolo parzialmente
  fruito: considera l'ultimo accesso, oltre al numero di elementi completati.
- Scheda: copertina compatta con sfondo discreto, fonte separata, ripresa principale,
  Libreria/Tracking/Segui, progresso locale e tre sezioni con dissolvenza.
- Episodi/Capitoli conserva ricerca, filtri, indice, gruppi e stagioni forniti
  dalla fonte, swipe, selezione e operazioni multiple. Gestisci apre il gestore
  completo; il menu superiore mantiene le azioni avanzate.
  Nei cataloghi senza raggruppamenti si parte da una breve anteprima della ripresa;
  indice, ricerca e filtri continuano a rendere accessibile l'intero elenco.
  I numeri validi ordinano soltanto l'anteprima, senza diventare identità o fondere
  episodi distinti. Stagioni e gruppi dichiarati restano espandibili.
- L'opera mantiene descrizione, metadati e approfondimenti già disponibili.
- Per te legge stato, voto, note e tag dal journal di tracking esistente. Consente
  di aprire tracking, download, segnalibri, notifiche e stanze. Il conteggio della
  fonte resta distinto dal progresso manuale del tracker.

La nuova disposizione a righe viene adottata una sola volta. Le successive scelte
di visualizzazione, comprese le griglie, restano rispettate. Nessuna migrazione
di titoli, identità delle fonti o progressi viene eseguita.

## Architettura e prestazioni

I nuovi componenti sono in `it.lorelore.nyanime.presentation.library` e `it.lorelore.nyanime.presentation.entries`. Il percorso
condiviso delle schede e gli adattatori già presenti mantengono l'identità locale
di ogni episodio/capitolo; numeri e somiglianze dei titoli non diventano chiavi.

Le letture del tracking avvengono su IO, seguono la revisione dell'archivio e sono
annullate cambiando opera. Una risposta della vecchia opera non viene mostrata
su quella nuova. Aprire la scheda non crea un record né un invio al tracker.
Il fallimento della lettura ha un messaggio esplicito. Immagini, cache, protezioni
di privacy e transizioni riutilizzano i componenti dell'app.

Nessun nuovo servizio, modello, font, catalogo o dipendenza. Non vengono eseguite
ricerche remote per abbellire la schermata. Le parti in caricamento mantengono
skeleton fermi; le dissolvenze rispettano ModernMotion e le animazioni ridotte.

## Provenienza e limiti

Il disegno è originale, sviluppato dal mockup approvato e ispirato all'organizzazione
di [Karasu](https://github.com/Suzora/Karasu). Nessun codice, risorsa o modello di
Karasu è stato importato o rilicenziato. Restano tutte le licenze del progetto.

I dati dimostrativi del mockup non vengono trasferiti nell'app. Mappe dei franchise,
nuove statistiche, recensioni social e raccomandazioni rimangono nelle rispettive
issue: la nuova interfaccia non mostra istogrammi, punteggi o relazioni inventati per riempire UI.

Le verifiche automatiche e quelle su telefono vanno annotate separatamente nella
consegna: una build riuscita non dimostra da sola resa grafica e fluidità reali.

## Pulsanti e stati

I comandi di avanzamento del tracking e di ripresa nella Libreria condividono
una superficie neutra con bordo discreto, icone ad alto contrasto e area di tocco
di almeno 48 dp. Le azioni rapide delle schede usano il colore del tema solo
per icona e bordo dello stato attivo; non hanno un riempimento rosso pieno.
La posizione e i percorsi del pannello di tracking restano quelli esistenti.
