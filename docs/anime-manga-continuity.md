# Passaggio tra anime e manga

## Apertura progressiva

L’identificazione dell’opera, la ricerca della copia e i riferimenti sui capitoli
sono fasi indipendenti. Il collegamento compare dopo la risposta del catalogo;
una copia verificata si può aprire subito, anche se i riferimenti editoriali
stanno ancora caricando. Toccando durante la ricerca, la scheda apre la copia
appena pronta; cambiare destinazione annulla l’apertura precedente.

Si riutilizzano prima gli ID già forniti, senza ripetere la richiesta dei dettagli
alla fonte. Se mancano gli ID, è ammesso soltanto un alias esatto e univoco del
catalogo, mantenendo significativi i numeri di stagione ed edizione. Questo
identifica l’opera nel catalogo, non la copia di un’estensione.

Per la copia si controllano libreria, letture/visioni precedenti e identità
verificate in cache. Seguono i resolver facoltativi per ID e la ricerca nativa
dei candidati: i titoli restringono la ricerca, ma soltanto ID concordanti nei
metadati della fonte permettono un’apertura automatica. Si rispettano fonti
abilitate, lingue e priorità configurate. Il lookup prova sia AniList sia MAL.
I dettagli non vengono richiesti una seconda volta quando gli ID sono già
presenti nella risposta dell’estensione.

Le due direzioni condividono un limite di tre ricerche di copie alle fonti e due ai
metadati aggiuntivi. Errori di una fonte non cancellano le copie già trovate;
le richieste obsolete vengono annullate. Le scelte mantengono il loro ordine
durante gli aggiornamenti progressivi.

La cache locale contiene al massimo 128 riferimenti fonte/URL con ID verificati,
validi per sei ore. Non contiene stream, cookie o credenziali, non è trasferita
nei backup e non viene letta né scritta durante sessioni in incognito. Una copia
rimossa, una fonte disabilitata o ID in conflitto non vengono aperti tramite cache.

Se non si trova una copia verificata, un foglio spiega il limite e permette una
ricerca manuale esplicita. Non si sceglie silenziosamente il primo titolo simile.
Stati e messaggi sono disponibili in italiano e inglese.

## Verifica del flusso

La suite completa dell’app comprende 1.355 test, tutti superati in questa verifica.
I casi del collegamento usano soltanto opere fittizie e contratti generici:
risultati progressivi con metadati lenti, copie con titoli uguali ma ID discordanti,
resolver MAL, alias del catalogo, fonte offline, estensione rimossa, concorrenza,
annullamento e cache limitata, privata e non trasferita nei backup.

Un APK firmato, ottimizzato e non debuggabile è stato installato su un telefono reale.
Sono state verificate apertura diretta del manga, scelta fra stagioni e copie anime,
ritorno alla scheda precedente e apertura dopo il riavvio dell’app. Le prove non
richiedono login al tracking e non avviano automaticamente lettura o riproduzione.
I checkpoint mancanti restano mancanti; non sono sostituiti da capitoli stimati.

## Relazioni e capitoli

La scheda anime mostra i manga collegati attraverso gli ID di AniList. Se
l'adattamento parte da una novel, viene seguito anche il rapporto tra quella
novel e i suoi manga, distinguendolo dal collegamento diretto.

MangaBaka e MangaUpdates possono fornire punti documentati di inizio e fine
adattamento. Sono riferimenti editoriali, non una mappa completa di ogni episodio.
Gli intervalli stagione/episodio sono mostrati per aiutare a distinguere gli archi;
non vengono convertiti in capitoli stimati. Il salto a un capitolo viene proposto
solo con un checkpoint esplicito utilizzabile per l'episodio corrente. La presenza
di una stagione nel checkpoint viene confrontata con la stagione della scheda:
numero esplicito fornito dall'estensione o dai titoli dell'esatto ID di catalogo.
Una serie TV senza prequel TV è trattata come prima stagione; film, parti e anni
di uscita non incrementano la numerazione. In caso di conflitto non si attribuisce
un numero di stagione.

