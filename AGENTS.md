# Engineering instructions

## Start here

1. Check the current Git branch and working tree. Read [STATE.md](STATE.md) first for the verified baseline, open issues, and next step.
2. Read the documents relevant to the task using the table below. Follow their links to the implementation and tests before making a change.
3. Treat source code, applied migration definitions, and tests as the source of truth for implemented behavior. Verify claims against them and correct stale docs. If the sources disagree, investigate before relying on a financial guarantee.

## Four principles

1. **Financial correctness has absolute priority.** Preserve the documented [ledger invariants](LEDGER_INVARIANTS.md) even when doing so takes more code or effort. Verify amounts, balances, atomicity, idempotency, and concurrency wherever a change can affect money. Exercise failure paths and retries with PostgreSQL when those guarantees are at stake.
2. **Human readability means low cognitive complexity.** Prefer explicit control flow, meaningful names, idiomatic Java, and only the abstractions needed to preserve correctness or remove real duplication. Use as many lines as clarity requires. Keep transaction boundaries and financial rules visible. Explain why when the code alone cannot convey the reason.
3. **Persistent context belongs in the repository.** Leave enough verified context for another session to continue with a short prompt. Keep current state and next steps in `STATE.md`, design rationale in `DECISIONS.md`, and durable behavior in the relevant reference doc. Label plans, assumptions, and unverified guarantees explicitly.
4. **Self-maintainability applies to code and docs.** Look for architectural degradation, technical debt, and unnecessary complexity as you work. Address routine issues within the task's scope and surface larger improvements with their tradeoffs. Routine maintenance of the existing docs is authorized. Propose significant changes to engineering principles, documentation structure, or architecture and obtain approval before implementing them.

## Documentation and the definition of done

Update the affected docs in the same change whenever a trigger below applies. Keep each fact in its primary document and link to it from elsewhere. Replace stale text and remove obsolete material. Keep `STATE.md` focused on the current milestone and what remains.

| Document | Update when |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Engineering conventions change |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Responsibilities or execution flows change |
| [LEDGER_INVARIANTS.md](LEDGER_INVARIANTS.md) | Financial guarantees change |
| [DATABASE.md](DATABASE.md) | Schema or transaction strategy changes |
| [TESTING.md](TESTING.md) | Testing strategy changes |
| [DECISIONS.md](DECISIONS.md) | A significant technical decision is made or superseded |
| [STATE.md](STATE.md) | A meaningful development milestone is completed |
| [README.md](README.md) | Public functionality or setup changes |

A change is done when its implementation and relevant docs agree and the applicable [checks](TESTING.md) have run. Report actual results and remaining concerns, including any verification still pending, in compact plain text.
