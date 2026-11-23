import {
  Activity as ActivityIcon, ArrowDownLeft, ArrowDownToLine, ArrowRight, ArrowUpRight,
  Banknote, Building2, Check, ChevronRight, CircleAlert, CreditCard, House, Landmark, List,
  LockKeyhole, Search, Send, Wallet, X, Zap,
} from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import {
  BankError, dateLabel, getDashboard, getJournal, getTimeline, money, parseAmount, sendPayment,
  type Activity, type Dashboard, type Journal, type Payment, type TimelineEvent,
} from './bank'

const navigation = [
  { label: 'Home', path: 'home', icon: House },
  { label: 'Accounts', path: 'accounts', icon: Wallet },
  { label: 'Cards', path: 'cards', icon: CreditCard },
  { label: 'Analytics', path: 'analytics', icon: ActivityIcon },
  { label: 'Transactions', path: 'transactions', icon: List },
] as const

type Route = typeof navigation[number]['path']
const pendingKey = 'wizardric-pending-payment'

function currentRoute(): Route {
  const path = location.hash.slice(1)
  return navigation.find((item) => item.path === path)?.path ?? 'home'
}

function Sidebar({ route }: { route: Route }) {
  return (
    <aside className="sidebar">
      <a className="brand" href="#home" aria-label="Wizardric home">
        <span className="brand-icon"><Landmark size={21} strokeWidth={2} /></span>
        <span>Wizardric<small>Personal banking</small></span>
      </a>
      <nav aria-label="Main navigation">
        {navigation.map(({ label, path, icon: Icon }) => (
          <a key={path} href={'#' + path} aria-current={route === path ? 'page' : undefined}>
            <Icon size={19} strokeWidth={1.7} /><span>{label}</span>
          </a>
        ))}
      </nav>
      <div className="sidebar-profile">
        <span className="avatar">AM</span>
        <span>Avery Mitchell<small>Demo account</small></span>
      </div>
    </aside>
  )
}

function Balance({ account, onSend }: { account: Dashboard['account']; onSend: () => void }) {
  return (
    <section className="balance" aria-label="Account balance">
      <div className="balance-top">
        <span>Available balance</span>
        <span className="currency">USD</span>
      </div>
      <div className="balance-amount">{money(account.available_balance)}</div>
      <div className="balance-breakdown">
        <span>Posted <strong>{money(account.posted_balance)}</strong></span>
        <span>Reserved <strong>{money(account.reserved_balance)}</strong></span>
      </div>
      <button className="button mint" onClick={onSend}><ArrowUpRight size={18} /> Send money</button>
    </section>
  )
}

function AccountCard({ account }: { account: Dashboard['account'] }) {
  return (
    <section className="panel account-card">
      <div className="section-header"><h2>Your account</h2><a href="#accounts" className="text-link">Details <ChevronRight size={15} /></a></div>
      <div className="account-line">
        <span className="account-icon"><Wallet size={21} strokeWidth={1.7} /></span>
        <span><strong>Everyday</strong><small>USD checking</small></span>
        <strong className="account-value">{money(account.available_balance)}</strong>
      </div>
    </section>
  )
}

function ActivitySymbol({ activity }: { activity: Activity }) {
  const Icon = activity.category === 'Housing' ? Building2 : activity.category === 'Bills' ? Zap
    : activity.amount > 0 ? ArrowDownLeft : ArrowUpRight
  return <span className={'row-icon ' + (activity.amount > 0 ? 'incoming' : '')}><Icon size={18} strokeWidth={1.7} /></span>
}

function ActivityList({ activity, onSelect }: { activity: Activity[]; onSelect: (key: string) => void }) {
  if (!activity.length) return <p className="empty-list">No transactions match this view.</p>
  return (
    <ul className="activity-list">
      {activity.map((row) => (
        <li key={row.key}>
          <button onClick={() => onSelect(row.key)}>
            <ActivitySymbol activity={row} />
            <span className="row-name"><strong>{row.name}</strong><small>{row.category}</small></span>
            <span className="row-date">{dateLabel(row.date)}</span>
            <span className="row-value">
              <strong className={row.amount > 0 ? 'positive' : ''}>{row.amount > 0 ? '+' : '−'}{money(Math.abs(row.amount))}</strong>
              {row.status === 'pending' && <small className="pending">Pending</small>}
            </span>
            <ChevronRight size={15} className="row-chevron" />
          </button>
        </li>
      ))}
    </ul>
  )
}

