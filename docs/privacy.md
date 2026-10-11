# Privacy di Nyanime

Nyanime è un'app Android che gestisce localmente librerie, progressi di visione e lettura, preferenze e download. Non richiede un account Nyanime. Profili social e sincronizzazione personale tra dispositivi sono disattivati.

## Dati sul dispositivo

La libreria, la cronologia, i punti di ripresa, le impostazioni, i file scaricati e i dati delle estensioni restano nell'archivio dell'app sul dispositivo. Backup ed esportazioni vengono creati solo quando li richiedi e sono sotto il tuo controllo. Il backup completo `.nyabk` può includere credenziali e token delle impostazioni selezionate: non è cifrato e va conservato in privato. Non include video o pagine scaricati. Disinstallare l'app o cancellarne i dati può eliminare queste informazioni; i file esportati separatamente seguono le regole della posizione in cui li salvi.

## Connessioni facoltative

Nyanime contatta servizi esterni quando usi le relative funzioni: cataloghi e tracker configurati, fonti delle estensioni installate, immagini e contenuti multimediali, ricevitore Cast o TV companion e GitHub per controllare gli aggiornamenti. Tali servizi possono vedere i dati tecnici della richiesta, incluso l'indirizzo IP, secondo le proprie informative. Le estensioni sono componenti separati e possono avere cookie e comportamenti propri.

Le stanze Guarda/Leggi insieme usano relay Nostr. Un codice o invito consente ai partecipanti di coordinare la sessione; gli eventi necessari alla stanza passano attraverso i relay scelti. Non condividere il codice con persone che non vuoi far entrare nella stanza. Le stanze non pubblicano automaticamente la cronologia della libreria.

L'approfondimento facoltativo delle schede contatta AniList, TVmaze e Wikidata
soltanto dopo l'attivazione. I cataloghi ricevono titoli e identificatori pubblici,
senza URL delle fonti, cookie, credenziali o progressi personali. Le informazioni
pubbliche consultate possono essere conservate in una cache locale ricostruibile;
in incognito non si registrano nuovi dati o associazioni persistenti. Il consenso
e le associazioni manuali sono inclusi nei backup delle impostazioni.

## Backup Cloud facoltativi

Se colleghi Telegram da **Nyanime Cloud**, Nyanime usa TDLib e un canale privato
riservato al tuo account per conservare backup completi `.nyabk`. Il backup può
contenere progressi, cronologia, preferenze private e credenziali già incluse
nell’esportazione completa. Video e pagine scaricati, sessioni e chiavi Telegram
sono esclusi. La cifratura è quella Cloud di Telegram, **non end-to-end**; non viene
aggiunta una password al file.

I salvataggi automatici partono soltanto dopo la configurazione esplicita. Puoi
disattivarli, proteggere copie, eliminare singoli backup o scollegare la sessione.
Nyanime non importa la rubrica e non rende pubblico un profilo per il collegamento
Cloud. Cache e sessione Telegram sono locali e separate dai backup; lo scollegamento
revoca la sessione e rimuove questi dati dopo la chiusura del motore. I backup già
salvati su Telegram rimangono nel tuo archivio. Vedi [guida Cloud](telegram-cloud.md)
e [privacy di Telegram](https://telegram.org/privacy).

## Dati non sincronizzati

Le estensioni Notizie abilitate contattano i rispettivi editori. Preferenze,
lettura e collegamenti personali restano sul dispositivo. Per riconoscere
adattamenti e seguiti, la sezione Notizie può interrogare AniList usando soltanto
ID di catalogo, senza progressi, credenziali o cronologia. Il controllo periodico
degli articoli parte solo se si attivano gli avvisi.

Le funzioni disattivate di profilo pubblico, amicizie, chat e sincronizzazione personale non inviano nuovi dati. Eventuali dati locali obsoleti di queste funzioni vengono rimossi dalla versione corrente dell'app. Download e copie Ultra restano locali.

## Controlli e contatti

Puoi disattivare funzioni facoltative nelle impostazioni, rimuovere download, cancellare dati dall'app o dal sistema Android e scollegare tracker esterni. Per domande o segnalazioni su questa distribuzione usa le [issue del repository Nyanime](https://github.com/owouwuiwi/nyanime/issues). Le librerie e i servizi di terzi hanno informative e licenze separate.

Questa pagina descrive il comportamento del codice Nyanime distribuito in questo repository; una build modificata o un'estensione di terzi può comportarsi diversamente.
