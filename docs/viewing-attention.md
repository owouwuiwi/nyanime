# Attenzione alla visione

> Versione ordinaria: integrazione 0.49.0.0. Le prove di build e dispositivo che citano NyanimeB descrivono esclusivamente quelle versioni storiche; le verifiche della build ordinaria sono riportate nella [migrazione](normal-migration.md).

Funzione facoltativa, sperimentale e disattivata all'inizio. È disponibile da
Impostazioni → Player → Attenzione alla visione e dai controlli dei due player.
Non riconosce l'identità e non stabilisce se una persona stia realmente dormendo
o comprendendo ciò che vede. Usa indizi del volto per offrire pausa e recupero
oppure, soltanto scegliendo «Solo rilevamento», conservare stime locali senza
interrompere la riproduzione.

## Uso e impostazioni

Abilitare la funzione e concedere il permesso della fotocamera frontale. Il
consenso spiega l'elaborazione locale e l'indicatore di Android prima del prompt.
I controlli sono comuni al player nativo e ai video normali del modulo online.
Il tasto con l'occhio nella barra dei controlli attiva o disattiva la stessa
preferenza globale: occhio celeste quando abilitato, barrato quando spento.
Nel player nativo è accanto a SM. Se manca il permesso della fotocamera, apre
prima le impostazioni con la spiegazione della funzione. Spegnerlo non riprende
automaticamente un video già in pausa.

| Controllo | Impostazione iniziale |
| --- | --- |
| Pausa dopo una distrazione continuativa | 3 secondi |
| Pausa dopo una chiusura bilaterale prolungata degli occhi | 10 secondi |
| Conferma del ritorno dello sguardo | 1,5 secondi |
| Ripresa dopo distrazione | Automatica |
| Ripresa dopo occhi chiusi | Con un tocco |
| Recupero del punto | Pulsante, senza ritorno automatico |
| Margine prima del punto | 3 secondi |
| Attesa del ritorno durante una pausa automatica | Massimo 60 secondi |

Pausa, ripresa e recupero si possono configurare separatamente. I tempi, la
sensibilità e il ritorno automatico sono facoltativi. È possibile sospendere
la funzione per la visione corrente, ripristinare le preferenze ed eliminare
i punti di recupero. Il punto è disponibile anche tornando alla visione in
un secondo momento; la chiave usa identità stabili del contenuto, non ID delle
righe nel database locale. Impostazioni e punti ordinari passano dal backup
esistente. In incognito i punti rimangono soltanto nella sessione in memoria.

Non c'è una posizione obbligatoria. Le misure degli occhi usano coordinate
relative al volto, invarianti rispetto alla rotazione della testa nell'immagine.
La verifica facoltativa mostra un'anteprima; l'adattamento serve soltanto per
la sessione corrente e non salva dati biometrici. L'inclinazione della testa
non indica sonno. Profilo forte, occhi nascosti, immagine scura o sovraesposta,
più volti e misure insufficienti producono una lettura incerta. In tal caso
non parte un nuovo comando automatico. La fotocamera può comunque non riuscire
a leggere bene determinate posture: la funzione non promette gaze tracking preciso.

## Solo rilevamento

La modalità predefinita resta «Pausa automatica». «Solo rilevamento» si sceglie
esplicitamente nelle stesse impostazioni e funziona con il medesimo tasto occhio.
Il video continua senza avvisi, pause, riprese o ritorni automatici. I comandi di
pausa e recupero sono nascosti; una pausa già avvenuta prima del cambio di
modalità resta una pausa, senza una ripresa inattesa.

Soltanto questa modalità raccoglie intervalli stimati di attenzione: sguardo
presente, assente, occhi chiusi oppure lettura incerta. La pausa automatica non
registra questi intervalli. Il dato è associato alla posizione nel video tramite
una chiave opaca stabile e intervalli di cinque secondi; più tipi di evidenza
nello stesso intervallo o in visioni successive restano distinti. Un intervallo
non osservato non significa «non guardato» e un indizio presente non dimostra
attenzione o comprensione per tutti i cinque secondi.

L'aggregazione avviene in memoria, con salvataggi limitati fuori dal thread UI
ogni quindici secondi e all'uscita, disattivazione o sospensione del contesto.
Non si deduce nulla attraverso seek, buffering, pause, cambi di contenuto,
ritardi di osservazione o interruzioni del ciclo di vita. Il formato locale è
versionato e limita ogni video a 24 ore; nessuna immagine, misura del volto o
traiettoria precisa degli occhi è conservata. In incognito gli intervalli non
vengono salvati e non si leggono quelli ordinari del video.

Gli intervalli ordinari passano dal backup completo delle impostazioni. Un
comando confermato permette di eliminarli senza cancellare progressi, segni di
visto, preferenze o punti di recupero. La cancellazione invalida anche le
scritture già in coda. Il conteggio nelle impostazioni indica i video con dati
salvati; gli eventuali errori di archiviazione compaiono soltanto lì.
Questa raccolta prepara una futura visualizzazione della cronologia; non
modifica adesso lo stato di visto o il tracking.