function CashFlow({ activity, expanded = false }: { activity: Activity[]; expanded?: boolean }) {
  const [days, setDays] = useState(30)
  const start = new Date()
  start.setDate(start.getDate() - days)
  const recent = activity.filter((row) => row.status === 'settled' && new Date(row.date) >= start)
  const incoming = recent.reduce((total, row) => total + Math.max(0, row.amount), 0)
  const outgoing = recent.reduce((total, row) => total + Math.max(0, -row.amount), 0)
  const largest = Math.max(incoming, outgoing, 1)
  return (
    <section className="panel cashflow">
      <div className="section-header">
        <h2>Cash flow</h2>
        {expanded ? <select aria-label="Cash flow period" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          <option value={7}>Last 7 days</option><option value={30}>Last 30 days</option>
        </select> : <span className="period">Last 30 days</span>}
      </div>
      {([{ label: 'Money in', amount: incoming, icon: ArrowDownToLine, tone: 'in' }, { label: 'Money out', amount: outgoing, icon: ArrowUpRight, tone: 'out' }]).map(({ label, amount, icon: Icon, tone }) => (
        <div className="cashflow-line" key={tone}>
          <div className="cashflow-label"><span><Icon size={16} />{label}</span><strong>{money(amount)}</strong></div>
          <div className={'bar ' + tone} aria-hidden="true"><div style={{ width: amount / largest * 100 + '%' }} /></div>
        </div>
      ))}
      <div className="cashflow-footer"><span>Net movement</span><strong>{money(incoming - outgoing)}</strong></div>
    </section>
  )
}

function Transactions({ activity, onSelect }: { activity: Activity[]; onSelect: (key: string) => void }) {
  const [query, setQuery] = useState('')
  const [direction, setDirection] = useState('all')
  const filtered = activity.filter((row) =>
    (row.name + ' ' + row.category).toLowerCase().includes(query.trim().toLowerCase())
    && (direction === 'all' || (direction === 'in' ? row.amount > 0 : row.amount < 0)),
  )
  return (
    <section className="panel transaction-panel">
      <div className="filters">
        <label className="search"><Search size={17} /><input type="search" placeholder="Search transactions" aria-label="Search transactions" value={query} onChange={(e) => setQuery(e.target.value)} /></label>
        <select aria-label="Transaction direction" value={direction} onChange={(e) => setDirection(e.target.value)}>
          <option value="all">All transactions</option><option value="in">Money in</option><option value="out">Money out</option>
        </select>
      </div>
      <ActivityList activity={filtered} onSelect={onSelect} />
    </section>
  )
}

function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { dialog.current?.showModal() }, [])
  return (
    <dialog ref={dialog} onCancel={onClose} aria-labelledby="dialog-title">
      <div className="dialog-header"><h2 id="dialog-title">{title}</h2><button className="icon-button" onClick={onClose} aria-label="Close"><X size={20} /></button></div>
      {children}
    </dialog>
  )
}

const eventLabels: Record<TimelineEvent['kind'], string> = {
  transfer_created: 'Transfer created', funds_reserved: 'Funds reserved',
  bank_confirmed: 'Bank confirmed', transfer_settled: 'Transfer settled',
}

