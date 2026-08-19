//> using scala 3.8.4
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
//> using test.dep org.scalameta::munit:1.3.5
//> using test.dep org.scalameta::munit-scalacheck:1.3.0
//> using option -deprecation -feature -Wunused:all
