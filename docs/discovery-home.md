# Home anime e selezione automatica delle fonti

Le integrazioni per singole fonti, i relativi pacchetti e i dati raccolti da siti
si mantengono fuori dal repository dell'app. Gli esempi e i test del contratto
usano nomi e dati fittizi. Eventuale materiale privato nel checkout appartiene
alle directory locali ignorate `.local/` o `private/`.

La Home aggiunge catalogo pubblico AniList, cronologia locale, nuovi episodi rilevati e feed
delle estensioni. Il player, Anime4K, package, firma e aggiornamento dell'app restano quelli del fork.

## Uso

La navigazione iniziale è Home / Anime / Manga / Esplora / Altro. Aggiornamenti e Cronologia
sono accessibili dalla Home e da Altro. La migrazione si applica una sola volta: in Impostazioni
rimangono selezionabili le schermate iniziali e le disposizioni classiche.

Una copertina AniList apre la scheda; **Apri episodi**, **Riprendi** e **Aggiungi alla libreria**
risolvono automaticamente una versione nelle estensioni installate e abilitate.
**Cambia versione / Cerca nelle fonti** è una correzione facoltativa, non un passaggio obbligatorio.
Non vengono installate estensioni, aggiunti tracker o aggirati blocchi delle fonti.

## Regole del resolver

1. Riutilizza il collegamento locale o una corrispondenza locale univoca AniList/MAL.
   Rivalida che la fonte sia installata, visibile e in una lingua abilitata.
2. Cerca su richiesta, avviando prima ultima fonte e preferite; massimo tre ricerche concorrenti.
   Usa titolo, titoli alternativi e titolo base. Appena una versione supera la verifica,
   annulla le ricerche ancora in corso. Limite complessivo 60 secondi, verifica singola 15 secondi.
3. Normalizza accenti, punteggiatura e qualificatori sub/dub; numeri di stagione/parte
   incompatibili e anni espliciti differenti non sono equivalenti.
4. Controlla dettagli ed episodi reali. Un contenitore con stagioni apre il figlio corrispondente.
   Una stagione non viene scelta soltanto perché è l'unica presente.
5. Una raccolta unica rimane unica. Per accettarla come seconda/ulteriore stagione servono
   titolo base compatibile, una catena AniList non ambigua di prequel TV/ONA conclusi con
   conteggi noti e una numerazione continua della fonte che copra il totale necessario.
   Episodi speciali o duplicati non sono prova sufficiente.
6. Salva l'associazione opera -> URL/fonte, salvo modalità incognito. Le associazioni a fonti
   temporaneamente disabilitate non vengono utilizzate né distrutte.

È un'euristica controllata, non una garanzia d'identità universale: titoli non standard,
raccolte con numerazione che ricomincia e prequel privi di metadati possono richiedere
la correzione facoltativa. Non vengono inventati offset o riscritti i progressi.
La versione concreta della fonte mantiene la propria identità e il proprio ordinamento.

## Architettura

- `domain/discovery`: modelli, contratti, scadenze, identità, confronto titoli e prove sulle raccolte.
- `data/discovery`: repository cache e persistenza SQLDelight.
- `app/data/discovery`: adattatore GraphQL, estensioni, risoluzione, sezioni locali e ripresa.
- `app/ui/discovery`: ScreenModel e navigazione; `presentation/discovery`: componenti Compose.
- `DiscoveryModule`: composition root Injekt.
- `discovery.db`: database separato dagli archivi anime/manga upstream. Nuovi cambi di schema
  devono avere migrazioni nella propria directory SQLDelight, senza riutilizzare le migrazioni upstream.

Cache: feed 30 minuti, calendario 15 minuti, dettagli 24 ore; fino a 100 pagine e 300 schede.
Cache immediata, refresh indipendente, errori per sezione, richieste duplicate accorpate.
Il calendario rappresenta la messa in onda nel fuso locale, non la disponibilità nell'estensione.
Il budget AniList è condiviso col tracking, inizialmente circa 28 richieste/minuto,
ridotto dagli header del servizio e sospeso in caso di 429.

