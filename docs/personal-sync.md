# I miei dispositivi

## Stato: dormiente

Il sync personale è temporaneamente disattivato in tutte le varianti dell’app.
La voce **I miei dispositivi** non compare nelle impostazioni, né in ModernUI né
nella UI legacy. Anche un dispositivo configurato in precedenza non avvia
connessioni, raccolta delle modifiche o trasferimenti della riproduzione.
L’aggiornamento elimina chiavi dal Keystore, identità cifrate (incluse copie atomiche),
database privati e file SQLite temporanei, opt-in salvato e code del sync.
Non cancella la libreria, i punti di ripresa locali o i download.
Le stanze video e manga sono indipendenti e attive.

`personalSyncEnabled` in `app/build.gradle.kts` controlla
`BuildConfig.PERSONAL_SYNC_ENABLED`. Il ripristino di una vecchia schermata di navigazione
mostra le impostazioni generali senza inizializzare il sync. Le istruzioni sotto
documentano i sorgenti dormienti nel repository, **non una funzione accessibile nella versione attuale**.
Il codice inutilizzato viene eliminato dall’APK dalla build ottimizzata.
Una futura riattivazione richiederà un nuovo collegamento e migrazioni che ripristinino
le strutture di raccolta delle modifiche, senza riutilizzare la vecchia identità cancellata.

## Collegare due telefoni o tablet

1. Sul primo dispositivo scegli **Inizia da questo dispositivo**.
2. Sul secondo scegli **Collega a un mio dispositivo**: compare un QR temporaneo.
3. Sul primo scegli **Collega un dispositivo** e scansiona il QR del secondo.
4. Confronta il numero visualizzato e conferma su entrambi.

Il QR scade dopo tre minuti e non contiene la chiave permanente. Il trasferimento
avviene cifrato dopo la conferma reciproca. I dispositivi usano relay Nostr:
non occorre aprire porte e non esiste un backend Nyanime. Disponibilità e tempi
dipendono dalla rete e dai relay; la prima copia di una libreria grande richiede
più tempo degli aggiornamenti successivi.

Il primo dispositivo inizializza la libreria condivisa. Il secondo recupera
quella libreria: le sue modifiche successive entrano nel sync. La configurazione
non importa automaticamente una precedente identità social.

## Riprendere

Ritrova i progressi nella Home, nella cronologia e nelle schede **Riprendi da qui**
di I miei dispositivi. Il punto di ripresa video e la pagina manga vengono salvati
nelle normali tabelle locali: nessuna richiesta di rete blocca il player o il lettore.
L’archivio del sync viene aggiornato fuori dal thread principale, ogni secondo,
con precedenza ai progressi rispetto alla prima copia della libreria.

Installando la stessa estensione, titolo ed episodio/capitolo sono riconosciuti
tramite riferimenti della fonte, mai tramite ID numerici del database locale.
Se una fonte manca o una corrispondenza è ambigua, la sezione **Da ritrovare su
questo dispositivo** conserva il progresso e permette una scelta esplicita.

Per i video già aperti su un altro dispositivo è mantenuto il trasferimento
esplicito al Play, con richiesta e conferma a scadenza. Gli aggiornamenti ordinari
non spostano un player attivo. Stanze e Cast restano indipendenti.

## Cosa viene trasferito

- Librerie video e manga, categorie, preferiti e segnalibri.
- Stato visto/letto, cronologia, posizione video e pagina manga.
- Marcatori di rimozione e revisioni per riconciliare modifiche fuori ordine.

Download, file Ultra, credenziali delle estensioni e impostazioni hardware restano
locali. La modalità incognito sospende cattura e applicazione dei progressi.
Il sync lavora con l’app aperta; la chiusura lascia una breve finestra per inviare
gli ultimi salvataggi. Alla riapertura recupera ciò che manca. Non aggiunge un
servizio permanente in background.

Il toggle **Sincronizza questo dispositivo** interrompe connessioni e cattura
locale senza cancellare la chiave o la libreria. La riattivazione recupera gli
aggiornamenti disponibili; le modifiche fatte durante la disattivazione non
vengono pubblicate retroattivamente.

## Recupero e riservatezza

**Salva la chiave di recupero** esporta un file protetto da password. Sul nuovo
telefono scegli **Ho una chiave di recupero**. Conserva file e password: senza
un dispositivo già collegato o questa copia, Nyanime non può recuperare i dati.
Disattivare un telefono non revoca una chiave già copiata.

Il canale personale usa un’identità, un alias Android Keystore e un database
separati da quelli della community dormiente. Il normale avvio non li apre finché
il sync non è configurato. La sola schermata introduttiva non crea chiavi né
connessioni. Anche i vecchi flag di cattura vengono verificati quando si aprono
i database locali, prima delle scritture della libreria.

I record e gli indici Nostr `30078` sono cifrati verso la propria identità con
NIP-44; gli indirizzi dei contenuti usano HMAC, senza titoli o URL nei tag.
I comandi di trasferimento sono involucri NIP-59, accettati solo se il mittente
interno è la propria identità e il comando è nell’elenco esplicito `device.*`.
Metadati pubblici, feed, contatti, chat e presenza social sono esclusi dai filtri
e dal percorso di pubblicazione del canale personale.

La coda è persistente, cifrata e consolidata per record. Un invio viene indicato
come confermato solo dopo la risposta positiva di un relay; ciò non certifica
che l’altro telefono, eventualmente offline, l’abbia già applicato. Errori e
attese restano visibili. Una vista locale compatta alimenta la lista di ripresa,
evitando di decifrare tutta la libreria a ogni secondo di video.

## Verifiche

I test coprono filtro dei messaggi personali, cifratura con identità condivisa,
incognito, ripresa e riavvolgimento, conflitti, coda e relay simulati, trasferimento
del player e regressioni delle stanze. Le latenze reali, le restrizioni Android
e la resa visiva richiedono comunque la prova su due dispositivi fisici.
