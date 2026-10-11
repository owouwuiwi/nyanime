# Interfaccia Nyanime

Nyanime introduce una presentazione da catalogo video: fondo nero, superfici neutre,
accenti rossi, copertine grandi e pulsanti di riproduzione ben visibili. La Home
mantiene tutti i dati dichiarati dalle estensioni, ricerca, filtri, calendario,
paginazione, cronologia e aggiornamenti. Le schede compatte consentono di aprire
le informazioni complete. La ripresa conserva avanzamento, apertura della scheda,
elementi nascosti e ripristino.

Il nome e l'icona cambiano; identificativo Android, firma, archivi e integrazioni
rimangono compatibili. Il player e la sessione Cast mantengono i controlli esistenti.

## ModernUI e interfaccia legacy

In **Impostazioni > Aspetto > ModernUI** è possibile scegliere l'interfaccia.
ModernUI è attiva per default. Disattivandola vengono ripristinati la Home con
i selettori compatti, le schede e i caroselli precedenti, l'intestazione dei dettagli,
la navigazione Material e la presentazione precedente del telecomando Cast.
La scelta sopravvive al riavvio. Ogni modalità mantiene il proprio tema; il tema
legacy parte da quello conservato prima del restyling. Nessuna libreria o cronologia
viene migrata o cancellata cambiando modalità.

La modalità moderna anima l'ingresso delle Home, gli indicatori e la pressione
sulle locandine. La copertina accompagna l'apertura e il ritorno dai dettagli;
sfondi, ombre, testi e pannelli hanno transizioni coordinate. Il banner cambia
profondità durante lo scorrimento. Non sono
presenti animazioni decorative permanenti; le animazioni temporizzate Compose
rispettano la scala di animazione del sistema.

Le categorie della Home sono centrate quando entrano nella larghezza disponibile
e restano scorrevoli con testo grande o schermi stretti. Il refresh fluttuante
delle schermate anime compare solo trascinando per aggiornare, senza comparire
durante le aperture o gli aggiornamenti automatici. L'icona usa la N rossa
originale, anche nelle build preview e nella variante monocromatica Android.

## Copertine e lavoro in background

Le Home possono ricevere titoli privi di copertina. Per le schede visibili, quando
un'immagine manca o il suo indirizzo non è più valido, l'app richiede i dettagli
alla stessa estensione installata. Non ricava indirizzi dal titolo e non contiene
parser di siti. Il recupero usa al massimo due richieste simultanee, riutilizza
i risultati per lo stesso titolo e si interrompe quando la schermata si ferma.
La cache è limitata e il recupero manuale permette di ignorare un risultato scaduto.

Anche i caricamenti delle sezioni si interrompono uscendo dalla Home, mantenendo
schede e date selezionate per la ripresa. I dati locali di “Continua a guardare”
restano indipendenti dalle richieste remote. I log del player distinguono le
transizioni di buffering dalle animazioni; la causa di uno stallo specifico
richiede una verifica sul dispositivo.

## Manga

L'aggiornamento conserva il tema precedente per libreria, dettagli, esplorazione
e lettore manga. La preferenza dedicata in Aspetto permette di modificarlo.
La migrazione viene applicata una sola volta e preserva le scelte successive,
la modalità chiara/scura e l'impostazione AMOLED.

Con un'estensione manga che dichiara una Home compatibile, la scheda Manga offre
Home e Biblioteca. Sezioni, classifiche, date e azioni dei capitoli provengono
dall'estensione; Continua a leggere usa la cronologia locale. Senza questa capacità
si apre la biblioteca esistente. Il [contratto manga](manga-home-api.md) descrive
le regole, mantenendo tema, lettore e avanzamento indipendenti.

## Riferimento visivo

Il progetto di riferimento è [flutter_netflix](https://github.com/angjelkom/flutter_netflix)
di Jack (licenza MIT). La presentazione è implementata in Kotlin e Compose e usa
le immagini del catalogo o delle estensioni. Il marchio e l'icona dell'app sono Nyanime.

## Verifica locale delle anteprime

Le anteprime usano il renderer Compose sul computer, senza avviare emulatori:

```sh
./gradlew :app:updateDebugScreenshotTest
```

La variabile opzionale `NYANIME_PREVIEW_ARTWORK` può indicare una cartella locale
con `poster-0.jpg`, `poster-1.jpg` e `poster-2.jpg`. Le immagini vengono usate solo
dal codice di anteprima. In assenza di questi file vengono renderizzate copertine
colorate. I render locali non sono inclusi nell'app o nel repository.

I test `NyanimeThemeMigrationTest` verificano la conservazione delle preferenze
durante aggiornamento e successiva ricreazione dell'applicazione.
