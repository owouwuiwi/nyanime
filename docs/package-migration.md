# Migrazione dei package

Il codice originale aggiunto a Nyanime usa progressivamente
`it.lorelore.nyanime.*`. Il pannello di tracking e i nuovi controlli delle
notifiche seguono questa convenzione, compresi i relativi test.

Le parti esistenti vengono spostate per responsabilità, quando vengono riviste,
con adattatori nei vecchi punti di ingresso. Una sostituzione globale di
`eu.kanade` non è una migrazione sicura.

## Compatibilità da conservare

- L'application ID e il certificato di firma restano quelli indicati in
  `app-identity.properties`. Cambiarli separatamente dai package Kotlin
  interromperebbe aggiornamenti e accesso ai dati dell'app installata.
- I nomi di worker, servizi, receiver, activity e provider possono essere salvati
  da Android o da WorkManager. Prima di spostarli occorre gestire i lavori già
  accodati, i PendingIntent, i collegamenti e gli eventuali nomi compatibili.
- Campi del backup, numeri Protobuf, chiavi delle preferenze, schemi dei database
  e identità delle fonti restano stabili. Il cambio di package non deve creare
  copie della libreria o perdere progressi.
- Nomi JNI, regole R8, serializzazione e riflessione richiedono verifiche del
  binario ottimizzato, oltre alla compilazione e ai test.
- Codice e namespace di terzi mantengono provenienza, attribuzioni e licenze.
  Spostare un file non cambia i diritti sul suo contenuto.

Ogni passaggio deve avere un ambito limitato e verificabile, mantenendo separati
modello, persistenza, servizi e presentazione. Le regole delle fonti continuano
a provenire esclusivamente dalle estensioni o dalle definizioni dell'utente.
