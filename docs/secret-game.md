# Sandspiel: gioco segreto locale su NyanimeB

In Info, cinque tocchi sul numero di versione aprono Sandspiel. Una pausa di
due secondi, l'uscita dalla schermata, il background o una pressione prolungata
azzerano la sequenza. La pressione prolungata mantiene la copia accessibile
delle informazioni diagnostiche. Il gesto viene consumato prima della navigazione.

Il modulo `:feature:secret-game` espone soltanto il contenuto Compose e il
riconoscitore del gesto. Un contratto interno separa inizializzazione, comandi,
ciclo di vita, checkpoint e rilascio dal motore Sandspiel. Nessuna dipendenza
del gioco è avviata dalla Home, dal player, da SM o dal lettore.

## Esecuzione e dati

La WebView nasce soltanto aprendo il gioco. `WebViewAssetLoader` serve una lista
chiusa di quattro risorse HTTPS locali; ogni altra richiesta restituisce 403.
File/content access, finestre, navigazioni esterne e rete sono disabilitati.
Il documento applica una CSP senza servizi remoti. Non sono inclusi community,
account, telemetria, annunci, font upstream o service worker.

La CSP ammette `unsafe-eval` esclusivamente nel documento locale: regl genera
le proprie funzioni WebGL con `Function`. `wasm-unsafe-eval` da solo non basta.
Non sono ammessi script inline, contenuti remoti o codice ricevuto dal ponte;
la lista chiusa delle risorse e il blocco di rete restano attivi. Gli errori
registrano fase e motivo limitati in logcat, senza includere la creazione.

Il ponte accetta messaggi versionati solo dal frame principale e dall'origine
locale, con dimensioni limitate. I comandi sono enumerati; non esiste accesso a
fonti, estensioni, cronologia, libreria o credenziali. Il ciclo grafico e la
simulazione restano JavaScript/WASM, senza messaggi Android per fotogramma.

L'input dei fluidi riceve materiale, raggio e pausa tramite una callback locale
dell'adattatore. Non dipende dall'interfaccia React originale, rimossa dal gioco
incorporato: il suo vecchio singleton veniva consultato soltanto trascinando il
dito e causava un errore durante la simulazione, dopo un avvio riuscito.

Il mondo conserva le dimensioni originali 300×300. La risoluzione grafica segue
il display fino a DPR 2; nessun algoritmo degli elementi viene semplificato.
La simulazione è limitata a 60 passi al secondo, anche sui display a 120 Hz.
In pausa non esiste un ciclo RAF inattivo: disegnare avvia soltanto i frame
necessari. Background e uscita fermano frame e timer; la sospensione della
WebView è locale, senza `pauseTimers()` globale. Perdita del contesto WebGL o
arresto del renderer mostrano un recupero esplicito senza cancellare il file.

Una sola creazione è conservata in `files/secret-game/sandspiel.scene`:
composizione cellulare, materiale e pennello. Il salvataggio usa checksum
SHA-256, validazione degli enum e delle dimensioni, file temporaneo con fsync e
sostituzione atomica. Un input incompleto o non valido non modifica il file
precedente. La riapertura riparte sempre in pausa e reinizializza i fluidi;
non promette di conservare velocità e pressione transitorie. Sono disponibili
cinque pennelli, gomma, pausa, annullamento e nuovo mondo con conferma.

Le modifiche ferme vengono salvate dopo 800 ms, una simulazione attiva ogni
15 secondi, oltre ai checkpoint per pausa/background/uscita. Indietro attende
la conferma di scrittura; se fallisce permette di restare o uscire esplicitamente.
Come per ogni salvataggio periodico, un arresto forzato del processo può perdere
le modifiche successive all'ultimo checkpoint completato.

## Provenienza e manutenzione

