import assert from 'node:assert/strict'
import test from 'node:test'
import { parseIntegerJson, sendPayment, stringifyIntegerJson } from '../src/bank.ts'
import { amountInput, money, parseAmount } from '../src/money.ts'

test('round-trips exact wire integers and persisted payments', async (context) => {
  const body = '{"account_id":9007199254740993,"beneficiary_id":9007199254740995,"amount":9223372036854775807,"idempotency_key":"retry"}'
  const payment = parseIntegerJson(body)
  assert.equal(payment.amount, 9223372036854775807n)
  assert.equal(payment.account_id, 9007199254740993n)
  assert.equal(stringifyIntegerJson(payment), body)

  context.mock.method(globalThis, 'fetch', async (path, options) => {
    assert.equal(path, '/api/bank/transfers')
    assert.equal(options.body, body)
    return new Response('{"id":9007199254740997,"amount":9223372036854775807}')
  })
  assert.deepEqual(await sendPayment(payment), { id: 9007199254740997n, amount: payment.amount })
})

test('decodes existing storage and preserves cents throughout USD formatting', () => {
  assert.deepEqual(parseIntegerJson('{"accountId":1,"deposits":[{"amount":11500}]}'), {
    accountId: 1n, deposits: [{ amount: 11500n }],
  })
  assert.equal(money(9007199254740987n), '$90,071,992,547,409.87')
  assert.equal(money(9223372036854775807n), '$92,233,720,368,547,758.07')
  assert.equal(money(-1n), '-$0.01')
  assert.equal(amountInput(9223372036854775807n), '92233720368547758.07')
  assert.equal(parseAmount('92233720368547758.07'), 9223372036854775807n)
  assert.equal(parseAmount('92233720368547758.08'), null)
  assert.equal(parseAmount('1.001'), null)
  assert.equal(parseAmount('0'), null)
})
