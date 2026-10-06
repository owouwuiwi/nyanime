# Ricerca intelligente dei titoli

Nyanime cerca il testo originale nelle estensioni e, quando mancano risultati
pertinenti, può recuperare titoli con refusi, punteggiatura diversa, parole unite
o nomi alternativi. La ricerca riguarda titoli e alias, non il significato di
descrizioni o temi. Non richiede un aggiornamento delle estensioni.

## Uso e impostazioni

[Atlante](atlas-search.md), Sfoglia e singole fonti condividono lo stesso motore. Sotto il campo di
ricerca compaiono suggerimenti di titolo: toccarne uno avvia una nuova ricerca.
Lo stesso testo compare una sola volta nei suggerimenti, anche quando è noto
a più cataloghi o fonti; le identità dei candidati rimangono distinte nel motore.
Un suggerimento di catalogo non garantisce la disponibilità nell'estensione;
solo i risultati ottenuti dall'estensione possono essere aperti.

Quando una correzione recupera risultati, una nota indica il testo utilizzato.
«Ricerca esatta» disattiva l'aiuto per quella query; «Usa ricerca intelligente»
lo riattiva. Il testo digitato resta visibile. Il candidato plausibile meglio
valutato viene usato anche per recuperare risultati, lasciando visibili le altre
alternative: non occorre toccare un suggerimento per avviare il recupero.
Questo non seleziona un'opera né modifica associazioni o tracking.
Le varianti di spazi e punteggiatura conservano l'ampiezza della query:
`nova loop` può recuperare il frammento dinamico `Nova:Loop`, senza trasformarsi
nel nome completo di una singola stagione. Il recupero prosegue anche se il testo
originale aveva già un risultato pertinente. Le grafie equivalenti note vengono
unite entro il budget; ciascuna mantiene il proprio cursore e viene riutilizzata
durante il refresh. I nomi si ricavano sempre dai titoli e alias disponibili.
Filtri e categorie restano applicati. Se la fonte non restituisce alcun titolo
pertinente, l'app non trasforma i suggerimenti di catalogo in risultati fittizi.

In **Impostazioni → Sfoglia → Ricerca intelligente** puoi disabilitare la
tolleranza, disabilitare soltanto i suggerimenti online o cancellare la cache.
Entrambe le opzioni sono inizialmente attive. Italiano e inglese sono disponibili.
La libreria cerca esclusivamente nei dati locali e conserva ordinamento,
categorie, ricerca di autore/genere, negazioni e sintassi `id:`.

## Responsabilità e contratti

Il modulo [`core/smart-search`](../core/smart-search) separa:

- `TitleMatcher`: normalizzazione e confronto dei nomi completi.
- `SearchCandidateProvider`: titoli incontrati, libreria e cataloghi facoltativi.
- `ExtensionSearchAdapter`: API di ricerca esistenti, filtri e paginazione.
- `SearchSession`: una query utente, budget, suggerimenti e cursori indipendenti.

`LexicalTitleMatcher` mantiene i nomi originali. Confronta forme Unicode
normalizzate, parole separate, forme compatte e una forma secondaria senza
accenti latini. Numeri ed edizioni hanno precedenza: cercare un nome numerato
può proporre anche le sue parti successive, senza aggiungere automaticamente
un numero di stagione alla query. Un numero diverso resta incompatibile.
Un alias senza numero non rende una parte numerata equivalente al titolo base.
I titoli corti richiedono più prudenza. La distanza di modifica considera anche
lettere invertite e refusi in un prefisso seguito da un sottotitolo.

