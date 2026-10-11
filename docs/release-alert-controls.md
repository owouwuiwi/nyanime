# Gestire gli avvisi delle uscite

In **Impostazioni → Notifiche delle uscite → Notifiche per titolo** puoi cercare
anime e manga e spegnere entrambi gli avvisi di un titolo: nuove disponibilità e
promemoria delle date. Questa scelta non rimuove il titolo da libreria, agenda
o tracker.

Per impostazione predefinita, il monitor considera anche titoli in libreria,
collegati a tracker o già iniziati. L'opzione facoltativa **Solo i titoli che
seguo esplicitamente**, inizialmente spenta, limita invece le notifiche a quelli
per cui hai scelto Segui. L'agenda e l'aggiornamento della libreria conservano la
loro selezione; il filtro riguarda gli avvisi.

Le stagioni automatiche ereditano le scelte di avviso della serie. Una scelta
esplicita sulla singola stagione resta un'eccezione. Riattivare un titolo nella
modalità esplicita equivale a seguirlo.

La schermata degli avvisi segnala se Android blocca l'app o i canali pertinenti
e apre le impostazioni di sistema. Al ritorno ricontrolla lo stato, riprogramma
i promemoria e prova a consegnare le disponibilità rimaste in attesa.
Cambiare preferenza non richiede di aggiornare le fonti.

Quando Android blocca le notifiche, la Home mostra bordi gialli e un triangolo
di avviso accanto alla musica. Il pulsante apre una spiegazione e le impostazioni
dell'app o del singolo canale bloccato. Lo stato è ricontrollato al ritorno;
il bagliore non è un'animazione continua e non intercetta i tocchi della Home.

Il monitor usa WorkManager e rispetta rete, incognito, Solo scaricati e
restrizioni del dispositivo. I promemoria usano un allarme per la data più vicina.
Gli allarmi esatti richiedono il relativo accesso Android; senza di esso
l'orario può essere approssimativo. Un arresto forzato o restrizioni del sistema
possono impedire l'esecuzione in background: nessuna promessa di consegna al
secondo è implicita.

Le notifiche disabilitate non vengono considerate consegnate. I promemoria troppo
vecchi non vengono riproposti in massa; gli avvisi di nuove disponibilità
mantengono la coda durevole esistente. È possibile provare separatamente entrambi
i canali dalla schermata delle impostazioni.