function Details({ activity, onClose }: { activity: Activity; onClose: () => void }) {
  const [details, setDetails] = useState<{ events: TimelineEvent[]; journals: Journal[]; error?: string }>()
  const { transferId, journalId, status } = activity
  useEffect(() => {
    let active = true
    async function load() {
      const events = transferId ? (await getTimeline(transferId)).events : []
      const ids = [...events.flatMap((event) => event.journal_id ? [event.journal_id] : []), ...(journalId ? [journalId] : [])]
      const journals = await Promise.all(ids.map(getJournal))
      if (active) setDetails({ events, journals })
    }
    void load().catch(() => {
      if (active) setDetails({ events: [], journals: [], error: 'Could not load the accounting details. Close and reopen to retry.' })
    })
    return () => { active = false }
  }, [transferId, journalId, status])
  return (
    <Dialog title="Transaction details" onClose={onClose}>
      <div className="detail-summary">
        <ActivitySymbol activity={activity} />
        <p>{activity.name}</p><strong>{money(activity.amount)}</strong>
        <span>{dateLabel(activity.date)} · {status === 'pending' ? 'Pending' : 'Completed'}</span>
      </div>
      {!details && <p role="status">Loading details…</p>}
      {details?.error && <p className="error-text" role="alert">{details.error}</p>}
      {!!details?.events.length && <ol className="timeline">
        {details.events.map((event) => <li key={event.kind}><Check size={15} /><span>{eventLabels[event.kind]}</span><time dateTime={event.occurred_at}>{new Date(event.occurred_at).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}</time></li>)}
      </ol>}
      {!!details?.journals.length && <details className="ledger-details">
        <summary>Ledger entries</summary>
        {details.journals.map((journal) => <div className="journal" key={journal.id}>
          <strong>Journal #{journal.id}</strong>
          {journal.entries.map((entry, index) => <div key={index}><span>{entry.side} · Account {entry.account_id}</span><strong>{money(entry.amount)}</strong></div>)}
        </div>)}
      </details>}
    </Dialog>
  )
}

function SendDialog({ dashboard, pending, submit, onClose }: {
  dashboard: Dashboard; pending: Payment | null; submit: (payment: Payment) => Promise<void>; onClose: () => void
}) {
  const [recipient, setRecipient] = useState(pending?.beneficiary_id ?? dashboard.recipients.find(({ name }) => name === 'Alex Morgan')?.id ?? dashboard.recipients[0]?.id)
  const [amount, setAmount] = useState(pending ? (pending.amount / 100).toFixed(2) : '25.00')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submitForm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    const cents = parseAmount(amount)
    if (!pending && (!recipient || cents === null)) {
      setError('Choose a recipient and enter a valid amount in USD.')
      return
    }
    if (!pending && cents !== null && cents > dashboard.account.available_balance) {
      setError('Insufficient available funds. Enter a smaller amount.')
      return
    }
    const payment = pending ?? {
      account_id: dashboard.account.id, beneficiary_id: recipient!, amount: cents!, idempotency_key: crypto.randomUUID(),
    }
    setBusy(true)
    setError('')
    try {
      await submit(payment)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not send this payment.')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog title="Send money" onClose={onClose}>
      <form className="payment-form" onSubmit={(event) => void submitForm(event)}>
        <label>From <span>Everyday · {money(dashboard.account.available_balance)} available</span></label>
        <label>To
          <select disabled={busy || !!pending} value={recipient ?? ''} onChange={(event) => setRecipient(Number(event.target.value))}>
            {dashboard.recipients.map((person) => <option key={person.id} value={person.id}>{person.name}</option>)}
          </select>
        </label>
        <label>Amount in USD<input inputMode="decimal" disabled={busy || !!pending} value={amount} onChange={(event) => setAmount(event.target.value)} /></label>
        {pending && !busy && <p className="payment-note">Check the original payment by retrying with the same details.</p>}
        {error && <p className="error-text" role="alert">{error}</p>}
        <button className="button primary" disabled={busy}><Send size={17} />{busy ? 'Sending…' : pending ? 'Retry payment' : 'Send payment'}</button>
      </form>
    </Dialog>
  )
}

