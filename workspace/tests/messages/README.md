# Sample messages

Used by the model tests, the Java tests and the browser tests, and handy for trying the platform by
hand (Console: Instructions > Submit an instruction). With the built-in simulator, the words in a
name or text decide what the simulated external systems answer.

| File | Type | What it shows |
|---|---|---|
| `pain001-salaries.xml` | pain.001.001.09 | Five rand payments: two accepted, one with a zero amount (AM01), one creditor named BLOCKED (sanctions hit, SANC), one named REJECTME (rejected by the clearing, AC04) |
| `pain001-sepa.xml` | pain.001.001.09 | A SEPA batch and a SEPA Instant batch in euro: two normal payments, one name outside the SEPA character set (CH16), one instant payment, one instant payment above 100,000 euro (AM02) |
| `mt101-suppliers.fin` | MT101 | Two cross-border payments (USD, EUR) that leave as MT103 |
| `camt055-cancel-salary.xml` | camt.055.001.08 | The customer asks to cancel payment SAL-0002 of the salary file |
| `pain008-collections.xml` | pain.008.001.08 | Five SEPA Core direct debit collections under mandates MND-1001 to MND-1005; which pass depends on the mandates registered |
| `mt940-nostro.fin` | MT940 | A correspondent statement with a payment debit, a charge and an incoming credit |

Words the simulator reacts to:

| Where | Word | Effect |
|---|---|---|
| creditor name | `BLOCKED` | sanctions hit: rejected |
| creditor name | `REVIEWME` | possible sanctions match: held for review |
| creditor name | `FRAUDCHECK` | fraud alert: held for review |
| creditor name | `SLOWCHECK` | fraud result arrives two seconds later: the payment waits |
| creditor name | `NOLIQUIDITY` | no liquidity on the first two requests: the payment waits and retries |
| creditor name | `REJECTME` | rejected by the clearing with AC04 (for a direct debit: debtor name) |
| creditor name | `NOCANCEL` | a cancellation request is refused by the receiving side |
| remittance text | `COMPLIANCE` | compliance review: held |
| debtor account | starts with `000`, `999`, `666` | unknown, closed, blocked account: rejected (AC01, AC04, AC06) |
| amount and currency | 50,000 or more, not rand or euro | a supporting document is required |
| currency | not USD, EUR or GBP (from a rand account) | no rate quoted: repair |

Things to change when reusing a file: the message id (`MsgId`), or it is rejected as a duplicate (AM05);
for the SEPA files also the end-to-end ids, or the payments are duplicates within five days; and the
dates, since SEPA Credit Transfer and direct debits follow the calendar and cut-off times.

`pacs008-incoming.xml` is a file of four payments arriving from the clearing for our customers: one for an
open account (credited), one for a closed account (sent back, AC04), one whose account number is not a
valid IBAN (sent back, AC01), and one from a sender whose name contains `BLOCKED` (held for review). For a
German IBAN the simulated account system looks at the account number, the last ten characters.

`camt056-recall.xml` is the sender's bank asking for the four payments of `pacs008-incoming.xml` back, plus one
it never sent: the credited one waits for a decision, the held one is sent back, and the other two are
answered (already returned, unknown). Submit it after the incoming file.

`pacs003-collections-in.xml` is four direct debit collections against our customers: one that is debited, one by a
creditor the customer can block (block it first through the debit-blocks API to see SL01), one against an
account without funds (account number starting with `555`: AM04), and one against a closed account (AC04).

`mt103-incoming.fin` is four MT103 received from a correspondent: rand for a rand account (credited less our charge),
dollars for a rand account (credited at the quoted rate less the charge), one for a closed account (sent back as an
MT103 return), and one with charges OUR (credited in full, the charge noted as a claim).

`pacs008-incoming-inst.xml` is one SEPA Instant payment for a customer (local instrument INST): credited and confirmed with
a pacs.002. `pacs008-incoming-zar.xml` is two domestic rand payments (clearing system ZA-RTC): one credited, one for a
closed account that is sent back.

`pacs028-enquiry.xml` is a status enquiry from another bank about two payments: the instant payment of
`pacs008-incoming-inst.xml`, answered ACCP, and one that never arrived, answered RJCT with NOOR.

`swift-acks.fin` is what the SWIFT interface returns for two MT103s we sent: an ACK for the first, a NAK (error T13)
for the second, each followed by a copy of the message.

`camt026-rfi.xml` is a request for information from a receiving bank about a payment we sent; `camt027-claim.xml`
is a claim of non-receipt from a sending bank about a payment we received.

`pain013-requests.xml` is a request to pay from an energy company's bank to our customer: two requests, one accepted into
a payment and one refused in the tests.

`pain009-mandate.xml`, `pain010-amendment.xml` and `pain011-cancellation.xml` are the life of one mandate from the
creditor's side: registered, moved to another account, cancelled.

## negative/

Files the engine must refuse, one per way of being wrong (truncated XML, XML that is not ISO 20022, a type no
channel takes, an MT message without a mandatory field or with a malformed amount, JSON that is not a message,
plain text, a flat file with an unknown record). `expected.yml` says for each what the stored rejection must
carry; `NegativeSamplesTest` checks them all. Add a file and a line there for every new way a message was found
to be wrong.
