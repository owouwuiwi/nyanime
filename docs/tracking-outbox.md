# Invii ai tracker

Da **Impostazioni → Tracking → Invii ai tracker**, oppure dal pannello Tracking
di una scheda, si può controllare la coda degli aggiornamenti remoti. Anime e
manga usano lo stesso archivio; ogni richiesta conserva servizio, account,
collegamento al catalogo e identità esatta del titolo.

## Stati e azioni

- **In attesa**: la modifica è salvata sul telefono. WorkManager richiederà la
  rete prima di tentare l'invio; Android può rinviare il lavoro in background.
- **Invio in corso**: viene prima letto lo stato remoto, poi vengono applicati
  soltanto i campi modificati. I tocchi successivi restano distinti dalla
  richiesta già in corso.
- **Stato remoto da verificare**: la connessione si è interrotta o non è arrivata
  una conferma. Il nuovo tentativo rilegge il servizio: se i valori richiesti sono
  già presenti non li riscrive.
- **Limite del servizio**: viene rispettato Retry-After, anche per le altre
  richieste dello stesso tracker. Gli altri servizi possono proseguire.
- **Da verificare**: accesso scaduto, account o associazione cambiati, valori non
  accettati oppure un conflitto richiedono un intervento. Non producono una
  sequenza continua di richieste.

Sono disponibili ricerca, filtri, apertura della scheda, Riprova, rimozione
esplicita e Annulla quando applicabile. L'attività recente conserva al massimo
128 ricevute, oltre a quelle ancora necessarie agli invii pendenti o in corso;
la schermata mostra le 40 più recenti già concluse.

Un errore di rete o 5xx usa un'attesa crescente da 30 secondi, fino a sei ore; un Retry-After più lungo viene rispettato fino a sette giorni.
Dopo sei tentativi della stessa richiesta l'automatismo si sospende senza
eliminarla. Un nuovo invio non eredita il limite dei tentativi di un vecchio
errore. Riprova non aggira il limite dichiarato dal servizio.

## Annulla e conflitti

Prima dell'invio, Annulla può eliminare i campi non più necessari. Se l'invio è
già confermato, accoda una correzione dei soli campi ancora appartenenti a quella
modifica. Una revisione persistente per campo protegge anche la sequenza locale
A → B → A: l'uguaglianza dei valori non autorizza ad annullare una scelta più
recente. Se una richiesta in corso potrebbe essere stata applicata, vengono
considerati sia il valore inviato sia la base effettivamente letta dal servizio.

Una correzione manuale può ridurre il progresso. L'autotracking, invece, non
abbassa un progresso remoto più alto e non scavalca una correzione manuale in
attesa. Entrambi passano dallo stesso gateway e dallo stesso archivio.

Quando il servizio contiene una modifica incompatibile dello stesso campo,
l'invio viene sospeso. La copia remota viene aggiornata nella cache solo se il
collegamento locale non è cambiato. Per conservare il valore remoto, rimuovere
l'invio; per applicare una nuova scelta, riaprire la scheda e modificare il campo.
Le modifiche remote di altri campi restano conservate.

I tracker privi di revisioni remote o aggiornamenti condizionali non permettono
un confronto atomico distribuito: un altro client può ancora intervenire tra la
lettura e la scrittura. Una sequenza remota che torna allo stesso valore non è
distinguibile dalla sola uguaglianza dei valori. Nyanime non inventa una garanzia
che l'API del servizio non offre.

## Account, vecchie code e privacy

Le nuove richieste conservano una sessione dell'account. Dopo un nuovo accesso
allo stesso profilo può essere richiesta una conferma; un account diverso non
riceve gli invii del precedente. Per i tracker collegati a una fonte, una
variazione della configurazione blocca i vecchi invii: occorre verificare il
collegamento dalla scheda. Le impronte non contengono le credenziali in chiaro.

La vecchia coda automatica condivideva chiavi numeriche tra anime e manga e non
conservava l'account originale. I suoi valori vengono trasferiti una sola volta
in un elenco da verificare: prima dell'invio bisogna scegliere esplicitamente
titolo e destinazione, con anteprima dell'avanzamento. Nessuna scelta viene
dedotta dal numero o dal nome del titolo.

Incognito globale e della fonte impediscono nuovi invii. La coda, le ricevute e
le operazioni in corso restano legate a questa installazione e non vengono
esportate per essere rieseguite durante backup o sincronizzazione. I record
personali locali mantengono invece il normale supporto ai backup.

## Architettura e provenienza

Il codice è in `it.lorelore.nyanime.tracking`: policy e riconciliazione pure,
SQLite, gateway dei servizi, pianificazione WorkManager e presentazione Compose
restano separati. I nomi dei vecchi Worker rimangono per migrare i lavori già
pianificati; i vecchi scrittori privi di identità sono rimossi.

La funzione sviluppa il pannello Nyanime esistente. Karasu è un riferimento di
prodotto: non vengono importati codice, test, algoritmi espressi nei sorgenti,
asset o runtime del progetto. Non sono aggiunte dipendenze né regole specifiche
dei siti; restano valide le licenze e le attribuzioni già presenti nell'app.
