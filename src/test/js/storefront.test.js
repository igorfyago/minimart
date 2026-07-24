// THE STOREFRONT'S SECOND COAT · realism and ecosystem alignment lessons.
// Run from the repo root: node --test src/test/js/storefront.test.js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const html = readFileSync(new URL('../../main/resources/web/index.html', import.meta.url), 'utf8');

test('T1 · the receipt is a real receipt: order number, delivery estimate, timeline', () => {
  const receipt = html.slice(html.indexOf("co('#co-receipt')"), html.indexOf("co('#co-receipt')") + 1200);
  assert.ok(receipt.includes('order') && /orderId|order id|#/.test(receipt),
    'the receipt names the order');
  assert.ok(/delivery|arriving|estimate/i.test(receipt), 'and says when to expect the parcel');
});

test('T1 · the card method names the instrument, not the concept', () => {
  assert.ok(html.includes('CARD_LAST4'), 'the page keeps the card last4 from the bank');
  assert.ok(html.includes("CARD_LAST4 ? ' ··' + CARD_LAST4"), 'the method shows ··4388 like a real store');
});

test('T3 · the mart highlights a deep-linked order (?order=)', () => {
  assert.ok(html.includes("URLSearchParams"), 'the page reads its query string');
  assert.ok(html.includes(".get('order')"), 'and looks for the order param');
  assert.ok(html.includes('order-marked'), 'and marks the row it names');
});

test('T4 · the guest checkout carries a real sign-in link, not passive text', () => {
  assert.ok(html.includes('auth.b4rruf3t.com/login?next='), 'the guest note links to the estate login');
});

test('T5 · a product tile opens its detail panel', () => {
  assert.ok(html.includes('id="pd-overlay"') || html.includes('id="pd-panel"'), 'the detail panel exists');
  assert.ok(html.includes('openProduct'), 'tiles open it');
});

test('T7 · reviews are shown and honestly labeled as the agents\' own', () => {
  assert.ok(html.includes('REVIEWS'), 'products carry reviews');
  const near = html.slice(html.indexOf('REVIEWS'), html.indexOf('REVIEWS') + 1200);
  assert.ok(/agent|simulated|seeded/i.test(near), 'and the label says whose opinions they are');
});

test('the guest gets the tape too: arrival replays the last few minutes, not silence', () => {
  assert.ok(html.includes('tapeFeedFirst'), 'the walker tracks arrival');
  const tick = html.slice(html.indexOf('async function tapeFeedTick'),
                          html.indexOf('async function tapeFeedTick') + 1400);
  assert.ok(tick.includes('3 * 3600 * 1000'), 'recent history still counts as news on arrival');
  assert.ok(!tick.includes('if (tapeFeedFirst) { tapeFeedFirst = false; return; }'),
    'the first poll no longer swallows everything — a guest on a quiet shop still sees the tape');
});
