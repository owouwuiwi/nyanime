# Stringhe e traduzioni di Nyanime

Questo modulo conserva risorse condivise e traduzioni ereditate. Le stringhe
specifiche video sono anche nel modulo [i18n-aniyomi](../i18n-aniyomi).

Le risorse di base sono nelle cartelle `src/commonMain/moko-resources/base/`
dei rispettivi moduli; le traduzioni sono nelle cartelle della lingua, per esempio
`it/`. Non è configurato qui un servizio di traduzione Nyanime da presentare come
sito o Weblate ufficiale del progetto.

Per una nuova stringa:

1. Individua il modulo già usato dalla schermata e aggiungi la risorsa di base.
2. Aggiungi la traduzione italiana; conserva placeholder, plurali e markup.
3. Riutilizza una chiave esistente quando il significato coincide.
4. Verifica il testo nel layout, anche con caratteri grandi e orientamento orizzontale.

Non rinominare chiavi o moduli solo perché contengono il nome upstream.
Mantieni le attribuzioni e non inserire nomi o dati di integrazioni private.
Per compilazione e verifica consulta [CONTRIBUTING.md](../CONTRIBUTING.md).