Un numero separato alla fine, anche dopo «season», «stagione», «part» o «parte»,
può indicare una stagione o parte richiesta. Un candidato con quel numero
ha precedenza anche su una somiglianza testuale più forte del titolo base.
Se non esiste un candidato numerato plausibile, si può recuperare il titolo
base riconosciuto, purché il nome completo sia una corrispondenza forte.
Il `1` è favorito come possibile prima stagione non numerata. Un numero inesistente
non blocca quindi la ricerca; il testo digitato resta visibile e la nota indica
la variante usata. Gli altri numeri rimangono invariati: `Synthetic Protocol 47 56`
può recuperare `Synthetic Protocol 47`, ma non `Synthetic Protocol 48`.
Un numero attaccato a una parola o nel mezzo del nome non viene eliminato.
L'interpretazione scelta rimane la stessa durante aggiornamento e paginazione:
un candidato numerato riconosciuto non si trasforma nel titolo base.
È un recupero di ricerca, non una prova di equivalenza tra opere.

`SymSpellTitleIndex` usa SymSpellKt per recuperare candidati dal dizionario
costruito durante l'uso. I candidati vengono verificati contro nomi completi:
parole di opere diverse non vengono assemblate in un titolo inventato.
Prefissi limitati e indice delle parti numeriche riducono le collisioni nei
cataloghi grandi. Non sono distribuiti titoli o dizionari precompilati.

Gli alias facoltativi provengono da metadati generici `nyanime.search.v1` o
`nyanime.tracking.v1` già forniti dal risultato. Il primo contratto accetta nel
campo `memo` JSON versionato, con `aliases` o `titles` come liste di stringhe.
Massimo 15 alias aggiuntivi, 256 caratteri per nome; URL e caratteri di controllo
sono esclusi. L'app tollera estensioni precedenti senza `memo`. Il contratto
arricchisce la ricerca e non aggiunge metodi obbligatori all'API delle fonti.

```json
{"nyanime.search.v1":{"aliases":["Synthetic Alternate Title"]}}
```

La deduplicazione dei risultati usa fonte e riferimento opaco. La somiglianza
lessicale non costituisce una prova di identità tra fonti e non modifica tracking,
AniSkip o associazioni di opere. Le Home conservano il loro merge per ID verificati.

## Richieste, concorrenza e pagine

- Debounce di 350 ms; il comando Cerca invia subito.
- La ricerca originale viene eseguita prima dei recuperi.
- Massimo due tentativi aggiuntivi per estensione e query, mantenendo i filtri.
- Massimo tre richieste di catalogo per query, condivise tra tutte le estensioni.
  In Atlante il budget è condiviso anche tra le sessioni video e manga.
- Kitsu è il primo aiuto; AniList fornisce un'alternativa e alias dinamici.
- Timeout dei cataloghi di 12 secondi; limitazione delle richieste e rispetto
  del backoff del servizio. Nessuna scansione massiva delle estensioni.

I budget restano consumati anche se un tentativo viene interrotto. La concorrenza
di ricerca delle fonti resta quella esistente. Cambiare query cancella le attività
precedenti e le risposte obsolete non aggiornano la schermata corrente.
Anche nella ricerca della Home anime i risultati ricevuti compaiono mentre
altre fonti stanno rispondendo, senza attendere il completamento dell'intero gruppo.

Un aggiornamento della stessa ricerca riutilizza la variante già verificata
nella fonte, senza ripetere la scoperta della correzione. Gli alias condivisi
possono recuperare più risultati, mantenendone distinte le identità.

Ogni variante di query ha il proprio cursore. I tentativi su una sola parola
significativa si fermano alla prima pagina: non aprono una scansione ampia.
Durante un recupero paginato interrotto, le pagine già ricevute restano pendenti
fino al completamento del gruppo, evitando salti e duplicati. Una risposta valida
vuota significa «nessun risultato»; un errore di rete, autorizzazione o HTTP 429
resta un errore e non avvia automaticamente correzioni del testo originale.

## Cache, incognito e dati esterni

La cache usa un file atomico nella cache privata Android, massimo 5.000 titoli
e 3 MB di JSON UTF-8. Memorizza nomi, alias e provenienza; il riferimento della
fonte viene trasformato in un hash per la chiave dell'indice. Non conserva URL
di riproduzione, cookie o credenziali. Un file danneggiato viene scartato e
l'indice ricostruito dai dati disponibili. Cancellarlo non tocca la libreria.

