// BROWSER-LEG IDENTITY · the estate cookie must always be exchangeable for a
// fresh token. Regression test for the 2026-07-23 break: a shopper with an
// EXPIRED b4rruf3t_token in localStorage was never re-exchanged (the page
// short-circuited on token-presence, not token-validity), so the shop served
// them anonymously and checkout rode psp instead of their bank card.
//
// The seam under test is the identity block of index.html, executed in a vm
// sandbox with a mocked fetch/localStorage — no browser needed.
//
// Run from the repo root:  node --test src/test/js/browser-identity.test.js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../../main/resources/web/index.html', import.meta.url), 'utf8');

// Extract the identity block: from the whoami fetch through the authHeaders
// definition (which checkout depends on).
function identityScript() {
  const start = html.indexOf("fetch(AUTH + '/v1/whoami'");
  const ah = html.indexOf('const authHeaders');
  const m = /^};/m.exec(html.slice(ah));
  const end = ah + m.index + 2;
  assert.ok(start > 0 && ah > start && end > ah, 'identity block found in index.html');
  // the cart seam: state + line math + the checkout body
  const cart = html.indexOf('// THE CART');
  let extra = '';
  if (cart > 0) {
    const cm = /\n};\n/m.exec(html.slice(cart));
    extra = '\n' + html.slice(cart, cart + cm.index + 3);
  }
  return 'let CUSTOMER = 7001;\n' + html.slice(start, end) + extra;
}

function makeWorld({ storedToken, refreshAnswers, martWhoamiAnswers }) {
  const store = { b4rruf3t_token: storedToken ?? null };
  const calls = { refresh: 0, martWhoami: 0 };
  const localStorage = {
    getItem: k => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
  };
  const fetch = (url, opts = {}) => {
    if (url.includes('/v1/whoami')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve({ name: 'igor', email: 'i@b4rruf3t.com' }) });
    }
    if (url.includes('/v1/tokens/refresh')) {
      calls.refresh++;
      const a = refreshAnswers[Math.min(calls.refresh - 1, refreshAnswers.length - 1)];
      return Promise.resolve({ ok: a.ok, json: () => Promise.resolve(a.body) });
    }
    if (url.includes('/api/whoami')) {
      calls.martWhoami++;
      const auth = (opts.headers && opts.headers.Authorization) || '';
      const a = martWhoamiAnswers(auth);
      return Promise.resolve({ ok: a.ok, json: () => Promise.resolve(a.body) });
    }
    if (url.includes('/api/card/activity')) {
      return Promise.resolve({ ok: true, json: () => Promise.resolve([{ last4: '4388' }]) });
    }
    throw new Error('unexpected fetch ' + url);
  };
  const sandbox = {
    AUTH: 'https://auth.b4rruf3t.com',
    localStorage, fetch,
    Promise, JSON, Number, String, Math, console,
    location: { hostname: 'mart.b4rruf3t.com' },
    crypto: { randomUUID: () => '00000000-0000-0000-0000-000000000000' },
    uuid: () => '00000000-0000-0000-0000-000000000000',
    $: () => ({ textContent: '', style: {} }),
    document: { querySelector: () => null, querySelectorAll: () => [] },
  };
  sandbox.window = sandbox;
  return { sandbox, store, calls };
}

async function run(world) {
  vm.createContext(world.sandbox);
  vm.runInContext(identityScript(), world.sandbox);
  // let the promise chain settle
  for (let i = 0; i < 30; i++) await new Promise(r => setImmediate(r));
  // `let` bindings live in the context's scope, not on the global object:
  // ask the context for their values rather than reading sandbox props.
  world.IDENTIFIED = vm.runInContext('IDENTIFIED', world.sandbox);
  world.checkoutBody = () => JSON.parse(vm.runInContext('JSON.stringify(checkoutBody())', world.sandbox));
}

test('expired stored token is exchanged for a fresh one before naming the customer', async () => {
  const w = makeWorld({
    storedToken: 'stale-expired-token',
    refreshAnswers: [{ ok: true, body: { access_token: 'fresh-token', refresh_token: 'r2' } }],
    // the mart rejects the stale token, accepts the fresh one
    martWhoamiAnswers: auth => auth.includes('fresh-token')
      ? { ok: true, body: { customer: 10 } }
      : { ok: true, body: { customer: null } },
  });
  await run(w);
  assert.equal(w.store.mart_customer, '10',
    'the estate shopper is their bank self, not an anonymous mint');
  assert.equal(w.store.b4rruf3t_token, 'fresh-token',
    'a stale token must be replaced via the estate cookie, not trusted');
});

