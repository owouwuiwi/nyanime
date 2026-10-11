# Ripartenza dopo un crash

La schermata di recupero resta in un processo Android separato. «Riapri Nyanime»
avvia nuovamente l’app; «Chiudi l’app» consente di uscire. Non cancella libreria,
preferenze o progressi e non tenta riavvii automatici.

«Condividi rapporto» prepara un file locale e apre il selettore di Android.
Nyanime non invia il rapporto a servizi o destinatari predefiniti. Il file può
contenere informazioni personali: l’invio dipende dalla scelta dell’utente.
Il pannello dei dettagli mostra una selezione limitata a 8.000 caratteri e 60
righe; il rapporto include l’errore completo ricevuto dal processo principale.

La preparazione avviene sul dispatcher IO, senza caricare i gestori delle
estensioni nel processo di recupero. Una ViewModel conserva il lavoro durante
la rotazione, impedisce esportazioni doppie e consente di riprovare dopo un
errore. L’acquisizione dei log ha un limite di otto secondi; file incompleti
vengono rimossi. Chiudere la schermata annulla il lavoro ancora in corso.

La schermata usa risorse locali, spazi adattabili e un contenitore scorrevole,
con temi chiaro/scuro e traduzioni italiano/inglese. L’apertura dei dettagli
rispetta la preferenza per le animazioni ridotte.

Le prove fisiche usano la vera CrashActivity nell’app di benchmark separata:
non provocano un crash nell’installazione personale e non inviano il rapporto.

## Verifica dell’11 ottobre 2026

Sul telefono SM-F741B con Android 16, tre test strumentali hanno verificato
apertura nel vero processo di recupero, dettagli inizialmente chiusi e apribili,
assenza dei dettagli nel tema chiaro ed esportazione fino al selettore Android.
Nove test JVM coprono anteprima limitata, conservazione del rapporto completo,
tocchi ripetuti, errori, tentativi successivi e annullamento.

Sono stati controllati anche riapertura, chiusura, temi chiaro/scuro, larghezza
di 320 dp con caratteri al 130% e scorrimento in orizzontale. I comandi principali
restano raggiungibili; le impostazioni temporanee del telefono sono state
riportate ai valori precedenti.
