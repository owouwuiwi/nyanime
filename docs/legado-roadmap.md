# Legado — integrazione futura

Il 23 ottobre 2026 l’integrazione Legado è stata accantonata su richiesta dell’utente.
Non fa parte della consegna corrente di EPUB/TXT, fonti Romanzi e Studio Romanzo.
Non è disponibile un comando di importazione Legado nell’app.

## Fondazioni conservate

Il modulo sperimentale `core/legado` comprende importazione dichiarativa, diagnosi di compatibilità,
valutazione limitata delle regole e costruzione delle richieste, con 49 test JVM passati.
Non è una dipendenza dell’app e non registra fonti. Le successive bozze del runtime sono conservate
separatamente in locale; non costituiscono una funzionalità completata o distribuita.

## TODO prima di una futura consegna

- Completare e verificare l’adattatore NovelSource, la paginazione e il contenuto HTML/JSON.
- Collegare rete mediata e JavaScript isolato, senza accesso arbitrario alle API Android.
- Archiviare definizioni, autorizzazioni e riferimenti in uno spazio privato per fonte,
  mantenendo identità e progressi durante gli aggiornamenti esplicitamente approvati.
- Separare credenziali e definizioni esportabili; definire backup, ripristino e Cloud compatibili.
- Creare l’importazione guidata con anteprima, duplicati, aggiornamenti e consensi distinti.
  Le capacità mancanti o non supportate devono impedire un’attivazione incompleta.
- Verificare annullamenti, limiti, risposte obsolete, portabilità e ripristino sul telefono.
- Aggiornare la parte Legado dell’issue #5 solo dopo le relative verifiche.

La ripresa richiederà una nuova pianificazione. Nessun sito, catalogo o regola di una fonte
deve essere incorporato nel core.
