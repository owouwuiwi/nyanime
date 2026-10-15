# Aggiornamenti e storico

La schermata dell'aggiornamento mostra la versione installata, quella disponibile,
il riepilogo delle modifiche e una cronologia espandibile. Download, verifica e
installazione usano la procedura esistente e continuano se si esce dalla pagina.
La cronologia aggiuntiva viene richiesta separatamente al publisher della release:
non ritarda il download. Sono incluse le release Android successive alla versione
installata, fino alla versione scelta, rispettando il canale degli aggiornamenti.
Alias di compatibilità, bozze e release per altri dispositivi vengono esclusi.
Se il recupero non riesce, restano visibili le note già ricevute e il collegamento
alla cronologia pubblica; non viene presentata una cronologia parziale come completa.

## Formato delle note

`scripts/release_notes.py` pubblica soltanto le voci aggiunte dopo la release
precedente. La riclassificazione di una voce storica non la ripubblica nell'OTA.
Le note nuove contengono il marcatore `<!-- nyanime-changelog:1 -->`, titoli di
versione `###` e categorie `#### Novità`, `#### Correzioni`, `#### Prestazioni`,
`#### Interfaccia`, `#### Compatibilità`, `#### Sicurezza`, `#### Distribuzione`,
`#### Verifiche`, `#### Limitazioni`. Ogni gruppo ha intestazione, icona e colore;
le categorie aggiuntive compaiono soltanto quando contengono note.
Le categorie vengono dichiarate nel changelog
con titoli `###`; nessuna classificazione viene dedotta dalle parole delle frasi.

Anche per una pubblicazione manuale, generare `notes.md` con
`scripts/release_notes.py`: non copiare direttamente una sezione di `CHANGELOG.md`.
Il formato del file sorgente usa livelli di intestazione diversi da quello OTA.
Preparazione del pacchetto e pubblicazione verificano marcatore, versioni e
categorie prima di accettare le note; un testo non strutturato viene rifiutato.
Una correzione alle sole note di una release già pubblicata non richiede un nuovo
APK: mantenere firma, versione, canale e file invariati, poi verificare il corpo
pubblicato e la suddivisione nella pagina aggiornamenti.

L'app conserva Markdown, liste annidate, paragrafi e codice. I conteggi comprendono
soltanto le voci della lista principale. “Mostra tutte” accorcia soltanto liste
separabili, conservando ogni voce completa. I vecchi corpi delle release privi del
marcatore conservano il testo originale sotto «Note originali», senza classificazioni
inferite. Anche una categoria sconosciuta è conservata con questa intestazione,
senza diventare una voce della categoria precedente. Lo storico incluso nell’APK
è revisionato esplicitamente: nessuna nota documentata rimane senza categoria.

## Consultazione offline

Impostazioni → Info → Cronologia aggiornamenti apre il changelog revisionato incluso
nell'APK, con lo stesso stile della pagina OTA. “Novità” apre la versione installata.
Il file è generato da `CHANGELOG.md` durante la build: non esistono due copie da
mantenere. Lo storico documenta anche revisioni che non sono state distribuite;
il relativo avviso distingue questo archivio dalle release pubblicate su GitHub.

La pagina rispetta temi e animazioni ridotte, dispone versioni e riepiloghi anche
con testi grandi e mantiene le espansioni durante la rotazione e il ritorno da una
schermata. Lo storico non modifica dati, preferenze o impostazioni delle estensioni.
