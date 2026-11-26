import { ChevronRight, CreditCard } from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  BankError, dateLabel, getCardActivity, getCardActivityDetail, getCardOverview, issueCard, setCardPaused,
  type CardActivity, type CardActivityDetail, type CardOverview,
} from './bank'
import { Dialog } from './Dialog'
import { money } from './money'

function label(value: string) {
  return value.charAt(0).toUpperCase() + value.slice(1).replaceAll('_', ' ')
}

function isCredit(type: string) {
  return type === 'refund' || type === 'original_credit'
}

function holdLabel(type: string) {
  return isCredit(type) ? 'Pending credit' : 'On hold'
}

function holdMoney(cents: bigint) {
  return money(cents < 0n ? -cents : cents)
}

const eventLabels: Record<string, string> = {
  authorization: 'Authorization',
  authorization_reversal: 'Authorization reversed',
  clear: 'Capture',
  force_clear: 'Forced capture',
  refund_financial: 'Refund received',
  credit_clear: 'Refund cleared',
  credit_applied: 'Refund applied',
}

function ActivityDetail({ accountId, id, onClose }: { accountId: bigint; id: string; onClose: () => void }) {
  const [detail, setDetail] = useState<CardActivityDetail>()
  const [error, setError] = useState('')

  useEffect(() => {
    let current = true
    getCardActivityDetail(accountId, id).then((result) => {
      if (current) setDetail(result)
    }).catch(() => {
      if (current) setError('Could not load this card transaction.')
    })
    return () => { current = false }
  }, [accountId, id])

  return (
    <Dialog title="Card transaction" onClose={onClose}>
      {error && <p className="error-text" role="alert">{error}</p>}
      {!detail && !error && <p role="status">Loading transaction…</p>}
      {detail && <>
        <div className="card-transaction-summary">
          <strong>{detail.transaction.merchant ?? label(detail.transaction.type)}</strong>
          <time dateTime={detail.transaction.created_at}>{dateLabel(detail.transaction.created_at)}</time>
          <span>{holdLabel(detail.transaction.type)} {holdMoney(detail.transaction.held_amount)}</span>
          <span>Settled {money(detail.transaction.settled_amount)}</span>
        </div>
        <ol className="card-events">
          {detail.events.map((event, index) => (
            <li key={index}>
              <div><strong>{eventLabels[event.type] ?? label(event.type)}</strong>
                <time dateTime={event.created_at}>{dateLabel(event.created_at)}</time></div>
              {event.decision && <p>{label(event.decision)}
                {event.decision_reason && event.decision_reason !== event.decision && ' · ' + label(event.decision_reason)}
              </p>}
              <div className="card-event-amounts">
                {event.requested_amount !== null && <span>Requested {money(event.requested_amount)}</span>}
                {event.held_amount !== null && event.held_amount !== 0n && <span>{isCredit(detail.transaction.type) ? 'Pending credit change' : 'Hold change'} {money(event.held_amount)}</span>}
                {event.settled_amount !== null && event.settled_amount !== 0n && <span>Settled change {money(event.settled_amount)}</span>}
              </div>
            </li>
          ))}
        </ol>
      </>}
    </Dialog>
  )
}

