const maximumAmount = 9_223_372_036_854_775_807n
const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })

export function amountInput(cents: bigint): string {
  return `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}

export function money(cents: bigint): string {
  const magnitude = cents < 0n ? -cents : cents
  const fraction = String(magnitude % 100n).padStart(2, '0')
  const formatted = usd.formatToParts(magnitude / 100n)
    .map((part) => part.type === 'fraction' ? fraction : part.value)
    .join('')
  return cents < 0n ? '-' + formatted : formatted
}

export function parseAmount(input: string): bigint | null {
  const match = /^(\d{1,17})(?:\.(\d{1,2}))?$/.exec(input.trim())
  if (!match) return null
  const cents = BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0'))
  return cents > 0n && cents <= maximumAmount ? cents : null
}