## Confini della riproduzione

Il rilevatore opera nella riproduzione locale visibile, a pagina o a schermo
intero. È sospeso nei player minimizzati, PiP, background, Cast, stanze, dirette
e Shorts. La fotocamera non appartiene al servizio che conserva il player.
Una pausa manuale, un seek, un comando di un'altra funzione o il cambio di
contenuto revocano la ripresa automatica. Alla scadenza dell'attesa del ritorno
la fotocamera si chiude e resta necessario un tocco. Nessun seek può migrare al
video successivo; i segni di visto e il tracking non vengono modificati.

## Architettura e costi

Il nuovo codice originale è in `it.lorelore.nyanime`, senza modificare
l'identità Android installata. `core:attention` separa:

- osservazioni e classificazione prudente degli indizi;
- decisioni temporali verificabili senza Android;
- acquisizione CameraX, ciclo di vita e rilascio delle immagini;
- contratto dei player e proprietà dei comandi automatici.

Il coordinamento visibile, le preferenze, i punti e l'interfaccia sono nel modulo
app; gli adattatori leggono i player già esistenti. Il modulo online registra
il proprio adattatore soltanto dopo il suo normale avvio pigro. Il suo snapshot
upstream resta immutato: i piccoli hook dei comandi e delle impostazioni sono
riprodotti da `feature/online-video/prepare.py` e verificano i punti di applicazione.
Non sono state importate implementazioni dei precedenti esperimenti di gaze.

CameraX richiede 640×480, usa KEEP_ONLY_LATEST e un solo executor di analisi.
L'inferenza sincrona VIDEO di MediaPipe mantiene esplicita la proprietà delle
immagini: ogni ImageProxy viene chiuso e le bitmap vengono rilasciate in finally.
Il limite iniziale è cinque analisi al secondo; se l'inferenza rallenta, il
campionamento scende. Contatori opzionali del player possono inoltre rallentarlo
e sospenderlo dopo perdite ripetute. Misure mancanti non sono considerate zero.
La qualità selezionata del video non viene cambiata dalla funzione.

Il modello ufficiale è locale, versione 1, 3.758.596 byte, SHA-256
`64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff`.
Gradle ne verifica l'hash prima delle build. MediaPipe è bloccato a 0.10.35 per
evitare la dipendenza LLM aggiunta alle release successive; CameraX riusa il
catalogo dell'app. Non si aggiungono ML Kit, EyeTrax o un secondo rilevatore.

La factory di statistiche della versione Maven è trasformata prima di D8/R8
per restituire `LocalAttentionStats`, un'implementazione senza trasporto o
raccolta di statistiche. La trasformazione verifica nome e firma del metodo;
non cambia modelli, grafi, inferenza o librerie native. Licenza, avviso della
modifica e provenienza degli artefatti sono consultabili anche offline in Info.

## Verifica e limiti di consegna

I test sintetici usano titoli e identità fittizi. Coprono finestre temporali,
battiti e occhiolini, occhi chiusi, letture sconosciute o obsolete, buffering,
comandi manuali, contenuto successivo, scadenza dell'attesa e rotazione della
geometria. I test di persistenza verificano identità stabili, portabilità,
scadenza, limiti, dati non validi e separazione dell'incognito. I test di
«Solo rilevamento» verificano assenza di comandi, intervalli incerti, seek,
velocità, pause, dati obsoleti, salvataggio portabile e cancellazione definitiva
delle scritture precedenti.

La compilazione, i test JVM e l'installazione non costituiscono una prova della
precisione su persone reali. La validazione percettiva richiede occhiali, luce
diversa e posture reali, nei due player: obiettivo zero pause errate in venti
minuti di visione normale e almeno nove rilevamenti corretti su dieci prove
controllate di assenza o occhi chiusi. Servono anche confronti di almeno venti
minuti con e senza funzione, con Anime4K attivo sul player nativo, per misurare
perdite aggiuntive e assenza di interruzioni ricorrenti. Queste soglie vanno
documentate come esiti misurati, non dedotte dai test sintetici.

Per questa prima prova l'utente ha scelto soltanto verifiche automatiche.
Precisione su persone, resa nelle diverse posture e confronto prolungato del
carico con una persona davanti alla camera restano quindi da validare;
l'installazione di una build di prova non implica il superamento di tali soglie.

Nota storica delle prime prove: questa funzione era nel ramo privato NyanimeB; nessun aggiornamento OTA
ordinario fa parte della consegna. Le prove specifiche della modalità sono
limitate al telefono USB. Eventuali installazioni wireless richiedono una
separata autorizzazione e non comprendono prove della fotocamera.

Riferimenti: [Face Landmarker Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android),
[CameraX ImageAnalysis](https://developer.android.com/media/camera/camerax/analyze),
[privacy](privacy.md), [provenienza del modello](../core/attention/src/main/assets/attention/provenance.json).