export function Cards({ accountId }: { accountId: bigint }) {
  const [overview, setOverview] = useState<CardOverview>()
  const [activity, setActivity] = useState<CardActivity[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [selected, setSelected] = useState<string>()
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')

  async function loadActivity(nextCursor?: string) {
    const result = await getCardActivity(accountId, nextCursor)
    setActivity((previous) => {
      if (!nextCursor) return result.items
      const seen = new Set(previous.map((item) => item.id))
      return [...previous, ...result.items.filter((item) => !seen.has(item.id))]
    })
    setCursor(result.next_cursor)
  }

  async function refresh() {
    let next
    try {
      next = await getCardOverview(accountId)
    } catch (failure) {
      if (!(failure instanceof BankError) || failure.status !== 404) throw failure
      next = { issuance_state: 'unconfigured', card: null, balance: null } as const
    }
    setOverview(next)
    if (next.card) await loadActivity()
  }

  async function run(action: () => Promise<void>, refreshOnError = false) {
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
    } catch (failure) {
      setError(failure instanceof BankError ? failure.message : 'Could not complete the sandbox request.')
      if (refreshOnError) {
        try {
          await refresh()
        } catch {
          // Keep the command error visible and let the user refresh when the service is available.
        }
      }
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => { void run(refresh) }, [accountId])

  async function issue() {
    const result = await issueCard(accountId)
    setOverview(result)
    if (result.card) await loadActivity()
  }

  async function changeStatus(paused: boolean) {
    const card = await setCardPaused(accountId, paused)
    setOverview((previous) => previous && { ...previous, card })
    if (card.status !== (paused ? 'paused' : 'active')) {
      setNotice('Column confirmed that the card is ' + card.status + '.')
    }
  }

  const card = overview?.card
  return (
    <div className="dashboard-column" aria-busy={busy}>
      {error && <div className="notice" role="alert">{error}
        <button disabled={busy} onClick={() => void run(refresh)}>Refresh</button>
      </div>}
      {notice && <div className="notice" role="status">{notice}</div>}
      {!overview && busy && <section className="panel loading" role="status">Loading sandbox cards…</section>}
      {overview?.issuance_state === 'unconfigured' && (
        <section className="panel cards-empty">
          <span className="card-icon"><CreditCard size={34} strokeWidth={1.5} /></span>
          <h2>Cards aren’t configured for this account</h2>
          <p>Run the Column sandbox setup for demo account {String(accountId)}.</p>
        </section>
      )}
      {overview?.balance && (
        <div className="dashboard-grid">
          <section className="panel sandbox-card">
            <div className="section-header"><h2>Virtual Visa debit</h2><CreditCard size={22} /></div>
            {card ? <>
              <p className="card-digits" aria-label={'Card ending in ' + card.last_four_digits}>•••• {card.last_four_digits}</p>
              <p className="card-status" role="status">{label(card.status)}</p>
              {card.status !== 'deactivated' && (
                <button className="button primary" disabled={busy} onClick={() => void run(() => changeStatus(card.status === 'active'), true)}>
                  {card.status === 'active' ? 'Pause card' : 'Resume card'}
                </button>
              )}
            </> : <>
              {overview.issuance_state === 'review' && <p>Issuance needs review. Check Column for a matching card.</p>}
              {overview.issuance_state === 'pending' && <p>Card issuance is awaiting confirmation.</p>}
              <button className="button primary" disabled={busy} onClick={() => void run(issue, true)}>
                {overview.issuance_state === 'available' ? 'Issue virtual card' : 'Check issuance'}
              </button>
            </>}
          </section>
          <section className="panel sandbox-balance">
            <div className="section-header"><h2>Column sandbox balance</h2></div>
            <dl>
              <div><dt>Available</dt><dd>{money(overview.balance.available_amount)}</dd></div>
              <div><dt>On hold</dt><dd>{money(overview.balance.holding_amount)}</dd></div>
            </dl>
          </section>
        </div>
      )}
      {card && (
        <section className="panel">
          <div className="section-header"><h2>Card activity</h2>
            <button className="icon-button card-refresh" disabled={busy} onClick={() => void run(refresh)}>Refresh</button>
          </div>
          {!activity.length && !busy && !error && <p className="empty-list">No card transactions yet.</p>}
          <ul className="activity-list card-activity">
            {activity.map((item) => (
              <li key={item.id}>
                <button onClick={() => setSelected(item.id)}>
                  <span className="row-icon"><CreditCard size={17} /></span>
                  <span className="row-name"><strong>{item.merchant ?? label(item.type)}</strong>
                    <small>{label(item.type)}</small></span>
                  <time className="row-date" dateTime={item.created_at}>{dateLabel(item.created_at)}</time>
                  <span className="row-value">
                    {item.held_amount !== 0n && <span className="pending">{holdLabel(item.type)} {holdMoney(item.held_amount)}</span>}
                    {item.settled_amount !== 0n && <strong>{money(item.settled_amount)} settled</strong>}
                  </span>
                  <ChevronRight className="row-chevron" size={15} />
                </button>
              </li>
            ))}
          </ul>
          {cursor && <button className="button primary card-more" disabled={busy}
            onClick={() => void run(() => loadActivity(cursor))}>Load more</button>}
        </section>
      )}
      {selected && <ActivityDetail key={selected} accountId={accountId} id={selected} onClose={() => setSelected(undefined)} />}
    </div>
  )
}
