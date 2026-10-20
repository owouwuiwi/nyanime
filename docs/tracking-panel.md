# Pannello di tracking

Il pulsante Tracking nelle schede anime e manga apre un pannello comune con stato,
progresso, voto e dettagli personali. Non serve un account per usarlo: **Questo
dispositivo** conserva un record locale, distinto dai servizi collegati.

## Uso

- Gli stati disponibili dipendono dal servizio selezionato. Il record locale offre
  in corso, ripetizione, completato, in pausa, abbandonato e da iniziare.
- I pulsanti meno e più cambiano il progresso. Toccando il numero si può inserire
  un valore; nei manga sono ammessi anche capitoli frazionari.
- Le barre permettono di assegnare un voto. Il numero personale resta separato
  dalla distribuzione della community, quando disponibile. Toccando il voto in
  alto si accede anche alla scala precisa supportata dal servizio.
- Altro raccoglie date, riservatezza se supportata, riletture/rivisioni, volumi,
  note e tag personali. Note, tag, ripetizioni e volumi sono conservati sul
  dispositivo, anche quando è selezionato un tracker.
- Le modifiche si salvano automaticamente. **Annulla** ripristina soltanto i
  campi dell'ultima modifica che non sono stati cambiati successivamente.
- **Servizi** mantiene ricerca, associazione, apertura e rimozione dei collegamenti
  esistenti. Non associa opere diverse in base alla sola somiglianza del nome.

Completare un record può portarne il progresso al totale conosciuto. Modificare il
tracking non segna episodi o capitoli locali come visti/letti e non cambia il
minutaggio o la pagina di ripresa. La visione/lettura effettiva continua ad
aggiornare i record personali già creati, secondo le preferenze di tracking.
Un vecchio progresso osservato non annulla un arretramento manuale.

## Salvataggio e servizi

I record locali usano un'identità portabile composta da tipo di contenuto,
identificativo numerico della fonte e riferimento del titolo, senza regole
specifiche di siti. Sono inclusi nei backup con libreria e tracking selezionati;
i vecchi backup senza questi campi restano leggibili.

Le modifiche remote sono patch dei soli campi cambiati, conservate in SQLite prima
della consegna e associate all'account e al collegamento originali. WorkManager
ne gestisce la ripresa quando c'è rete. Lo stato distingue salvataggio locale,
attesa di sincronizzazione ed errore; sono disponibili Riprova e Scarta.
Un account cambiato non riceve le modifiche accodate per un altro account.

Una risposta in ritardo conferma soltanto i valori effettivamente inviati:
nuove modifiche e Annulla restano in coda. Aggiornamenti automatici e refresh
condividono il coordinamento per record; le richieste superate non devono
ripristinare progressi manuali precedenti.

I lavori di rete pendenti sono specifici dell'installazione e non vengono
esportati come operazioni da eseguire su un altro telefono. Prima di trasferire
un backup, verificare che i tracker risultino sincronizzati. I dati personali
locali, comprese note e tag, viaggiano invece nel backup.

Collegare un account non sovrascrive automaticamente il record locale.
La copia dei valori locali verso un tracker richiede una conferma con anteprima
di stato, progresso e voto.

## Community e privacy

La distribuzione dei voti proviene da AniList solo quando esiste un ID verificato
e sono abilitate le informazioni pubbliche sulle opere. Non vengono inventate
barre statistiche quando mancano i dati. L'ID pubblico è l'unico riferimento
inviato per questa richiesta: nessun URL delle fonti, nota o progresso personale.
La cache è limitata e riusa l'archivio delle informazioni sulle opere.

In incognito le modifiche personali durano solo per la sessione; i servizi remoti
restano in sola lettura e i nuovi dati della community non sono salvati in cache.
Solo scaricati impedisce nuove richieste di distribuzione dei voti.

## Architettura e provenienza

Il modello, l'archivio personale, le patch, il gateway dei tracker e la
presentazione Compose sono separati in `it.lorelore.nyanime.tracking`.
L'archivio personale non registra un tracker fittizio nel gestore dei servizi.
Non sono introdotti cataloghi di estensioni né associazioni basate su nomi di fonti.

Il riferimento di prodotto è il pannello mostrato da
[Karasu](https://github.com/Suzora/Karasu), in particolare stati, progresso,
distribuzione dei voti e Annulla. Questa implementazione è scritta per la
presentazione nativa di Nyanime: non importa codice, componenti web o asset di
Karasu. Le licenze dei componenti già presenti nell'app restano invariate.
