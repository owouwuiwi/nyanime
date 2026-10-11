# AniSkip

L'integrazione era già presente nel player. Si attiva cercando AniSkip nelle
impostazioni, oppure in Player interno > Salto dell'introduzione > Attiva AniSkip.
Non serve più collegare manualmente il titolo a un tracker: l'app usa gli ID
forniti dall'estensione, un tracker già collegato oppure una ricerca univoca
nel catalogo. In caso di titolo o stagione ambigui non indovina.
I salti manuali, automatici e con conto alla rovescia mantengono le preferenze
esistenti.

Nel player, Altro > Salto della sigla permette di scegliere il comportamento
automatico generale e quello del singolo anime: Usa impostazione generale,
Salta o Non saltare. L'eccezione resta salvata anche dopo aver chiuso il player.
Se il salto automatico è disattivato per un anime, anche il conto alla rovescia
non salta da solo: resta disponibile il comando manuale.

Il recupero dei titoli già iniziati si esegue una sola volta per installazione,
quando è configurato almeno un tracker. Una scansione interrotta riprende, ma
non viene ripetuta dopo il completamento. Il tracking dei nuovi titoli resta
attivo al primo play o alla prima pagina letta.

Le richieste usano l'[API ufficiale v2](https://api.aniskip.com/api-docs), conservano
i numeri di episodio frazionari e riconoscono opening, ending, mixed opening,
mixed ending e recap. Le risposte senza intervalli sono normali; i segmenti
sconosciuti, invertiti o fuori dalla durata del video vengono ignorati.

Il client HTTP è condiviso, ha timeout limitati e annulla la chiamata quando
l'attività o il caricamento corrente vengono annullati. L'intera ricerca ha
un limite di quindici secondi. Le risposte vengono applicate solo all'episodio
e al video che le hanno richieste. Un tracker non supportato non impedisce
di usare un'associazione MAL o AniList disponibile.

Il lavoro precedente era già eseguito in background: queste correzioni non
costituiscono una diagnosi del buffering segnalato sul dispositivo.

Altre integrazioni esaminate: [Anime Skip](https://github.com/anime-skip/api-client-ts)
richiede un client ID e una configurazione aggiuntiva;
[Intro Skipper](https://github.com/intro-skipper/intro-skipper) analizza i file
su un server Jellyfin. Non sono sostituti diretti della ricerca AniSkip già
integrata nel player mobile.
