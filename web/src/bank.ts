import { parse, stringify } from 'lossless-json'

export interface Account {
  id: bigint
  currency: 'USD'
  posted_balance: bigint
  available_balance: bigint
  reserved_balance: bigint
}

export interface Recipient {
  id: bigint
  name: string
}

export interface Payment {
  account_id: bigint
  beneficiary_id: bigint
  amount: bigint
  idempotency_key: string
}

interface Transfer {
  id: bigint
  beneficiary_id?: bigint
  destination: string
  amount: bigint
  created_at: string
  status: 'pending' | 'settled'
}

export interface Activity {
  key: string
  name: string
  category: string
  amount: bigint
  date: string
  status: 'pending' | 'settled'
  transferId?: bigint
  journalId?: bigint
}

export interface TimelineEvent {
  kind: 'transfer_created' | 'funds_reserved' | 'bank_confirmed' | 'transfer_settled'
  occurred_at: string
  journal_id?: bigint
}

export interface Journal {
  id: bigint
  entries: { account_id: bigint; side: 'debit' | 'credit'; amount: bigint }[]
}

interface DemoSession {
  accountId: bigint
  anchor: string
  deposits: Activity[]
  samplePayments: Record<string, { date: string; category: string }>
  seeded: boolean
}

export interface Dashboard {
  account: Account
  recipients: Recipient[]
  activity: Activity[]
}

// Decode integer tokens before they can round through JavaScript Number.
export function parseIntegerJson<T>(body: string): T {
  return parse(body, null, BigInt) as T
}

export function stringifyIntegerJson(value: object): string {
  const json = stringify(value)
  if (json === undefined) throw new TypeError('Expected a JSON object')
  return json
}

export class BankError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, body?: object): Promise<T> {
  const response = await fetch('/api' + path, {
    ...(body && { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: stringifyIntegerJson(body) }),
    signal: AbortSignal.timeout(15_000),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new BankError(response.status, error?.error ?? 'The banking service is unavailable.')
  }
  return parseIntegerJson<T>(await response.text())
}

export const sendPayment = (payment: Payment) => request<Transfer>('/bank/transfers', payment)
export const getTimeline = (id: bigint) => request<{ events: TimelineEvent[] }>(`/bank/transfers/${id}/timeline`)
export const getJournal = (id: bigint) => request<Journal>(`/journals/${id}`)

const sessionKey = 'wizardric-demo-v1'
const sampleExpenses = [
  { name: 'Harbor Apartments', category: 'Housing', amount: 125000n, daysAgo: 21 },
  { name: 'Sam Rivera', category: 'Personal transfer', amount: 8500n, daysAgo: 15 },
  { name: 'City Utilities', category: 'Bills', amount: 7800n, daysAgo: 10 },
  { name: 'Alex Morgan', category: 'Personal transfer', amount: 4250n, daysAgo: 2 },
]
const sampleDeposits = [
  { name: 'Northline Payroll', category: 'Salary', amount: 295000n, daysAgo: 27 },
  { name: 'Jamie Chen', category: 'Received transfer', amount: 11500n, daysAgo: 5 },
]

function sampleDate(anchor: string, daysAgo: number) {
  const date = new Date(anchor)
  date.setDate(date.getDate() - daysAgo)
  return date.toISOString()
}

// Sample display dates are illustrative. Payment and journal details use the real PostgreSQL timestamps.
async function seedSession(): Promise<DemoSession> {
  const saved = localStorage.getItem(sessionKey)
  let session: DemoSession | undefined = saved ? parseIntegerJson<DemoSession>(saved) : undefined
  if (session) {
    try {
      await request<Account>(`/bank/accounts/${session.accountId}`)
    } catch (error) {
      if (!(error instanceof BankError) || error.status !== 404) throw error
      session = undefined
    }
  }
  if (!session) {
    const account = await request<Account>('/bank/accounts', { name: 'Everyday' })
    session = { accountId: account.id, anchor: new Date().toISOString(), deposits: [], samplePayments: {}, seeded: false }
    localStorage.setItem(sessionKey, stringifyIntegerJson(session))
  }
  if (session.seeded) return session

  const { accountId, anchor } = session
  const path = `/bank/accounts/${accountId}`
  // The anchor distinguishes installations even if a local database is reset.
  const key = `wizardric:${accountId}:${anchor}`
  for (const [index, deposit] of sampleDeposits.entries()) {
    const result = await request<{ journal_id: bigint }>(path + '/simulated-funding', {
      idempotency_key: `${key}:deposit:${index}`, amount: deposit.amount,
    })
    session.deposits[index] = {
      key: `journal-${result.journal_id}`, name: deposit.name, category: deposit.category,
      amount: deposit.amount, date: sampleDate(anchor, deposit.daysAgo), status: 'settled', journalId: result.journal_id,
    }
  }
  for (const [index, expense] of sampleExpenses.entries()) {
    const recipient = await request<Recipient>(path + '/beneficiaries', {
      idempotency_key: `${key}:recipient:${index}`, name: expense.name, destination: `WZ-DEMO-${index + 1}`,
    })
    const transfer = await sendPayment({
      account_id: accountId, beneficiary_id: recipient.id, idempotency_key: `${key}:payment:${index}`, amount: expense.amount,
    })
    session.samplePayments[String(transfer.id)] = { date: sampleDate(anchor, expense.daysAgo), category: expense.category }
  }
  session.seeded = true
  localStorage.setItem(sessionKey, stringifyIntegerJson(session))
  return session
}

interface Page<T> {
  items: T[]
  next_before_id: bigint | null
}

async function readAll<T>(path: string): Promise<T[]> {
  const items: T[] = []
  let cursor: bigint | null = null
  do {
    const page: Page<T> = await request(path + '?limit=100' + (cursor === null ? '' : '&before_id=' + cursor))
    items.push(...page.items)
    cursor = page.next_before_id
  } while (cursor !== null)
  return items
}

let startup: Promise<DemoSession> | undefined

async function startSession(): Promise<DemoSession> {
  if (!navigator.locks) return seedSession()
  return await navigator.locks.request(sessionKey, seedSession)
}

export async function getDashboard(): Promise<Dashboard> {
  startup ??= startSession().catch((error) => {
    startup = undefined
    throw error
  })
  const session = await startup
  const path = `/bank/accounts/${session.accountId}`
  const [recipients, transfers] = await Promise.all([
    readAll<Recipient>(path + '/beneficiaries'),
    readAll<Transfer>(path + '/transfers'),
  ])
  // Read the balance after history. If a payment settles between the two reads,
  // a pending row still schedules a refresh to pick up the settled state.
  const account = await request<Account>(path)
  const names = new Map(recipients.map(({ id, name }) => [id, name]))
  const activity: Activity[] = [
    ...session.deposits,
    ...transfers.map((transfer): Activity => {
      const sample = session.samplePayments[String(transfer.id)]
      return {
        key: `transfer-${transfer.id}`,
        name: names.get(transfer.beneficiary_id ?? 0n) ?? transfer.destination,
        category: sample?.category ?? 'Personal transfer',
        date: sample?.date ?? transfer.created_at,
        amount: -transfer.amount,
        status: transfer.status,
        transferId: transfer.id,
      }
    }),
  ]
  activity.sort((left, right) => right.date.localeCompare(left.date))
  return { account, recipients, activity }
}

const shortDate = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })

export const dateLabel = (date: string) => shortDate.format(new Date(date))
