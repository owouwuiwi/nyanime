# Informazioni sulle opere

L'approfondimento delle schede è facoltativo. Il primo accesso propone Attiva e
Non ora; la scelta viene conservata e resta modificabile in **Aspetto e
contenuti → Informazioni sulle opere**. Non è richiesto alcun account o token,
nemmeno una credenziale applicativa del progetto.

## Cataloghi

- **AniList**: anime e manga, trama disponibile in inglese, titoli alternativi,
  autori, studi, personaggi, doppiatori con lingua dichiarata e relazioni.
- **TVmaze**: serie e animazione televisiva, cast, creatori, stagioni e
  identificatore IMDb quando disponibile. I testi del catalogo sono in inglese.
- **Wikidata**: dati strutturati dei film, persone coinvolte e identificatori
  esterni. La copertura è disomogenea: una descrizione breve di un'entità non
  viene presentata come una trama. Non è un sostituto completo di TMDB.
- **IMDb**: solo collegamenti a identificatori verificati nel formato
  `tt…`; nessuno scraping, account, voto o recensione.

TMDB non viene contattato: la distribuzione non include né richiede una sua chiave.

Le informazioni compaiono nei Dettagli delle schede esistenti. Non cambiano
copertine, sfondi, dati originali, progressi, tracking, uscite, download o
notifiche. I dati della fonte hanno la precedenza; un testo del catalogo
completa soltanto una descrizione assente, con provenienza e lingua visibili.
Le quantità dichiarate dal catalogo sono distinte dai capitoli ed episodi
effettivamente disponibili nella fonte.

## Identità e metadati facoltativi

Le identità pubbliche sono composte da provider, identificatore testuale e tipo
di opera. Anime, manga, film e serie non sono intercambiabili. Gli identificatori
numerici esistenti di AniList e MAL rimangono compatibili; non viene modificato
il formato dei vecchi backup.

Prima vengono riutilizzati gli ID disponibili dalla fonte, dalla Home e dal
tracking. Non serve essere collegati a un tracker per consultare i metadati
pubblici. Una ricerca per nome fornisce suggerimenti; l'associazione automatica
richiede evidenze concordanti di anno, formato e contesto di stagione/parte.
Riferimenti discordanti richiedono una scelta esplicita.

Le estensioni possono fornire un oggetto facoltativo `nyanime.work.v1` nel
`memo` del titolo; non viene aggiunto alcun metodo obbligatorio:

```json
{
  "type": "film",
  "year": 2000,
  "format": "FILM",
  "titles": ["An original title"],
  "references": [
    {"provider": "wikidata", "id": "Q123", "type": "film"},
    {"provider": "imdb", "id": "tt1234567", "type": "film"}
  ]
}
```

Valori supportati: `anilist` con tipo `anime` o `manga`, `tvmaze` con
`series`, `wikidata` con `film`, `imdb` con `film` o `series`.
`season` e `part` sono numeri facoltativi; provider e campi sconosciuti
vengono ignorati. Gli ID devono essere estratti/verificati dall'estensione,
mai inventati in base al titolo. Il contratto numerico `nyanime.tracking.v1`
continua a funzionare.

**Correggi associazione** consente di scegliere un'altra edizione o rimuovere
l'associazione. Una rimozione esplicita non viene annullata al successivo
accesso. Consenso e associazioni manuali sono preferenze incluse nei backup
delle impostazioni; la chiave stabile dipende da tipo, fonte e riferimento
del titolo, mai dall'ID numerico del database del telefono.

## Rete, cache e privacy

- La scheda locale è immediata. Si arricchisce soltanto il titolo aperto,
  dopo il consenso, senza scansioni della libreria.
- Richieste contemporanee identiche sono condivise. Uscire dall'ultima
  schermata interessata annulla la richiesta; una risposta tardiva non viene
  applicata a un'altra scheda.
- Cache pubblica ricostruibile, aggiornata dopo 24 ore e limitata a 256 file / 32 MiB.
  I dati salvati restano utilizzabili offline o in Solo scaricati.
- Errori e limiti riguardano soltanto l'approfondimento; le risposte 429 e
  gli errori del servizio non attivano cicli continui di tentativi.
- Le API ricevono esclusivamente titoli e ID pubblici. Non ricevono URL delle
  fonti, flussi video, credenziali, cookie delle estensioni o progressi.
- Incognito non registra nuove associazioni o nuovi dati nella cache
  persistente. Le scelte manuali durano soltanto per la sessione aperta.
- Filmografie ed elenchi estesi si aprono su richiesta. I servizi che
  supportano la paginazione vengono interrogati per pagina; TVmaze restituisce
  elenchi completi, mostrati progressivamente. Le pagine consultate rimangono
  nella cache dell'approfondimento; la cache HTTP è disattivata, anche per
  rispettare la modalità incognito.

## Attribuzioni

- [AniList, API pubblica](https://docs.anilist.co/)
- [TVmaze API](https://www.tvmaze.com/api): dati distribuiti con
  [CC BY-SA](https://www.tvmaze.com/api#licensing), con collegamento al catalogo
  e attribuzione nelle schede.
- [Wikidata](https://www.wikidata.org/wiki/Wikidata:Licensing): dati strutturati
  CC0. Testi e loghi di altri siti non vengono scaricati né incorporati.

Le licenze dei dati restano distinte dalla licenza del codice Nyanime.

## Verifica della versione 0.22.0.0

- 36 test mirati: identificazione, omonimie, stagioni, precedenza della fonte,
  associazioni portabili, cache, incognito, annullamento, errori e adattatori.
- `spotlessCheck`, compilazione Preview e suite Debug completati: 1.073 test,
  nessun fallimento, 7 test esclusi dalla suite esistente.
- APK Preview firmato installato sul Galaxy Z Flip6: consenso una sola volta,
  schede video e manga, persone, opere collegate, ricerca nelle fonti,
  elenco esteso e seconda pagina dei crediti, disattivazione e riattivazione.
- Controllati tema chiaro/scuro, larghezza di 320 dp, caratteri al 150% e
  tastiera nel selettore dell'opera. Impostazioni del dispositivo ripristinate.
- Nessun nuovo crash dell'app nel periodo di prova. Nel campione finale di
  navigazione, `gfxinfo` ha misurato 979 frame e il 3,68% di frame in ritardo;
  il dato comprende navigazione, caricamento della fonte e scorrimento.

Offline, limiti e risposte obsolete sono coperti dai test del servizio; non
sono una prova di tutti i comportamenti di rete di ciascun dispositivo.
La verifica su telefono ha coperto AniList; TVmaze e Wikidata sono verificati
dai test degli adattatori, senza una prova completa delle loro schede sul telefono.