export function App() {
  const [route, setRoute] = useState(currentRoute)
  const [dashboard, setDashboard] = useState<Dashboard>()
  const [loadError, setLoadError] = useState('')
  const [detailKey, setDetailKey] = useState<string>()
  const [sending, setSending] = useState(false)
  const [pending, setPending] = useState<Payment | null>(() => {
    const saved = sessionStorage.getItem(pendingKey)
    return saved ? JSON.parse(saved) : null
  })
  const refreshSequence = useRef(0)

  async function refresh() {
    const sequence = ++refreshSequence.current
    try {
      const next = await getDashboard()
      if (sequence === refreshSequence.current) {
        setDashboard(next)
        setLoadError('')
      }
    } catch {
      if (sequence === refreshSequence.current) setLoadError('Could not connect to the banking service.')
    }
  }

  useEffect(() => {
    function navigate() { setRoute(currentRoute()) }
    window.addEventListener('hashchange', navigate)
    void refresh()
    return () => window.removeEventListener('hashchange', navigate)
  }, [])

  const hasPending = dashboard?.activity.some((row) => row.status === 'pending') ?? false
  useEffect(() => {
    if (!hasPending || loadError) return
    // Schedule the next read only after this read updates the dashboard.
    const timer = window.setTimeout(() => void refresh(), 1100)
    return () => window.clearTimeout(timer)
  }, [hasPending, dashboard, loadError])

  async function submitPayment(payment: Payment) {
    if (payment.account_id !== dashboard?.account.id) {
      sessionStorage.removeItem(pendingKey)
      setPending(null)
      throw new Error('This payment belongs to a previous demo account. Open a new payment to continue.')
    }
    sessionStorage.setItem(pendingKey, JSON.stringify(payment))
    setPending(payment)
    let transfer
    try {
      transfer = await sendPayment(payment)
    } catch (error) {
      if (error instanceof BankError && [400, 404, 409, 422].includes(error.status)) {
        sessionStorage.removeItem(pendingKey)
        setPending(null)
        if (error.status === 422) {
          await refresh()
          throw new Error('The payment was declined. Check your available balance and try again.')
        }
        throw error
      }
      throw new Error('The bank did not confirm the result. Retry this payment to check it without sending twice.')
    }
    sessionStorage.removeItem(pendingKey)
    setPending(null)
    setSending(false)
    setDetailKey('transfer-' + transfer.id)
    await refresh()
  }

  const activity = dashboard?.activity.find((row) => row.key === detailKey)
  const title = navigation.find((item) => item.path === route)!.label

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content" onClick={(event) => {
        event.preventDefault()
        document.getElementById('main-content')?.focus()
      }}>Skip to content</a>
      <Sidebar route={route} />
      <main id="main-content" tabIndex={-1}>
        <div className="main-content">
          <header className="page-header">
            <h1>{route === 'home' ? 'Welcome back, Avery' : title}</h1>
            <span className="demo-label"><LockKeyhole size={14} /> Simulated banking</span>
          </header>
          {loadError && <div className="notice" role="alert"><CircleAlert size={18} /><span>{loadError}</span><button onClick={() => void refresh()}>Retry</button></div>}
          {pending && !sending && <div className="notice" role="status"><CircleAlert size={18} /><span>A payment is awaiting confirmation.</span><button onClick={() => setSending(true)}>Check payment</button></div>}
          {route === 'cards' ? (
            <section className="panel cards-empty">
              <span className="card-icon"><CreditCard size={34} strokeWidth={1.5} /></span>
              <h2>Virtual cards are coming soon</h2>
              <p>You’ll be able to view your cards and track card payments here.</p>
            </section>
          ) : !dashboard ? (
            !loadError && <section className="panel loading" role="status">Getting your account ready…</section>
          ) : route === 'home' ? (
            <div className="dashboard-grid">
              <div className="dashboard-column">
                <Balance account={dashboard.account} onSend={() => setSending(true)} />
                <section className="panel recent">
                  <div className="section-header"><h2>Recent activity</h2><a className="text-link" href="#transactions">View all <ChevronRight size={15} /></a></div>
                  <ActivityList activity={dashboard.activity.slice(0, 4)} onSelect={setDetailKey} />
                </section>
              </div>
              <div className="dashboard-column">
                <AccountCard account={dashboard.account} />
                <CashFlow activity={dashboard.activity} />
              </div>
            </div>
          ) : route === 'accounts' ? (
            <div className="dashboard-grid">
              <Balance account={dashboard.account} onSend={() => setSending(true)} />
              <section className="panel account-information">
                <span className="account-icon"><Banknote size={21} /></span>
                <h2>Everyday</h2><p>USD checking account</p>
                <a className="text-link" href="#transactions">View transactions <ArrowRight size={15} /></a>
              </section>
            </div>
          ) : route === 'analytics' ? <CashFlow activity={dashboard.activity} expanded /> : (
            <Transactions activity={dashboard.activity} onSelect={setDetailKey} />
          )}
        </div>
      </main>
      {sending && dashboard && <SendDialog dashboard={dashboard} pending={pending} submit={submitPayment} onClose={() => setSending(false)} />}
      {activity && !sending && <Details key={activity.key} activity={activity} onClose={() => setDetailKey(undefined)} />}
    </div>
  )
}