test('fresh valid stored token skips the exchange and names the customer directly', async () => {
  const w = makeWorld({
    storedToken: 'good-token',
    refreshAnswers: [{ ok: true, body: { access_token: 'unused', refresh_token: 'x' } }],
    martWhoamiAnswers: auth => auth.includes('good-token')
      ? { ok: true, body: { customer: 10 } }
      : { ok: true, body: { customer: null } },
  });
  await run(w);
  assert.equal(w.store.mart_customer, '10');
  assert.equal(w.calls.refresh, 0, 'a token that works must not be refreshed');
});

test('no stored token exchanges the estate cookie once', async () => {
  const w = makeWorld({
    storedToken: null,
    refreshAnswers: [{ ok: true, body: { access_token: 'minted', refresh_token: 'r' } }],
    martWhoamiAnswers: () => ({ ok: true, body: { customer: 10 } }),
  });
  await run(w);
  assert.equal(w.calls.refresh, 1);
  assert.equal(w.store.mart_customer, '10');
});

test('dead token plus failed re-exchange leaves the anonymous shopper standing', async () => {
  const w = makeWorld({
    storedToken: 'stale-expired-token',
    refreshAnswers: [{ ok: false, body: null }],
    martWhoamiAnswers: () => ({ ok: true, body: { customer: null } }),
  });
  await run(w);
  assert.equal(w.store.mart_customer ?? '7001', '7001',
    'no fresh token, no named customer: the anonymous shop works on');
});

// THE PAYMENT CHOICE · a signed-in estate shopper picks the rail their
// purchase rides: "main acct" debits the bank statement, "credit card" rides
// the card. Anonymous shoppers are never asked — the processor stands.
test('an identified shopper choosing main acct sends pay=main with the order', async () => {
  const w = makeWorld({
    storedToken: 'good-token',
    refreshAnswers: [],
    martWhoamiAnswers: () => ({ ok: true, body: { customer: 10 } }),
  });
  await run(w);
  assert.equal(w.IDENTIFIED, true, 'the page knows the shopper is their bank self');
  w.eval = s => vm.runInContext(s, w.sandbox);
  w.eval('addToCart({id:"v-focus-30", title:"Focus Stack", price:"89"})');
  const body = w.checkoutBody();
  assert.equal(body.pay, 'main', 'the default choice is the main account');
  assert.equal(body.customer, '10');
});

test('choosing the card sends pay=card, and anonymous is never offered a choice', async () => {
  const w = makeWorld({
    storedToken: null,
    refreshAnswers: [{ ok: false, body: null }],   // no estate cookie: anonymous
    martWhoamiAnswers: () => ({ ok: true, body: { customer: null } }),
  });
  await run(w);
  assert.equal(w.IDENTIFIED, false);
  w.eval = s => vm.runInContext(s, w.sandbox);
  w.eval('addToCart({id:"v-focus-30", title:"Focus Stack", price:"89"})');
  const body = w.checkoutBody();
  assert.equal('pay' in body, false, 'anonymous checkout carries no rail choice — psp stands');
});

// THE REAL CHECKOUT · a cart, a summary, a deliberate payment method, one
// Pay button. The rail choice moved OUT of the shop header into the checkout
// where a shopper actually makes it.
test('the cart totals lines and the checkout body carries every line', async () => {
  const w = makeWorld({
    storedToken: 'good-token',
    refreshAnswers: [],
    martWhoamiAnswers: () => ({ ok: true, body: { customer: 10 } }),
  });
  await run(w);
  const cart = vm => vm;
  // two of one product, one of another
  w.eval = s => vm.runInContext(s, w.sandbox);
  w.eval('CART = {}; addToCart({id:"v-focus-30", price:"89"}); addToCart({id:"v-focus-30", price:"89"}); addToCart({id:"v-sleep-30", price:"69"});');
  const total = w.eval('cartTotal()');
  assert.equal(total, '247.00', '2×89 + 1×69 · got ' + total);
  const lines = JSON.parse(w.eval('JSON.stringify(cartLines())'));
  assert.equal(lines.length, 2, 'same product folds into one line');
  assert.equal(lines.find(l => l.variant === 'v-focus-30').qty, 2);
  const body = JSON.parse(w.eval('JSON.stringify(checkoutBody())'));
  assert.deepEqual(body.lines.map(l => [l.variant, l.qty]).sort(),
    [['v-focus-30', 2], ['v-sleep-30', 1]].sort());
  assert.equal(body.pay, 'main', 'the chosen method rides the body');
  assert.equal(body.customer, '10');
});

test('switching the method inside checkout to card changes the body, not the header', async () => {
  const w = makeWorld({
    storedToken: 'good-token',
    refreshAnswers: [],
    martWhoamiAnswers: () => ({ ok: true, body: { customer: 10 } }),
  });
  await run(w);
  w.eval = s => vm.runInContext(s, w.sandbox);
  w.eval('addToCart({id:"v-starter-14", title:"Starter", price:"29"}); setPay("card");');
  const body = JSON.parse(w.eval('JSON.stringify(checkoutBody())'));
  assert.equal(body.pay, 'card');
});
