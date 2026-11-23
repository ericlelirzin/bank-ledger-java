export interface Account {
  id: number
  currency: 'USD'
  posted_balance: number
  available_balance: number
  reserved_balance: number
}

export interface Recipient {
  id: number
  name: string
}

export interface Payment {
  account_id: number
  beneficiary_id: number
  amount: number
  idempotency_key: string
}

interface Transfer {
  id: number
  beneficiary_id?: number
  destination: string
  amount: number
  created_at: string
  status: 'pending' | 'settled'
}

export interface Activity {
  key: string
  name: string
  category: string
  amount: number
  date: string
  status: 'pending' | 'settled'
  transferId?: number
  journalId?: number
}

export interface TimelineEvent {
  kind: 'transfer_created' | 'funds_reserved' | 'bank_confirmed' | 'transfer_settled'
  occurred_at: string
  journal_id?: number
}

export interface Journal {
  id: number
  entries: { account_id: number; side: 'debit' | 'credit'; amount: number }[]
}

interface DemoSession {
  accountId: number
  anchor: string
  deposits: Activity[]
  samplePayments: Record<number, { date: string; category: string }>
  seeded: boolean
}

export interface Dashboard {
  account: Account
  recipients: Recipient[]
  activity: Activity[]
}

export class BankError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

async function request<T>(path: string, body?: object): Promise<T> {
  const response = await fetch('/api' + path, {
    ...(body && { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
    signal: AbortSignal.timeout(15_000),
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new BankError(response.status, error?.error ?? 'The banking service is unavailable.')
  }
  return response.json()
}

export const sendPayment = (payment: Payment) => request<Transfer>('/bank/transfers', payment)
export const getTimeline = (id: number) => request<{ events: TimelineEvent[] }>(`/bank/transfers/${id}/timeline`)
export const getJournal = (id: number) => request<Journal>(`/journals/${id}`)

const sessionKey = 'wizardric-demo-v1'
const sampleExpenses = [
  { name: 'Harbor Apartments', category: 'Housing', amount: 125000, daysAgo: 21 },
  { name: 'Sam Rivera', category: 'Personal transfer', amount: 8500, daysAgo: 15 },
  { name: 'City Utilities', category: 'Bills', amount: 7800, daysAgo: 10 },
  { name: 'Alex Morgan', category: 'Personal transfer', amount: 4250, daysAgo: 2 },
]
const sampleDeposits = [
  { name: 'Northline Payroll', category: 'Salary', amount: 295000, daysAgo: 27 },
  { name: 'Jamie Chen', category: 'Received transfer', amount: 11500, daysAgo: 5 },
]

function sampleDate(anchor: string, daysAgo: number) {
  const date = new Date(anchor)
  date.setDate(date.getDate() - daysAgo)
  return date.toISOString()
}

// Sample display dates are illustrative. Payment and journal details use the real PostgreSQL timestamps.
async function seedSession(): Promise<DemoSession> {
  const saved = localStorage.getItem(sessionKey)
  let session: DemoSession | undefined = saved ? JSON.parse(saved) : undefined
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
    localStorage.setItem(sessionKey, JSON.stringify(session))
  }
  if (session.seeded) return session

  const { accountId, anchor } = session
  const path = `/bank/accounts/${accountId}`
  // The anchor distinguishes installations even if a local database is reset.
  const key = `wizardric:${accountId}:${anchor}`
  for (const [index, deposit] of sampleDeposits.entries()) {
    const result = await request<{ journal_id: number }>(path + '/simulated-funding', {
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
    session.samplePayments[transfer.id] = { date: sampleDate(anchor, expense.daysAgo), category: expense.category }
  }
  session.seeded = true
  localStorage.setItem(sessionKey, JSON.stringify(session))
  return session
}

interface Page<T> {
  items: T[]
  next_before_id: number | null
}

async function readAll<T>(path: string): Promise<T[]> {
  const items: T[] = []
  let cursor: number | null = null
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
      const sample = session.samplePayments[transfer.id]
      return {
        key: `transfer-${transfer.id}`,
        name: names.get(transfer.beneficiary_id ?? 0) ?? transfer.destination,
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

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
const shortDate = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })

export const money = (cents: number) => usd.format(cents / 100)
export const dateLabel = (date: string) => shortDate.format(new Date(date))

export function parseAmount(value: string) {
  const match = /^(\d{1,13})(?:\.(\d{1,2}))?$/.exec(value.trim())
  if (!match) return null
  const cents = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null
}