I collegamenti restano sempre compatti, senza pulsante o contenuto espandibile.
Il corpo apre direttamente la copia verificata, oppure il capitolo quando esiste
un checkpoint applicabile. Quando la destinazione è ambigua, la scelta avviene
in un foglio separato, aperto soltanto al tocco della scheda. Dal manga si possono
scegliere le diverse stagioni; più copie mostrano anche il nome della fonte,
ottenuto dall'estensione installata.
Senza checkpoint la scheda mantiene il collegamento al titolo senza mostrare
un numero stimato o un avviso sul capitolo mancante.
La scheda mantiene il gradiente. Sono visibili
due righe «Stagione N • Inizio • Capitolo X» e «Stagione N • Fine • Capitolo Y»,
quando esistono riferimenti per la stagione aperta. Più stagioni nello stesso
campo sono lette separatamente: un punto dell'intera serie non diventa la fine
di una stagione successiva. Un riferimento senza stagione può essere mostrato
come riferimento della prima stagione solo se non risultano prequel o sequel TV.
I punti mancanti o ambigui non sono inventati. Per film o adattamenti senza una
stagione identificabile resta l'etichetta «Adattamento» sui riferimenti non numerati.
Il termine «Fine» indica il punto finale catalogato, anche se una serie ancora
in corso può estendere l'adattamento nei successivi aggiornamenti del catalogo.
I riferimenti compaiono una sola volta; non sono presenti dettagli espansi,
copertine aggiuntive o informazioni duplicate.

## Dal manga all'anime

La scheda manga offre anche il percorso inverso. L'identità proviene dai metadati
dell'estensione o da un ID già associato al tracking: l'accesso a un tracker e la
presenza in libreria non sono richiesti. AniList fornisce i rapporti di adattamento;
prequel, sequel e musica non vengono confusi con un adattamento del manga.
Se il manga deriva da una novel, il collegamento indiretto è indicato.

Le stagioni e gli altri adattamenti mantengono il proprio ID e vengono mostrati
separatamente, con titolo, formato, anno e numero di episodi quando disponibili.
La scelta mostra sotto ogni stagione i capitoli documentati di inizio e fine,
in due righe distinte. Il numero di stagione viene ricavato dal catalogo, con
gli stessi criteri del percorso inverso; film e speciali non ereditano intervalli
TV. Se manca un riferimento, non viene calcolato dal numero degli episodi.
Gli eventuali riferimenti a una pagina interna al capitolo restano visibili.
I due percorsi condividono la cache dei cataloghi, interrogati tramite ID.
Nyanime verifica le copie note e interroga `AnimeCatalogIdResolver` nelle estensioni
compatibili. La copia deve fornire un ID nei metadati dell'estensione: un vecchio
tracking da solo non prova che non sia un altro adattamento dello stesso titolo.
Non stima un episodio dal capitolo letto e non avvia il player:
apre la scheda dell'anime, conservando progresso, tracking e preferenze esistenti.
Se una copia non è verificabile, rimane disponibile la ricerca esplicita.

Il risultato resta nello stato della scheda anche quando si apre un anime.
Un errore del catalogo conserva un risultato precedente valido; al primo errore
mostra invece un'azione per riprovare, senza modificare la libreria.

## Contratti delle estensioni

I contratti sono facoltativi e mantengono nell'estensione ogni regola del sito:

- `AnimeCatalogIdResolver.findAnimeByCatalogId(catalog, id)` risolve un ID anime.
- `RelatedMangaLinks.relatedMangaLinks(animeUrl)` fornisce collegamenti manga
  associati dal sito a una scheda anime.
- `MangaCatalogIdResolver.findMangaByCatalogId(catalog, id)` risolve un ID manga.
- `MangaCatalogLinkResolver.findMangaByCatalogLink(url)` accetta soltanto link
  appartenenti al catalogo che l'estensione gestisce.

Queste interfacce mantengono nome e firme nella build ottimizzata. Se un APK
include stub con gli stessi nomi, il caricamento usa le definizioni dell'app:
l'identità del contratto resta unica anche tra class loader distinti.

L'app passa un identificativo di catalogo, non costruisce URL del sito e non
interpreta il suo HTML. I dettagli restituiti devono contenere gli ID secondo il
[contratto dei metadati](extension-tracking-metadata.md). Nyanime controlla che
gli ID concordino con il manga collegato: un ID in conflitto impedisce
l'associazione automatica. Un titolo uguale non prova da solo l'identità.

Un'estensione può usare un indice locale di ID già incontrati quando il suo sito
non offre una ricerca per ID. Un titolo mai indicizzato può comunque essere
verificato attraverso un link accettato dall’estensione o i metadati dei candidati
della ricerca nativa. Se la fonte non fornisce un ID verificabile, rimane la ricerca
manuale esplicita; non si sceglie automaticamente un risultato simile.

L'apertura di una copia non la aggiunge alla libreria e non modifica il progresso
anime. I servizi di catalogo ricevono ID o, soltanto nel recupero dell’identità
iniziale, il titolo; non ricevono cookie della fonte,
credenziali del tracker o URL di riproduzione.

Il risultato resta nello stato della scheda durante il passaggio al manga e il
ritorno. La richiesta segue il ciclo di vita della scheda, non quello della sua
composizione: non ricomincia a ogni ritorno e non sparisce durante un aggiornamento
del tracking o del punto raggiunto. Una risposta superata non sostituisce quella
relativa alla richiesta corrente; un errore conserva il risultato precedente.
