// Mocha's timeout (`mocha-timeout.js`) is not the only clock a long test runs against. Karma talks to
// the browser over a socket that answers pings from the page's main thread, and a wasm test is
// synchronous: while `MatchAiLadderTest` plays its ladder — 10 s locally, longer on a shared CI
// runner — nothing else on that thread runs, the pings go unanswered, and Karma drops the browser
// ("Disconnected ... reconnect failed before timeout of 2000ms (ping timeout)"). The tests had not
// failed; the harness had stopped listening. That is how v0.8.6's tag gate went red on Ubuntu while
// the same commit passed on `main`.
//
// So every clock that can expire during one synchronous test gets the same budget as Mocha's.
config.pingTimeout = 60000;
config.browserDisconnectTimeout = 60000;
config.browserNoActivityTimeout = 120000;
config.captureTimeout = 120000;
