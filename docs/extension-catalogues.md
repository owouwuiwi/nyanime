# Cataloghi e aggiornamenti delle estensioni

L’app non incorpora elenchi di fonti, indirizzi dei siti o regole di estrazione.
I cataloghi si importano nelle impostazioni delle estensioni o con collegamenti
`nyanime://anime-store?url=<URL HTTPS>` e `nyanime://manga-repo?url=<URL HTTPS>`.
I precedenti collegamenti di importazione restano compatibili.

`nyanime://extension-catalogue?url=<URL HTTPS>` importa un catalogo unificato.
La conferma mostra nome e quantità per video, manga e notizie. Il campo `media`
e il `medium` di ciascuna voce separano i tipi senza dedurli dai nomi. Un catalogo
può dichiarare gli URL precedenti tramite `replaces`: il passaggio aggiorna entrambi
i registri e ripristina la configurazione precedente in caso di errore.

Le estensioni Notizie si installano e aggiornano in Notizie → Fonti dai cataloghi
già importati. Download limitati e verificati per hash, pacchetto, API, certificato
memorizzato al momento dell’importazione e ID di distribuzione precedono l’installer
Android. Le installazioni manuali richiedono un consenso prima del collegamento.
L’abilitazione di una fonte Notizie rimane separata dall’installazione.

I sorgenti di un’estensione possono essere privati mentre catalogo, icona e APK sono
pubblici. Questo permette l’installazione senza fornire credenziali del repository
all’app. Un APK distribuito può comunque essere analizzato: la privacy del repository
non rende il codice compilato indecifrabile.

Un APK con distribuzione manuale resta protetto. Nei suoi dettagli, «Collega agli
aggiornamenti» compare solo per origini già importate con pacchetto, API, certificato
e ID di distribuzione compatibili. La scelta è legata a pacchetto e firma e viene
ricontrollata sull’APK scaricato, compresa la conservazione della dichiarazione Home.
Non si scelgono automaticamente origini diverse e non si autorizzano downgrade.

«Mantieni questa versione» prevale anche dopo il collegamento. Nessun token GitHub
o altro segreto del compilatore viene incorporato nell’app o nel catalogo pubblico.
