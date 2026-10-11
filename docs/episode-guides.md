# Guide episodi

Le **Guide** sono una categoria di estensioni RIN distinta dalle fonti video,
manga, notizie e dagli addon. L'installazione è facoltativa. Non registrano
fonti riproducibili, attività Android o servizi permanenti.

## Uso

Installa una guida compatibile dallo Store, poi apri un anime e **Guida episodi**.
L'app mostra i dati locali e aggiorna soltanto la guida del titolo aperto.
Le classificazioni sono Canon, Anime canon, Misto, Filler e Non classificato.
Le righe normali mostrano badge soltanto per filler e misti.

Una corrispondenza automatica richiede titolo o alias esatto, anno ed edizione
coerenti. Stagioni successive, parti e casi ambigui richiedono conferma.
**Correggi numerazione** mostra esempi tra numero locale e numero nella guida.
Le parti non presenti in una guida incompleta restano non classificate.

**Nascondi filler** è disponibile anche nei filtri della lista.
**Salta automaticamente i filler**, in Altro nel player e nelle impostazioni
della guida, offre preferenza globale e scelta per anime. Entrambe sono
disattivate inizialmente. Vengono saltati solo filler puri confermati; misti,
anime canon e sconosciuti restano riproducibili. Un'apertura esplicita non viene
mai saltata. Il riscontro permette di consultare gli episodi saltati, senza
segnarli visti. Nelle stanze la decisione appartiene all'host. Cast applica la
stessa regola del player.

## Contratto generico

Il manifest RIN usa `medium: "guide"`, schema 2, nessun elemento in `sources`,
un entry point e una dichiarazione `guide` con API 1, capacità versionate,
descrizioni localizzate e limiti di versione host. La capacità iniziale è
`episode-guides.v1`. Il modulo API condiviso contiene
`RinEpisodeGuideProvider`, indipendente dal contratto addon:

```java
String search(Context context, String requestId, String identityJson);
String guide(Context context, String requestId, String reference);
void cancel(String requestId);
```

La ricerca riceve esclusivamente metadati pubblici dell'opera: titolo, alias,
anno, formato, stagione, parte e ID di catalogo disponibile. Non riceve ID
locali, URL delle fonti o dei video, cookie, credenziali o progressi.
Restituisce candidati con riferimento stabile, titolo, alias e contesto
disponibile. La guida restituisce `work`, `episodes`, `attribution`, `url` e
`updated`; ogni episodio ha numero intero, tipo, titolo e data facoltativa.
Tipi non riconosciuti rimangono `UNKNOWN`.

Domini, ricerca remota, parsing, classificazioni specifiche del sito e campioni
reali appartengono esclusivamente al repository privato del provider.
Nyanime carica solo bundle verificati, compatibili e abilitati. I vecchi client
ignorano la nuova sezione `rinGuides` del catalogo; non la interpretano come
fonte manga o addon.

## Dati e limiti

Le risposte pubbliche sono conservate per 24 ore, con limite di 256 elementi e
32 MiB. Una risposta non valida non sostituisce i dati salvati. Richieste
simultanee identiche condividono un'operazione; annullare l'ultimo lettore
annulla anche la chiamata al provider. Offline e Solo scaricati non interrogano
la rete. Incognito conserva nuovi dati e correzioni soltanto in memoria e non
condivide le operazioni persistenti.

Associazioni, scostamenti di numerazione, filtri, salto e correzioni manuali
sono preferenze portabili incluse nei backup completi. Usano l'identità stabile
della fonte e del titolo, non gli ID numerici del database. La cache non è
necessaria al ripristino. I contrassegni filler preesistenti restano separati;
una correzione manuale ha precedenza sui dati automatici.

## Verifica

Test host con opere fittizie coprono associazioni ambigue, numerazione,
precedenza manuale, tipi sconosciuti, fine lista, cache, annullamenti e privacy.
Il provider mantiene separatamente test del parser e campioni reali. Prove
fisiche e copertura di player, stanze e Cast vengono indicate nella consegna;
il superamento dei test di selezione non prova da solo la riproduzione remota.
