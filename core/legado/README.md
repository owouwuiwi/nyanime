# Legado format adapter

**Deferred:** Legado import and host integration are future work, outside the current novel-reader
delivery. This module is not a dependency of the application. See the [remaining work](../../docs/legado-roadmap.md).

This module is an original format adapter, not a bundled catalogue or a working Legado source
provider. Importing builds a bounded compatibility plan and a typed rule tree without executing
scripts, connecting to a website, opening a session or activating a source. A deterministic rule
evaluator preserves HTML/JSON row context, typed XPath results and bounded RE2 replacements.
Requests are resolved through a separate broker contract. The host integration, local secret
storage, import UI, export and source registration remain to be implemented and validated.

## Contracts

- Single JSON objects and arrays keep stable source identity, an exact canonical definition hash
  and per-field diagnostics. Duplicate keys are reported on every affected row.
- COMPLETE means structurally representable by this parser. It is neither execution permission
  nor an end-to-end compatibility claim. PARTIAL requires an explicit review of missing optional
  capabilities. BLOCKED cannot activate until blocking rules are corrected or supported.
- JavaScript remains an explicit unevaluated step, with separate permission required by the host.
  Java/Android bridge operations and unimplemented capabilities are reported, never emulated by
  exposing the application, database, cookies or network to script code.
- Parsed declarations retain CSS/default selector chains, XPath/JSONPath, indexes/slices,
  combinations, replacement and script structure. Unsupported semantics fail explicitly.
- Headers are private input values with redacted diagnostic strings. The original document is
  available only through an explicit local-storage method. There is no automatic sharing/export
  implementation: an eventual exporter must redact private input and review embedded scripts.
- Reading is limited to2MiB/64sources, nesting64 and20000 unquoted separators before JSON parsing. UTF-8 is strict;
  cancellation checkpoints and streams that make no progress are handled explicitly.
- DOM/JSON contexts stay opaque. HTML extraction uses copies before removing active elements;
  changing one field cannot alter a later field's input. Scalar output, selections, rule fanout
  and replacement expansion are bounded; excessive results fail rather than silently truncating.
- JavaScript is an injected host port, absent by default. It receives declared JSON values only.
  The host must recheck the current source revision and permission before and after each call,
  and use the existing isolated script service rather than an interpreter in the application.
- URL construction preserves GET/POST, response charset, body and header semantics separately.
  A search query cannot inject additional query parameters. Every resulting origin needs explicit
  approval; global source credentials are withheld from secondary origins. The broker remains
  responsible for redirects, source-scoped sessions, cancellation and request quotas.

## Verification boundary

The integrated check on23October2026 passed49JVM tests and assembled the Android library.
The tests cover import bounds and compatibility, row context, inclusive slices, attributes/XPath,
JSON, lazy alternatives, single-match extraction, bounded expansion, suspended cancellation,
GET/POST templates, query/body encoding, origin approval, credential containment and redacted errors.
None of these results proves an installed Legado source, a reader UI or end-to-end compatibility.

The host/runtime must preserve edition/progress identity during explicit updates, mediate every
request, bound execution and use the existing optional NovelSource and isolated script services.
No rule for a real provider belongs in this module or its tests.

## Provenance

No Legado application code or assets have been copied. The adapter follows the published data
format: [fields](https://hapelee.github.io/legado-with-MD3/dev/source-fields.html),
[rule syntax](https://hapelee.github.io/legado-with-MD3/dev/syntax.html),
[URL options](https://hapelee.github.io/legado-with-MD3/dev/url-options.html).
The existing Studio JSON-path reader, Jsoup, RE2/J and serialization dependencies are reused.
The documentation is a compatibility reference; it does not relicense any upstream application.