In incognito non vengono scritti nuovi titoli persistenti. Disabilitare i cataloghi
non impedisce la normale ricerca nelle estensioni. Senza rete i cataloghi non
vengono consultati; in modalità «Solo scaricati» non partono neppure ricerche
nelle estensioni. I suggerimenti online, se abilitati e disponibili, trasmettono
la query ai cataloghi pubblici: non sono una ricerca puramente locale.

Le correzioni sconosciute richiedono candidati disponibili localmente o nei
cataloghi: nessun algoritmo può garantire ogni refuso in qualsiasi estensione.
Il motore restituisce uno stato esplicito quando l'aiuto non è disponibile.

## Verifiche e manutenzione

I test versionati usano esclusivamente nomi sintetici. Coprono normalizzazione,
refusi e alias, edizioni numerate, numeri finali non riconosciuti, prima stagione implicita, ambiguità con
recupero esplicito e alternative visibili, originali falliti, budget condivisi,
paginazione interrotta, deduplicazione, offline e cache danneggiata. Test dedicati
confrontano la prima ricerca e le successive con dati runtime già memorizzati,
grafie equivalenti, famiglie di titoli e cursori distinti per più recuperi.
I parser dei cataloghi e la compatibilità dei metadati hanno test dedicati.

La normalizzazione usa anche una cache di 4.096 nomi esclusivamente in memoria;
le distanze lessicali già calcolate vengono riutilizzate nella stessa valutazione.
Il recupero dei candidati evita di normalizzare l'intero indice a ogni query,
senza ridurre la selezione disponibile.

Il benchmark Android `TitleSearchBenchmark` costruisce 5.000 titoli sintetici
senza rete. Misura ricerca più valutazione con obiettivo p95 inferiore a 100 ms;
registra anche la variazione della memoria del processo, che include runtime e
allocazioni temporanee e non equivale alla sola dimensione dell'indice.
La prova del 1 ottobre 2026 su Galaxy Z Flip6 (Android 16) ha ottenuto
**p95 16,74 ms** su 100 campioni misurati dopo 20 di riscaldamento, includendo
query parziali senza numero e refusi su titoli numerati. La variazione misurata
è 37.248 KiB di heap Java e 87.932 KiB di PSS del processo di prova, comprensivo
del corpus e del runtime: non è la memoria incrementale della sola app.
Questa misura riguarda il motore locale, non latenza di rete o tempo di apertura
delle estensioni. Le prove con contenuti reali restano locali, fuori dal repository.

```powershell
./gradlew.bat :core:smart-search:testDebugUnitTest :domain:testDebugUnitTest
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :macrobenchmark:assembleBenchmark
```

I layout devono essere verificati su dispositivo reale, anche con caratteri grandi.
`SearchAssistanceBar` riserva spazio al messaggio e ai suggerimenti anche prima
della loro comparsa, adattandolo alla dimensione dei caratteri. Mantiene i risultati
durante i recuperi e usa `ModernMotion` per la comparsa delle informazioni. Non modifica
gli effetti di navigazione o il ciclo di vita del player.

## Licenza della dipendenza

SymSpellKt **3.4.0** è usato come dipendenza Gradle, senza modifiche o dizionari
upstream inclusi. La licenza MIT originale del tag è conservata integralmente
in [`SymSpellKt-LICENSE.txt`](../app/src/main/assets/licenses/SymSpellKt-LICENSE.txt)
e distribuita nell'APK. Comprende gli avvisi di Adam Brown (2024), Lucky Sharma
(2019, implementazione Java) e Wolf Garbe (2018, implementazione C#).
La libreria compare inoltre nelle licenze delle librerie dell'app.

- [Progetto SymSpellKt](https://github.com/Darkrock-Studios/SymSpellKt).
- [Licenza del tag v3.4.0](https://github.com/Darkrock-Studios/SymSpellKt/blob/v3.4.0/LICENSE).

La licenza MIT della dipendenza rimane applicabile separatamente dalla licenza
Apache 2.0 di Nyanime. Aggiornando la versione, ricontrollare licenza, avvisi,
artefatto Android e test di compatibilità prima di cambiare questo documento.
