# Avvio di Smart e timer di sospensione

## Pressione prolungata nel player

Durante la riproduzione individuale, tieni premuto sul video per passare
temporaneamente a **2×**. L'indicatore compare finché il gesto è attivo; al rilascio
si ripristina la velocità precedente, anche se diversa da 1×. La preferenza di
velocità non viene modificata.

Il ripristino avviene anche se il gesto viene annullato, metti in pausa, cambi
episodio o esci dal player. I controlli bloccati e il video già in pausa non
attivano il gesto. Nelle stanze il 2× temporaneo è disabilitato per mantenere la
sincronizzazione; il telecomando Cast conserva i suoi controlli. Gli screenshot
restano disponibili nei controlli del player, senza aprirsi tenendo premuto.

## Comandi essenziali

La nota musicale e il tasto **HQ** sono nascosti dalla barra per impostazione
predefinita. **Altro > Audio / Qualità video** apre direttamente le rispettive
funzioni; **Altro > Personalizza comandi** permette di riabilitare separatamente
i due tasti, con scelta conservata anche alla riapertura dell'app. La qualità
video è disponibile per gli episodi online. **SM** resta sulla barra.

**Altro > Fotogramma** mette in pausa e apre le azioni per salvare, condividere
o usare l'immagine come copertina. Chiudendo il pannello il video resta in pausa.

## Avvio automatico di Anime4K Smart

In **Impostazioni > Lettore interno > Anime4K > Avvia Smart automaticamente**
puoi scegliere se Smart si attiva all'apertura degli episodi. L'opzione è **attiva
di default**, come il comportamento iniziale di Nyanime.

Disattivandola, un episodio che avrebbe avviato Smart parte con Anime4K spento,
anche se aveva una scelta Smart salvata. Il pulsante **SM** resta disponibile per
attivarlo manualmente. Le scelte manuali Spento, Massimo e Personalizzato mantengono
il proprio comportamento; un cambio qualità o recupero dello stream conserva
la scelta della sessione attuale.

L'interruttore regola l'avvio, non cambia l'algoritmo. Nelle stanze Anime4K è
temporaneamente disabilitato in tutte le modalità, indipendentemente da questa
preferenza. Uscendo viene recuperata la scelta prevista per la visione individuale.

## Timer dal player

Apri **Altro > Timer di sospensione** nel player. Il pannello si adatta allo spazio
disponibile e offre:

- durate rapide di **15, 30, 45, 60, 90 e 120 minuti**;
- durata personalizzata da **1 a 1439 minuti**, con memoria dell'ultima scelta;
- **Alla fine dell'episodio**;
- tempo residuo, **+15 minuti** per un timer a tempo attivo, cambio durata e annullamento.

Il timer misura il tempo trascorso con un orologio monotono: continua a scendere
anche durante pause e buffering e non dipende dalle modifiche dell'ora di sistema.
Alla scadenza mette in pausa il video. Non blocca il telefono né simula il tasto
di accensione; l'eventuale spegnimento del display dipende da Android.

Una durata a tempo può attraversare più episodi nella stessa sessione del player.
“Alla fine dell'episodio” è legato all'episodio corrente: scegliendone un altro
manualmente quella modalità si disattiva. Il timer attivo non è un allarme
persistente dopo la chiusura della sessione o del processo.

## Autoplay e stanze

La scadenza del timer ha priorità sul prossimo episodio, anche quando il callback
di autoplay arriva prima dell'aggiornamento visivo del residuo. Il conto alla rovescia
e “Riproduci ora” rispettano l'arresto; ripartire richiede una nuova azione locale.

In una stanza la scadenza crea una pausa locale protetta. Un Play remoto non
rimuove questa protezione; premi Play sul tuo telefono quando vuoi riprendere.
L'attesa degli altri e la ripartenza comune seguono poi le normali regole della stanza.