Il catalogo usa un client API senza verifiche WebView: un 403 JSON non è un CAPTCHA.
Se AniList dichiara l'API temporaneamente disattivata, il trasporto sospende per 60 secondi
anche le richieste delle altre sezioni in attesa. La Home mostra un solo avviso, conserva
le righe già in cache e lascia utilizzabili cronologia, libreria e fonti. Il retry manuale
rispetta la sospensione; nessun proxy o tentativo di aggirare il blocco del servizio.

### Catalogo alternativo automatico

Se il catalogo pubblico AniList non risponde, i nuovi feed usano l'API pubblica Kitsu.
La provenienza effettiva è indicata in Home e sulle schede; voti Kitsu non sono voti AniList.
Cache, collegamenti, schede e opere correlate usano identità composte da provider e ID.
Le pagine successive restano sul provider della prima pagina anche se AniList torna online.
La ricerca nelle estensioni e il player non cambiano. I mapping MAL provengono esclusivamente
dai collegamenti espliciti del catalogo; i numeri ID uguali tra cataloghi non sono corrispondenze.
Il ripiego non sostituisce una scheda AniList con una scheda Kitsu dal medesimo numero ID.

Kitsu offre trend, stagioni, classifica, ricerca, banner e schede; non fornisce in questo
adattatore il calendario dettagliato equivalente a quello AniList. Il calendario presenta
un avviso di indisponibilità, senza inventare episodio o orario. Richieste Kitsu serializzate,
intervallo minimo 1,1 secondi, timeout e rispetto della sospensione in caso di 429.

La modalità solo download impedisce richieste al catalogo e alle fonti; la ripresa usa soltanto
episodi scaricati. La modalità incognito disabilita la cache persistente del catalogo,
nasconde le sezioni locali sensibili e impedisce di ricordare nuove associazioni.
Le cache immagini continuano a seguire il comportamento del caricatore immagini dell'app.

## Home delle estensioni

Le Home opzionali provengono dalle estensioni installate, attendibili e abilitate.
Nomi, sezioni, categorie e filtri appartengono all'estensione; l'app non contiene
un elenco di siti o configurazioni per fonti specifiche.

Le pagine mantengono ricerca, paginazione, selezione e scorrimento indipendenti.
Le copertine aprono le identità locali esistenti, conservando stagioni, episodi,
libreria e progressi. La ripresa usa la cronologia delle fonti disponibili.
Una fonte assente o disabilitata non espone pagine o inviti all'installazione.
Incognito e solo download continuano a limitare richieste e cronologia.

I contratti SourceHomeGateway e SourceHomeRepository separano l'accesso alle fonti
da cache e presentazione. Le richieste usano i metodi pubblici delle estensioni,
filtri indipendenti, caricamento delle righe su richiesta e risultati precedenti
visibili durante il refresh. Gli errori e il retry restano separati per sezione.

## Verifica

Verifica deterministica:

```sh
./gradlew spotlessCheck testDebugUnitTest
```

Probe opzionale che usa **lo stesso adattatore di produzione**, senza account:

```sh
ANIYOMI_VERIFY_CATALOG=true ./gradlew :app:testDebugUnitTest --tests '*AnilistLiveCatalogTest'
```

Il workflow preview conserva separatamente i risultati deterministici e il probe live.
Un errore remoto del probe non nasconde l'esito dei test e non impedisce la produzione dell'APK;
non va interpretato come una verifica positiva del catalogo.

Checklist manuale prima di considerare la funzionalità validata su dispositivo:

- Installare l'APK universale come aggiornamento, verificare package e certificato.
- Telefono/tablet, chiaro/scuro, font ingranditi, immagini progressive.
- Rientro dalla scheda al punto di scorrimento; rotazione e ricreazione del processo.
- Fonti effettivamente installate: titolo univoco, raccolta completa, stagioni separate,
  fonte disabilitata/rimossa, incognito e solo download.
- Ripresa incompleta, episodio terminato, fine serie e download.
- Catalogo reale, ricerca, pagina successiva e calendario; 403/429 e rete assente.
