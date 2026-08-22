//> using scala 3.8.4
// THE FIVE NOTE TYPE DEFINITIONS ARE LOADED FROM THE CLASSPATH, so the directory holding them
// has to be on it. Everything under `resources/` is copied there verbatim, which is what lets
// `anki/NoteTypeAssets.scala` read `/note-types/<slug>/manifest.json` and the template and
// stylesheet files that manifest names.
//
// THE WRAPPER DIRECTORY IS THE POINT, not tidiness. Pointing this at `./note-types` directly
// would put `/basic/`, `/cloze/`, `/cloze-sequence/` and a bare `/README.md` at the ROOT of the
// classpath, where a dependency shipping the same path would shadow ours — and a shadowed
// template is not an error, it is a note type whose cards render someone else's markup.
// Namespacing under `/note-types/` makes that collision implausible rather than merely
// unlikely. The files were moved here from `obsidian-anki-custom-sync/note-types/` in the same
// commit that added this line.
//> using resourceDir ./resources
//> using dep org.typelevel::laika-core:1.3.2
//> using dep org.typelevel::cats-core:2.12.0
//> using dep org.yaml:snakeyaml:2.6
//> using dep com.monovore::decline-effect:2.6.2
// The AnkiConnect interpreter. Ember rather than the JDK-backed client, and http4s'
// Client[F] rather than a raw HTTP call, because the interpreter is F-polymorphic like the
// rest of the tool and a Client is a Resource — connection lifetime is sequenced by the same
// mechanism as everything else instead of a close() to forget.
//> using dep org.http4s::http4s-ember-client:0.23.36
//> using dep org.http4s::http4s-circe:0.23.36
//> using dep io.circe::circe-parser:0.14.16
// A LOGGING BACKEND, PRESENT ONLY SO THAT NOTHING IS PRINTED BY ACCIDENT. http4s brings
// `slf4j-api` with no binding, and slf4j then writes FOUR WARNING LINES to stderr on every
// single run that contacts Anki — "Failed to load class StaticLoggerBinder" and friends. That is
// the first thing a person sees from the packaged binary, and it says nothing about their vault.
//
// `slf4j-simple` PINNED TO `error` RATHER THAN `slf4j-nop`, and the difference is the point. A
// no-op binding would silence a genuine failure as thoroughly as it silences the warnings. This
// keeps the error channel open. It costs nothing to do so: measured on 2026-08-22 by running a
// full `sync --dry-run` against a live collection with this backend at DEBUG, ember logged
// NOTHING AT ALL on the healthy path, so `error` is silent in ordinary use rather than merely
// quiet. The level is set by `resources/simplelogger.properties`, which is read from the
// classpath and therefore survives packaging — a `-D` on the command line would not.
//> using dep org.slf4j:slf4j-simple:2.0.18
//> using test.dep org.scalameta::munit:1.3.5
//> using test.dep org.scalameta::munit-scalacheck:1.3.0
// AN INEXHAUSTIVE MATCH IS AN ERROR, NOT A WARNING. Scala reports one as a warning and the
// build still exits 0, so "the compiler will tell us" was false: a missing case shipped
// silently. Every defect in the extraction layer this week was a construct nobody matched.
// This is what makes a closed type's exhaustiveness an actual guarantee rather than a habit.
//
// THE PATTERN HAS NO SPACES IN IT, AND THAT IS NOT A STYLE CHOICE. scala-cli splits a `using
// option` line on whitespace, so the obvious filter — matching the full sentence "match may not
// be exhaustive" — is torn into fragments, forms no filter at all, and the build compiles clean
// while guaranteeing nothing. Verified by compiling a deliberately inexhaustive match: with the
// spaced form it was a warning and the build exited 0; with this form it is an error.
//> using option -deprecation -feature -Wunused:all -Wconf:msg=exhaustive:e