- Submodule originale: [Sandspiel](https://github.com/MaxBittker/sandspiel),
  commit `9936e06b07a583aedfba9589c0cead2917f405fb`, MIT, Max Bittker, 2018.
- Simulazione fluidi incorporata: Pavel Dobryakov, MIT, 2017.
- Licenze JS e Rust incluse come testi completi e raggiungibili da Info →
  Licenze delle librerie. La distinta dei sorgenti e gli hash degli asset sono
  inclusi nell'APK. I font del sito originale non sono distribuiti.
- [Procedura di build e aggiornamento](../tools/sandspiel/README.md).

Il submodule resta intatto. Gli adattamenti controllati lavorano su una copia
generata e una revisione incompatibile fallisce in modo esplicito. Le normali
build Gradle verificano risorse già compilate e non aggiornano upstream.
Verificano anche la revisione effettiva quando è presente il submodule Git;
negli archivi di sorgenti privi di `.git` restano obbligatori tutti gli hash.

## Verifica

Test JVM: cinque tocchi, timeout, reset, apertura singola, round trip atomico,
checksum, file incompleti, enum non validi e trasferimenti limitati/ordinati.
Test Node sul WASM reale: caduta della sabbia, materiali, annullamento, salvataggio
e coordinate; controllo dell'assenza dei servizi esclusi nel bundle.

Per il collaudo Android: aprire da Info, disegnare con tutti i pennelli,
provare reazioni e annullamento, chiudere/riaprire, ruotare, passare in background,
verificare file corrotto e riprovare dopo errore grafico. Misurare memoria e frame
con gioco attivo, in pausa e chiuso; controllare il player/SM e il lettore dopo
uscite ripetute. La compilazione e i test del browser non sostituiscono questa
prova sul dispositivo. Le evidenze effettive sono registrate a fine collaudo.

Il probe Android isolato usa gli stessi asset, CSP e adattatore del modulo:
`./gradlew :feature:secret-game:assembleDebugAndroidTest`. Installare il suo APK
di test su un telefono e avviare
`adb shell am instrument -w nyanime.secretgame.test/nyanime.secretgame.SecretGameDeviceProbe`.
Verifica avvio WASM/WebGL, ponte, trascinamenti touch durante la simulazione,
ritorno in primo piano e checkpoint con ripristino in pausa,
senza accedere ai dati dell'app principale. Disinstallare il solo APK di test
dopo la prova. Il probe non è incluso nell'APK di NyanimeB.

### Controlli già eseguiti il 19 ottobre 2026

- 8 test JVM del modulo, ripetuti nella build condivisa aggiornata: superati.
- 6 test Node sul WASM effettivo e 12 test del catalogo licenze: superati.
- Runtime locale nel browser: disegno continuo, caduta della sabbia, acqua,
  cancellazione e ripristino di un checkpoint da 480.041 caratteri verificati.
  Il materiale selezionato ritorna con il disegno e la simulazione resta in pausa.
- Nel controllo prolungato in pausa, il contatore è rimasto a 3.605 fotogrammi
  per 716.057 ms, senza un nuovo ciclo grafico.
- Probe Android isolato sul Samsung SM-F741B USB, WebView 157.0.8103.5:
  avvio degli asset effettivi con CSP, WASM e WebGL, comandi tramite ponte
  nativo, checkpoint e riapertura con contenuto identico in pausa: superati.
- La prima prova dell'APK completo ha rilevato un blocco CSP della generazione
  delle funzioni regl. Riprodotto con l'HTML distribuito e corretto nel solo
  documento locale; il probe Android verifica anche questo percorso.
- La prova successiva con trascinamenti touch e simulazione attiva ha rilevato
  l'accesso residuo a `window.UI.state` nell'input dei fluidi. L'adattatore ora
  riceve lo stato dei controlli tramite callback, senza ripristinare la vecchia UI.
- Probe Android aggiornato sullo stesso telefono USB: trascinamenti reali durante
  la simulazione, sospensione/ripresa, checkpoint e ripristino in pausa superati.
  Il confronto verifica i tre byte della composizione di ciascuna delle 90.000
  cellule e l'azzeramento del solo byte transitorio di generazione; verifica anche
  che i tocchi abbiano effettivamente disegnato e conserva materiale e pennello.
- APK completo: apertura con cinque tocchi, disegno in movimento con acqua e lava,
  cambio pennello, pausa, annullamento, conferma di cancellazione annullata,
  ritorno dal background e tre chiusure/riaperture consecutive superati. Ogni
  riapertura ritorna in pausa; nessun nuovo errore `SecretGame` nel processo host.
- Il probe isolato funziona anche senza il permesso Android `INTERNET`.

APK firmato di prova: **0.48.2.20**, `versionCode` **276**, sorgenti runtime dal
commit `3580ef0f4f38b0eda448b86947a40fd22caf4d95` (fix gioco `86bb4960c1`).
SHA-256: `1661cbcf710b0a2795df1f0d89d8341b5f51a50b0435cf9a5917942ad0492212`.
Firma, guardie ABI ViewModel/Compose, otto asset con distinta di provenienza e
catalogo delle attribuzioni verificati. I 107 test JVM selezionati del consolidato
(gioco, player/stanze e Syncplay) sono superati, senza errori o test saltati.

Non è stata eseguita una misura comparativa completa di tempi GPU e memoria.
La Main del telefono rimane in verticale anche con la rotazione di sistema:
quel tentativo non viene conteggiato come collaudo orizzontale. Le impostazioni
originali di rotazione sono state ripristinate. Nessun push o pubblicazione OTA.
