# Bring your own content

**Nyanime does not promote piracy in any way or form.**

Nyanime is a general-purpose player and reader. It does not host or distribute
unauthorized anime, manga, television programmes or other copyrighted works.
You bring your own content, local files and compatible extensions.

Use only material you are legally entitled to access, such as your own original
work, public-domain material, or content made available with the necessary
permission or licence. Respect applicable copyright law, licences and access
conditions. Being publicly accessible on the internet does not by itself mean
that a work may be copied or redistributed. See the
[WIPO copyright FAQ](https://www.wipo.int/en/web/copyright/faq-copyright).

## Extensions and developer examples

The application supplies generic APIs. An independently installed extension
supplies its own catalogue, parsing rules and content references. Compatibility
or installation does not certify the lawfulness of a source or grant rights to
its content. Extensions must not be used to infringe copyright.

The separate developer starter uses original synthetic video, geometric manga
pages and sample articles. Its demo media are dedicated under CC0-1.0, with
branding and font licences identified separately. No third-party scans, episodes
or streams are needed to learn the APIs.

The application's software licence governs its code. It does not grant rights
to independently supplied media, cover artwork, metadata or trademarks. Read
[licensing and attribution](licensing.md) for the component-specific obligations.
This statement explains the project's position; it does not replace those
obligations or applicable law.

## In the app

Open **More → About** to read the project's position and its bring-your-own-content
model. The card links to the public policy above; it does not load remote text
to display the statement.

### Translating the statement

All four visible texts use the existing Moko resource system, through
`MR.strings.responsible_use_title`, `responsible_use_statement`,
`responsible_use_summary` and `responsible_use_learn_more`.
English defaults live in `i18n/src/commonMain/moko-resources/base/strings.xml`;
Italian overrides live in the sibling `it/strings.xml`.

To add a language, translate these same keys in its locale's `strings.xml`,
following the existing locale directories. The existing locale-config task detects
non-empty locale resources and includes them in the app's language selector.
Missing translations use the English defaults. No source-specific logic, new
language switch, hardcoded text or Compose changes are required. The card uses
wrapping text and the current layout direction, without fixed text heights.

## In italiano

**Nyanime non promuove né incoraggia la pirateria in alcuna forma.**

Nyanime adotta il modello **bring your own content**: mette a disposizione un
player, un lettore e API generiche; i contenuti, i file locali e le estensioni
compatibili sono scelti dall'utente. Non ospita né distribuisce opere senza
autorizzazione. Usa soltanto materiali che hai il diritto di consultare,
rispettando copyright, licenze e condizioni di accesso. Un contenuto visibile
su internet non è automaticamente liberamente copiabile o ridistribuibile.

Gli esempi per sviluppatori sono separati dall'app e usano materiali originali
con licenze dichiarate. La compatibilità di un'estensione non costituisce
un'autorizzazione a utilizzare i contenuti che essa rende accessibili.
