# Identità del titolo per tracking e AniSkip

Le estensioni possono fornire gli identificativi già visibili nella pagina di
dettaglio. L'app interpreta soltanto il contratto generico e versionato: non
contiene selettori, domini o logica specifici delle fonti. Se i metadati mancano,
la riproduzione e la lettura restano disponibili; l'app tenta una ricerca prudente
per titolo e lascia i risultati ambigui scollegati.

Per gli anime, l'estensione aggiunge a `SAnime.memo` l'oggetto
`nyanime.tracking.v1` senza rimuovere le altre chiavi:

```json
{
  "nyanime.tracking.v1": {
    "ids": { "anilist": 123, "myanimelist": 456 },
    "titles": ["Titolo alternativo"]
  }
}
```

Per i manga, `SMangaImpl` implementa il contratto facoltativo
`SMangaTrackingMetadata`. L'estensione può impostare `trackingMetadata` con
una stringa JSON contenente lo stesso oggetto interno:

```json
{
  "ids": { "anilist": 123, "myanimelist": 456, "mangaupdates": 789 },
  "titles": ["Titolo alternativo"]
}
```

Gli ID devono essere numeri positivi e riferirsi alla stessa edizione o stagione
del contenuto aperto. I titoli alternativi sono indizi, non identità certe.
L'estensione non deve indovinare né usare un ID di un'altra opera o stagione.
Per mantenere la compatibilità con lettori precedenti, il campo manga è
transitorio e facoltativo; nessun metodo dell'interfaccia `SManga` cambia.

Solo i tracker a cui la persona ha già effettuato l'accesso vengono collegati.
Le richieste pubbliche al catalogo servono a verificare l'identità e non
contengono il progresso personale.
