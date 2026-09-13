// Mocha's default is two seconds a test, and `MatchAiLadderTest` plays 160 full matches between
// two search-based opponents: about 3.5 s on the desktop JVM and 10 s under wasm. The ladder is
// what it is measuring, so the budget moves rather than the trial count.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
