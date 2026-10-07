const { test, expect } = require('@playwright/test');
const path = require('path');
const fs = require('fs');

const SAMPLES = path.resolve(__dirname, '..', '..', 'workspace', 'tests', 'messages');
const password = (user) => process.env['ORVANTA_TEST_PASSWORD_' + user.toUpperCase()];

async function signIn(page, user, secret = password(user)) {
  await page.goto('/');
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(secret);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

/** Every page of a test is watched: an error in the browser console, such as a blocked script, fails the test. */
function watch(page, problems) {
  page.on('console', (message) => {
    // a refused sign-in (401) and a refused model request (422) are reported by the browser as failed requests; those are expected
    if (message.type() === 'error' && !message.text().includes('401') && !message.text().includes('422')) problems.push(message.text());
  });
  page.on('pageerror', (error) => problems.push(String(error)));
}

async function api(request, user, method, url, data) {
  const login = await request.post('/api/auth/login', { data: { username: user, password: password(user) } });
  const { token } = await login.json();
  const options = { headers: { Authorization: 'Bearer ' + token } };
  if (data !== undefined) options.data = data;
  const response = await request[method](url, options);
  return response.json();
}

function payment(msgId, creditorName) {
  return `<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.001.001.09"><CstmrCdtTrfInitn><GrpHdr><MsgId>${msgId}</MsgId>`
    + `<CreDtTm>2026-10-04T09:15:00</CreDtTm><NbOfTxs>1</NbOfTxs></GrpHdr><PmtInf><PmtInfId>${msgId}-1</PmtInfId>`
    + `<ReqdExctnDt><Dt>2026-10-05</Dt></ReqdExctnDt><Dbtr><Nm>Karoo Mining Supplies</Nm></Dbtr>`
    + `<DbtrAcct><Id><Othr><Id>4051122334</Id></Othr></Id></DbtrAcct><CdtTrfTxInf><PmtId><EndToEndId>${msgId}</EndToEndId></PmtId>`
    + `<Amt><InstdAmt Ccy="ZAR">900.00</InstdAmt></Amt><CdtrAgt><FinInstnId><BICFI>FIRNZAJJ</BICFI></FinInstnId></CdtrAgt>`
    + `<Cdtr><Nm>${creditorName}</Nm></Cdtr><CdtrAcct><Id><Othr><Id>62011223344</Id></Othr></Id></CdtrAcct>`
    + `</CdtTrfTxInf></PmtInf></CstmrCdtTrfInitn></Document>`;
}

test.describe.configure({ mode: 'serial' });

test('sign-in refuses a wrong password and shows each role only its own pages', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1', 'definitely-not-the-password');
  await expect(page.getByText('user name or password is wrong')).toBeVisible();

  await signIn(page, 'operator1');
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Review queue', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Studio', exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Users and roles', exact: true })).toHaveCount(0);

  // signing out ends the session: going back needs a new sign-in
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await page.goto('/#/transactions');
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  expect(problems).toEqual([]);
});

test('an operator submits a file and follows a payment to its history', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'Instructions', exact: true }).click();
  await page.getByRole('button', { name: 'Submit an instruction' }).click();
  await page.locator('input[type=file]').setInputFiles(path.join(SAMPLES, 'pain001-salaries.xml'));
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByText('Received 1 message(s).')).toBeVisible();

  await page.getByRole('link', { name: 'Transactions', exact: true }).click();
  // the five payments reach their outcomes; the list is refreshed until they have
  await expect(async () => {
    await page.getByRole('button', { name: 'Refresh' }).click();
    await expect(page.locator('tbody tr')).toHaveCount(5);
    await expect(page.locator('tbody tr', { hasText: 'ACCEPTED' })).toHaveCount(2);
    await expect(page.locator('tbody tr', { hasText: 'REJECTED BY EXTERNAL' })).toHaveCount(1);
  }).toPass({ timeout: 40_000 });
  await expect(page.locator('tbody tr', { hasText: 'SANC' })).toHaveCount(1);

  await page.locator('tbody tr', { hasText: 'SAL-0001' }).click();
  await expect(page.getByText('Thandiwe Mokoena / 62011223344 / FIRNZAJJ')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
  await expect(page.getByText('row ZA_DOMESTIC')).toBeVisible();
  // payment data is shown as text, never interpreted as markup
  expect(await page.locator('script').count()).toBe(2);   // the two scripts of the Console itself
  expect(problems).toEqual([]);
});

test('the dashboard shows where payments are, and the list is searched, sorted and paged', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  // the dashboard: totals, a chart of the last seven days with a text equivalent, and the payments by status
  await expect(page.locator('.tile', { hasText: 'Payments in total' }).locator('.n')).toHaveText('5');
  await expect(page.getByRole('img', { name: /Payments received per day over the last seven days/ })).toBeVisible();
  await page.locator('.hbar', { hasText: 'ACCEPTED' }).click();
  await expect(page.locator('.chip', { hasText: 'Status: ACCEPTED' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(2);

  // search narrows the list while typing, and the address keeps the search
  await page.locator('.chip', { hasText: 'Status: ACCEPTED' }).getByRole('button').click();
  await expect(page.locator('tbody tr')).toHaveCount(5);
  await page.getByLabel('Search payments').fill('thandiwe');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('Thandiwe Mokoena');
  expect(page.url()).toContain('q=thandiwe');
  await page.reload();
  await expect(page.getByLabel('Search payments')).toHaveValue('thandiwe');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByRole('button', { name: 'Remove the filter Text' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(5);

  // a filter that matches nothing says so
  await page.getByText('More filters').click();
  await page.getByLabel('Amount from').fill('999999999');
  await expect(page.getByText('No payments match these filters.')).toBeVisible();
  await page.getByRole('button', { name: 'Remove the filter At least' }).click();

  // sorting by a column, both ways
  await page.getByRole('button', { name: 'Amount' }).click();
  await expect(page.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute('aria-sort', 'descending');
  await expect(page.locator('tbody tr').first()).toContainText('21,300.50');
  await page.getByRole('button', { name: 'Amount' }).click();
  await expect(page.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute('aria-sort', 'ascending');
  await expect(page.locator('tbody tr').first()).toContainText('0.00');

  // pages
  await page.goto('/#/transactions?limit=2');
  await expect(page.locator('.pager .count')).toHaveText('1–2 of 5');
  await expect(page.getByRole('button', { name: 'Previous' })).toBeDisabled();
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('.pager .count')).toHaveText('3–4 of 5');
  await page.getByRole('button', { name: 'Next' }).click();
  await expect(page.locator('.pager .count')).toHaveText('5–5 of 5');
  await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();

  // a payment opens from the keyboard and shows how far it got
  await page.goto('/#/transactions?q=SAL-0005');
  await page.locator('tbody tr').first().focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('list', { name: 'Progress of the payment' })).toContainText('Answer (stopped here)');
  await expect(page.getByRole('heading', { name: 'Route and settlement' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Where you are' }).getByRole('link', { name: 'Transactions' }).click();
  await expect(page.locator('.pager')).toBeVisible();
  expect(problems).toEqual([]);
});

test('a designer changes a rule in Studio and a checker approves it', async ({ browser }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await expect(maker.getByText('(version 1)')).toBeVisible();
  await maker.getByRole('link', { name: 'payments.rules.TransactionValidation' }).click();
  const editor = maker.locator('textarea').first();
  await expect(editor).toHaveValue(/AMOUNT_WITHIN_LIMIT/);
  const original = await editor.inputValue();

  // a broken edit names the rule that is wrong
  await editor.fill(original.replace('txn.amount <= 999999999.99', 'txn.amount <='));
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('1 problem(s). Nothing was changed.')).toBeVisible();
  await expect(maker.getByText('rule AMOUNT_WITHIN_LIMIT, assert')).toBeVisible();

  await editor.fill(original.replace('txn.amount <= 999999999.99', 'txn.amount <= 750000'));
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();
  await maker.getByRole('button', { name: 'Generated Java' }).click();
  await expect(maker.getByText('extends Elements.RuleSet')).toBeVisible();
  await maker.getByRole('button', { name: 'Model', exact: true }).click();

  maker.once('dialog', (dialog) => dialog.accept('Lower the single payment limit'));
  await maker.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(maker.getByText('was created. It is deployed when another user approves it.')).toBeVisible();
  // the maker sees the request but cannot decide it
  await maker.getByRole('link', { name: /^ORVAPR/ }).click();
  await expect(maker.getByText('This is your own request.')).toBeVisible();
  await expect(maker.getByRole('button', { name: 'Approve and apply' })).toHaveCount(0);

  const checker = await (await browser.newContext()).newPage();
  watch(checker, problems);
  await signIn(checker, 'checker1');
  await checker.getByRole('link', { name: 'Approvals', exact: true }).click();
  await checker.locator('tbody tr', { hasText: 'RuleSet payments.rules.TransactionValidation' }).first().click();
  await expect(checker.getByRole('heading', { name: 'Currently deployed' })).toBeVisible();
  await expect(checker.getByText('txn.amount <= 750000')).toBeVisible();
  await checker.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(checker.locator('.badge', { hasText: 'APPROVED' }).first()).toBeVisible();

  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await expect(maker.getByText('(version 2)')).toBeVisible();
  expect(problems).toEqual([]);
});

test('a flow is built in the designer without typing model text and runs after approval', async ({ browser, request }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await maker.getByRole('button', { name: 'New flow' }).click();
  await expect(maker.getByText('Start: payments.flows.MyNewFlow')).toBeVisible();
  const form = maker.locator('.fd-form');
  await form.locator('input[type=text]').first().fill('payments.flows.SmallPaymentCheck');
  await expect(maker.getByText('Start: payments.flows.SmallPaymentCheck')).toBeVisible();

  // an End step from the palette, filled in through the form
  await maker.locator('[data-tool=end]').click();
  await expect(maker.locator('[data-step=end1] > .fd-head')).toHaveAttribute('aria-pressed', 'true');
  await form.locator('textarea').first().fill('txn.amount > 100');
  await form.locator('input[type=text]').nth(1).fill('AM02');
  await form.locator('input[type=text]').nth(2).fill('Too large for this flow');

  // a Set step is added after it, then dragged in front of it
  await maker.locator('[data-tool=set]').click();
  await form.getByRole('button', { name: 'Add line' }).click();
  await form.getByLabel('field').fill('txn.checked');
  await form.getByLabel('expression', { exact: true }).fill("'yes'");
  await expect(maker.locator('.fd-canvas .fd-step').first()).toHaveAttribute('data-step', 'end1');
  await maker.locator('[data-step=set1]').dragTo(maker.locator('.fd-canvas .fd-drop').first());
  await expect(maker.locator('.fd-canvas .fd-step').first()).toHaveAttribute('data-step', 'set1');

  // a step that is not filled in is reported with its place
  await maker.locator('[data-tool=rules]').click();
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('1 problem(s). Nothing was changed.')).toBeVisible();
  await expect(maker.getByText('step rules1', { exact: true })).toBeVisible();
  await form.getByRole('button', { name: 'Delete' }).click();
  await expect(maker.locator('[data-step=rules1]')).toHaveCount(0);

  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();
  await maker.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(maker.locator('textarea').first()).toHaveValue(/name: payments\.flows\.SmallPaymentCheck[\s\S]*id: set1[\s\S]*id: end1/);

  maker.once('dialog', (dialog) => dialog.accept('A flow built in the designer'));
  await maker.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(maker.getByText('was created. It is deployed when another user approves it.')).toBeVisible();

  const checker = await (await browser.newContext()).newPage();
  watch(checker, problems);
  await signIn(checker, 'checker1');
  await checker.getByRole('link', { name: 'Approvals', exact: true }).click();
  await checker.locator('tbody tr', { hasText: 'Flow payments.flows.SmallPaymentCheck' }).first().click();
  await checker.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(checker.locator('.badge', { hasText: 'APPROVED' }).first()).toBeVisible();

  // the deployed flow does what was drawn
  const run = (amount) => api(request, 'maker1', 'post', '/api/studio/run', { target: 'payments.flows.SmallPaymentCheck', scope: { txn: { amount } } });
  const large = await run(500);
  expect(large.result.status).toBe('REJECTED');
  expect(large.result.code).toBe('AM02');
  expect(large.scope.txn.checked).toBe('yes');
  expect((await run(50)).result.status).toBe('ROUTED');

  // an existing flow opens in the designer with its steps
  await maker.getByRole('link', { name: 'payments.flows.DuplicateCheck' }).click();
  await maker.getByRole('button', { name: 'Design' }).click();
  await expect(maker.locator('.fd-canvas .fd-step')).toHaveCount(6);
  expect(problems).toEqual([]);
});

test('a deployment is rolled back from the Console after a second person approves', async ({ browser }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');

  // a model that is still used cannot be removed; what uses it is named
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await maker.getByRole('link', { name: 'payments.rules.TransactionValidation' }).click();
  maker.once('dialog', (dialog) => dialog.accept('try to remove it'));
  await maker.getByRole('button', { name: 'Request removal' }).click();
  await expect(maker.getByText('The model is still used. Nothing was changed.')).toBeVisible();
  await expect(maker.getByRole('cell', { name: 'payments.flows.TransactionProcessing' })).toBeVisible();

  // version 2 changed a rule; version 3 added the flow built in the designer
  await maker.getByRole('link', { name: 'Deployments', exact: true }).click();
  await expect(maker.locator('tbody tr', { hasText: 'ORVDEP000003' }).locator('.badge', { hasText: 'ACTIVE' })).toBeVisible();
  await maker.locator('tbody tr', { hasText: 'ORVDEP000002' }).click();
  await expect(maker.getByRole('heading', { name: 'What this version changed' })).toBeVisible();
  const changed = maker.locator('details', { hasText: 'payments.rules.TransactionValidation' });
  await changed.locator('summary').click();
  await expect(changed.getByText('-     assert: txn.amount <= 999999999.99')).toBeVisible();
  await expect(changed.getByText('+     assert: txn.amount <= 750000')).toBeVisible();
  await expect(maker.getByRole('heading', { name: 'What going back to this version would change now' })).toBeVisible();
  await expect(maker.locator('details', { hasText: 'payments.flows.SmallPaymentCheck' }).locator('.badge')).toHaveText('removed');

  maker.once('dialog', (dialog) => dialog.accept('The new flow is not wanted'));
  await maker.getByRole('button', { name: 'Request rollback to this version' }).click();
  await expect(maker.getByText('was created. It is applied when another user approves it.')).toBeVisible();
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await expect(maker.getByText('(version 3)')).toBeVisible();

  const checker = await (await browser.newContext()).newPage();
  watch(checker, problems);
  await signIn(checker, 'checker1');
  await checker.getByRole('link', { name: 'Approvals', exact: true }).click();
  await checker.locator('tbody tr', { hasText: 'Rollback to ORVDEP000002' }).first().click();
  await expect(checker.getByRole('heading', { name: 'What approving changes' })).toBeVisible();
  await expect(checker.locator('details', { hasText: 'payments.flows.SmallPaymentCheck' })).toBeVisible();
  await checker.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(checker.locator('.badge', { hasText: 'APPROVED' }).first()).toBeVisible();

  // the earlier models are active as version 4, and the flow is gone
  await maker.getByRole('link', { name: 'Deployments', exact: true }).click();
  await expect(maker.locator('tbody tr', { hasText: 'ORVDEP000004' }).locator('.badge', { hasText: 'ACTIVE' })).toBeVisible();
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await expect(maker.getByText('(version 4)')).toBeVisible();
  await expect(maker.getByRole('link', { name: 'payments.flows.SmallPaymentCheck' })).toHaveCount(0);
  expect(problems).toEqual([]);
});

test('rules are edited in a form and a decision table in a grid', async ({ browser, request }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();

  // a new rule set: two rules filled in through the form, the second dragged in front of the first
  await maker.getByRole('button', { name: 'New rule set' }).click();
  await maker.getByLabel('Model name').fill('payments.rules.DesignerRules');
  await maker.getByRole('button', { name: 'Add rule' }).click();
  await maker.getByLabel('Id (unique in the rule set)').fill('POSITIVE');
  await maker.getByLabel('Must be true (expression)').fill('txn.amount > 0');
  await maker.getByLabel('Reason code reported when it fails (empty: the id)').fill('AM01');
  await maker.getByLabel('Message reported when it fails').fill('Amount must be greater than zero');
  await maker.getByRole('button', { name: 'Add rule' }).click();
  await maker.getByLabel('Id (unique in the rule set)').fill('HAS_CURRENCY');
  await maker.getByLabel('Must be true (expression)').fill('exists(txn.currency)');
  await maker.getByLabel('Severity').selectOption('warning');
  await expect(maker.locator('.fd-canvas .fd-step').first()).toHaveAttribute('data-rule', 'POSITIVE');
  await maker.locator('[data-rule=HAS_CURRENCY]').dragTo(maker.locator('.fd-canvas .fd-drop').first());
  await expect(maker.locator('.fd-canvas .fd-step').first()).toHaveAttribute('data-rule', 'HAS_CURRENCY');
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();

  maker.once('dialog', (dialog) => dialog.accept('Rules made in the designer'));
  await maker.getByRole('button', { name: 'Submit for approval' }).click();
  const approvalId = await maker.getByRole('link', { name: /^ORVAPR/ }).textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + approvalId + '/approve', { comment: 'checked' });
  expect(decided.status).toBe('APPROVED');
  const run = await api(request, 'maker1', 'post', '/api/studio/run', { target: 'payments.rules.DesignerRules', scope: { txn: { amount: 0 } } });
  expect(JSON.stringify(run.result)).toContain('AM01');
  expect(JSON.stringify(run.result)).toContain('HAS_CURRENCY');

  // the routing table opens as a grid: one line per row, one column per field the rows set
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await maker.getByRole('link', { name: 'payments.routing.OutboundRouting' }).click();
  await maker.getByRole('button', { name: 'Design' }).click();
  await expect(maker.locator('.dt tbody tr')).toHaveCount(4);
  await expect(maker.getByLabel('txn.route.scheme of row ZA_DOMESTIC')).toHaveValue("'ZA-RTC'");
  await maker.getByRole('button', { name: 'Add row' }).click();
  await maker.getByLabel('Id of row 5').fill('USD_WIRE');
  await maker.getByLabel('When of row ROW_1').fill("txn.currency == 'USD'");
  await maker.getByLabel('txn.route.channel of row ROW_1').fill("'channels.SwiftMtOutbound'");
  await maker.getByLabel('txn.route.scheme of row ROW_1').fill("'SWIFT'");
  await maker.getByRole('button', { name: 'Move row ROW_1 up' }).click();
  for (let i = 0; i < 3; i++) await maker.getByRole('button', { name: 'Move row USD_WIRE up' }).click();
  await expect(maker.locator('.dt tbody tr').first()).toHaveAttribute('data-row', 'USD_WIRE');
  // a row dropped on another goes in front of it
  await maker.locator('tr[data-row=ZA_DOMESTIC] .dt-handle').dragTo(maker.locator('tr[data-row=USD_WIRE]'));
  await expect(maker.locator('.dt tbody tr').first()).toHaveAttribute('data-row', 'ZA_DOMESTIC');
  await expect(maker.locator('.dt tbody tr').nth(1)).toHaveAttribute('data-row', 'USD_WIRE');

  // a row without a condition in the middle is pointed out before anything is validated
  await maker.getByLabel('When of row USD_WIRE').fill('');
  await expect(maker.getByText('Row USD_WIRE has no condition, so it matches everything: it must be the last row.')).toBeVisible();
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('1 problem(s). Nothing was changed.')).toBeVisible();
  await maker.getByLabel('When of row USD_WIRE').fill("txn.currency == 'USD'");
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();
  await maker.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(maker.locator('textarea').first()).toHaveValue(/id: ZA_DOMESTIC[\s\S]*id: USD_WIRE[\s\S]*txn\.route\.scheme: .*SWIFT[\s\S]*id: SEPA_INSTANT/);
  expect(problems).toEqual([]);
});

const TEST_XSD = `<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns="urn:iso:std:iso:20022:tech:xsd:camt.055.001.08"
  targetNamespace="urn:iso:std:iso:20022:tech:xsd:camt.055.001.08" elementFormDefault="qualified">
  <xs:element name="Document"><xs:complexType><xs:sequence>
    <xs:element name="CstmrPmtCxlReq"><xs:complexType><xs:sequence>
      <xs:element name="Assgnmt"><xs:complexType><xs:sequence>
        <xs:element name="Id"><xs:simpleType><xs:restriction base="xs:string"><xs:maxLength value="35"/></xs:restriction></xs:simpleType></xs:element>
        <xs:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/>
      </xs:sequence></xs:complexType></xs:element>
      <xs:any processContents="skip" minOccurs="0" maxOccurs="unbounded"/>
    </xs:sequence></xs:complexType></xs:element>
  </xs:sequence></xs:complexType></xs:element>
</xs:schema>`;

test('a mapping is built in the designer with a loop and runs after approval', async ({ browser, request }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await maker.getByRole('button', { name: 'New mapping' }).click();
  const form = maker.locator('.fd-form');
  await form.getByLabel('Name (dotted, for example payments.maps.MyMapping)').fill('payments.maps.DesignerSummary');

  // a field, then a loop that adds one record per item, with a rule inside it
  await maker.locator('[data-tool=set]').click();
  await form.getByLabel('Field to write (a path such as debtor.name)').fill('reference');
  await form.getByLabel('Value (expression)').fill('txn.endToEndId');
  await maker.locator('[data-tool=forEach]').click();
  await form.getByLabel('List to go through (expression)').fill('txn.items');
  await form.getByLabel('Name of the current item').fill('line');
  await form.getByLabel('Add a record per item to this list (empty: the rules inside write to the current record)').fill('lines');
  await form.getByLabel('Only the items for which (expression; empty: all)').fill('line.amount > 0');
  await maker.locator('[data-tool=set]').click();          // the loop is chosen, so the rule goes inside it
  await form.getByLabel('Field to write (a path such as debtor.name)').fill('amount');
  await form.getByLabel('Value (expression)').fill('line.amount');
  await expect(maker.locator('[data-rule="forEach:txn.items"] [data-rule="set:amount"]')).toBeVisible();

  // the first rule is dragged below the loop
  await expect(maker.locator('.fd-canvas > .fd-list > .fd-step').first()).toHaveAttribute('data-rule', 'set:reference');
  await maker.locator('[data-rule="set:reference"]').dragTo(maker.locator('.fd-canvas > .fd-list > .fd-drop').last());
  await expect(maker.locator('.fd-canvas > .fd-list > .fd-step').last()).toHaveAttribute('data-rule', 'set:reference');

  // an unfinished rule is reported with its place, then removed
  await maker.locator('[data-tool=let]').click();
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('problem(s). Nothing was changed.')).toBeVisible();
  await form.getByRole('button', { name: 'Delete' }).click();
  await maker.getByRole('button', { name: 'Validate' }).click();
  await expect(maker.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();

  maker.once('dialog', (dialog) => dialog.accept('A mapping built in the designer'));
  await maker.getByRole('button', { name: 'Submit for approval' }).click();
  const approvalId = await maker.getByRole('link', { name: /^ORVAPR/ }).textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + approvalId + '/approve', { comment: 'checked' });
  expect(decided.status).toBe('APPROVED');
  const run = await api(request, 'maker1', 'post', '/api/studio/run', { target: 'payments.maps.DesignerSummary',
    scope: { txn: { endToEndId: 'E2E-7', items: [{ amount: 5 }, { amount: 0 }, { amount: 7 }] } } });
  expect(JSON.stringify(run.result)).toContain('"reference":"E2E-7"');
  expect(JSON.stringify(run.result)).toContain('"lines":[{"amount":5},{"amount":7}]');

  // an existing mapping opens with its nested loops
  await maker.getByRole('link', { name: 'Studio', exact: true }).click();
  await maker.getByRole('link', { name: 'payments.inbound.Pain001ToCanonical' }).click();
  await maker.getByRole('button', { name: 'Design' }).click();
  await expect(maker.locator('[data-rule="forEach:init.PmtInf"] [data-rule="forEach:pi.CdtTrfTxInf"] [data-rule="set:endToEndId"]')).toBeVisible();
  expect(problems).toEqual([]);
});

test('a schema is imported and a message is checked before it is sent in', async ({ browser }) => {
  const problems = [];
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  await maker.getByRole('link', { name: 'Message checks', exact: true }).click();
  await expect(maker.getByText('No schema is imported, so no ISO 20022 message is checked against one.')).toBeVisible();
  await expect(maker.getByRole('link', { name: 'specs.swift.MT103' })).toBeVisible();

  // an MT message with a wrong amount: the field, the occurrence and the expected format are named
  const mt101 = fs.readFileSync(path.join(SAMPLES, 'mt101-suppliers.fin'), 'utf8');
  await maker.getByLabel('Message to check').fill(mt101.replace(':32B:EUR8300,40', ':32B:EUR8300.40'));
  await maker.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(maker.getByText('1 problem(s). A message like this is rejected when it is received.')).toBeVisible();
  await expect(maker.locator('.problems li')).toContainText(':32B:');
  await expect(maker.locator('.problems li')).toContainText('3!a15d');
  await maker.getByLabel('Message file').setInputFiles(path.join(SAMPLES, 'mt101-suppliers.fin'));
  await maker.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(maker.getByText('No problems.')).toBeVisible();

  // an ISO 20022 type without a schema is not checked, and the page says so
  const cancel = fs.readFileSync(path.join(SAMPLES, 'camt055-cancel-salary.xml'), 'utf8');
  await maker.getByLabel('Message to check').fill(cancel);
  await maker.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(maker.getByText('Nothing is installed for camt.055.001.08, so messages of this type are not checked.')).toBeVisible();

  // the schema is imported from a file, and a second person approves it
  maker.once('dialog', (dialog) => dialog.accept('Schema of the cancellation request'));
  await maker.getByLabel('Schema file').setInputFiles({ name: 'camt.055.001.08.xsd', mimeType: 'application/xml', buffer: Buffer.from(TEST_XSD) });
  await expect(maker.getByText('waits for approval by another user.')).toBeVisible();
  const checker = await (await browser.newContext()).newPage();
  watch(checker, problems);
  await signIn(checker, 'checker1');
  await checker.getByRole('link', { name: 'Approvals', exact: true }).click();
  await checker.locator('tbody tr', { hasText: 'Import schema camt.055.001.08' }).first().click();
  await expect(checker.getByText('camt.055.001.08.xsd')).toBeVisible();
  await checker.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(checker.locator('.badge', { hasText: 'APPROVED' }).first()).toBeVisible();

  await maker.getByRole('link', { name: 'Message checks', exact: true }).click();
  await expect(maker.getByRole('cell', { name: 'camt.055.001.08', exact: true })).toBeVisible();
  await maker.getByLabel('Message to check').fill(cancel);
  await maker.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(maker.getByText('No problems.')).toBeVisible();
  await maker.getByLabel('Message to check').fill(cancel.replace(/<Assgnmt>\s*<Id>[^<]*<\/Id>/, '<Assgnmt><Id>AN-ASSIGNMENT-ID-THAT-IS-FAR-LONGER-THAN-ALLOWED</Id>'));
  await maker.getByRole('button', { name: 'Check', exact: true }).click();
  await expect(maker.locator('.problems li')).toContainText('Document/CstmrPmtCxlReq/Assgnmt/Id');
  expect(problems).toEqual([]);
});

test('a role is created and a user is limited to one channel', async ({ browser, request }) => {
  const problems = [];
  const deskSecret = 'desk-User-' + Date.now() + '-pass';   // made up for this run; the user exists only in the test server
  const maker = await (await browser.newContext()).newPage();
  watch(maker, problems);
  await signIn(maker, 'maker1');
  const approveLatest = async () => {
    const id = await maker.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
    const decided = await api(request, 'checker1', 'post', '/api/approvals/' + id + '/approve', { comment: 'checked' });
    expect(decided.status).toBe('APPROVED');
  };

  // a role of one's own: a name and a set of permissions
  await maker.getByRole('link', { name: 'Users and roles', exact: true }).click();
  await expect(maker.locator('tbody tr', { hasText: 'OPERATOR' }).getByText('built in')).toBeVisible();
  await maker.getByLabel('Role name').fill('STATUS_DESK');
  await maker.getByLabel('Role description').fill('Answers questions about payments');
  await maker.getByLabel('payments.view', { exact: true }).check();
  await maker.getByRole('button', { name: 'Submit role for approval' }).click();
  await expect(maker.getByText('was created and waits for approval.')).toBeVisible();
  await approveLatest();

  // a user with that role, limited to what arrives on one channel
  await maker.getByRole('link', { name: 'Users and roles', exact: true }).click();
  await expect(maker.getByRole('cell', { name: 'STATUS_DESK', exact: true })).toBeVisible();
  await maker.getByLabel('User name').fill('deskuser');
  await maker.getByLabel('Display name').fill('Status Desk');
  await maker.getByLabel('Password').fill(deskSecret);
  await maker.getByLabel('STATUS_DESK', { exact: true }).check();
  await maker.getByLabel('channels.CorporateIsoInbound', { exact: true }).check();
  await maker.getByRole('button', { name: 'Submit for approval', exact: true }).click();
  await expect(maker.getByText('was created and waits for approval.')).toBeVisible();
  await approveLatest();
  await maker.getByRole('link', { name: 'Users and roles', exact: true }).click();
  await expect(maker.locator('tbody tr', { hasText: 'deskuser' })).toContainText('channels CorporateIsoInbound');

  // the limited user: told about the limit, offered only what the limit allows
  const desk = await (await browser.newContext()).newPage();
  watch(desk, problems);
  await signIn(desk, 'deskuser', deskSecret);
  await expect(desk.getByText('Your access is limited to channels CorporateIsoInbound.')).toBeVisible();
  await expect(desk.getByText('Limited access')).toBeVisible();
  for (const hidden of ['Outbound files', 'Statements', 'Failed events', 'Studio', 'Users and roles']) {
    await expect(desk.getByRole('link', { name: hidden, exact: true })).toHaveCount(0);
  }
  await desk.getByRole('link', { name: 'Transactions', exact: true }).click();
  await expect(desk.locator('tbody tr').first()).toBeVisible();
  const all = await api(request, 'operator1', 'get', '/api/transactions?limit=1000');
  const mine = await desk.evaluate(async () => (await api('GET', '/api/transactions?limit=1000')).items);
  expect(mine.length).toBeGreaterThan(0);
  expect(mine.every((t) => t.channelIn === 'channels.CorporateIsoInbound')).toBe(true);
  expect(all.items.length).toBeGreaterThanOrEqual(mine.length);
  expect(problems).toEqual([]);
});

test('a held payment is released from the review queue with a second person', async ({ browser, request }) => {
  const problems = [];
  await api(request, 'operator1', 'post', '/api/inbound?fileName=hold.xml', payment('UI-HOLD-1', 'FRAUDCHECK Supplies'));

  const operator = await (await browser.newContext()).newPage();
  watch(operator, problems);
  await signIn(operator, 'operator1');
  await operator.getByRole('link', { name: 'Review queue', exact: true }).click();
  await expect(async () => {
    await operator.getByRole('button', { name: 'Refresh' }).click();
    await expect(operator.locator('tbody tr', { hasText: 'FRAUDCHECK Supplies' })).toHaveCount(1);
  }).toPass({ timeout: 30_000 });
  await operator.locator('tbody tr', { hasText: 'FRAUDCHECK Supplies' }).click();
  await expect(operator.locator('.note', { hasText: 'FRAUD: Fraud alert; review before releasing' })).toBeVisible();
  await expect(operator.getByText('REVIEW, score 87')).toBeVisible();
  operator.once('dialog', (dialog) => dialog.accept('Known supplier, confirmed by phone'));
  await operator.getByRole('button', { name: 'Request release' }).click();
  await expect(operator.getByText('It takes effect when another user approves it.')).toBeVisible();

  const checker = await (await browser.newContext()).newPage();
  watch(checker, problems);
  await signIn(checker, 'checker1');
  await checker.getByRole('link', { name: 'Approvals', exact: true }).click();
  await checker.locator('tbody tr', { hasText: 'held for FRAUD' }).first().click();
  await checker.getByRole('button', { name: 'Approve and apply' }).click();
  await expect(checker.locator('.badge', { hasText: 'APPROVED' }).first()).toBeVisible();

  await expect(async () => {
    await operator.reload();
    await expect(operator.locator('.badge', { hasText: 'ACCEPTED' }).first()).toBeVisible();
  }).toPass({ timeout: 30_000 });
  await expect(operator.getByText('Holds released')).toBeVisible();
  expect(problems).toEqual([]);
});

test('instructions, outbound files, approvals and the security log are searched and filtered like payments', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'maker1');

  await page.getByRole('link', { name: 'Instructions', exact: true }).click();
  await page.getByLabel('Search instructions').fill('salary-2026-10-001');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('SALARY-2026-10-001');
  await expect(page.locator('.chip', { hasText: 'Text: salary-2026-10-001' })).toBeVisible();
  await page.getByLabel('Search instructions').fill('no such file anywhere');
  await expect(page.getByText('No instructions match these filters.')).toBeVisible();

  await page.getByRole('link', { name: 'Outbound files', exact: true }).click();
  await page.getByLabel('Search outbound files').fill('pacs.008.001.08');
  await expect(page.locator('tbody tr').first()).toContainText('pacs.008.001.08');
  await page.getByRole('button', { name: 'Transactions' }).click();
  await expect(page.getByRole('columnheader', { name: /Transactions/ })).toHaveAttribute('aria-sort', 'descending');

  await page.getByRole('link', { name: 'Approvals', exact: true }).click();
  await page.getByLabel('All kinds').selectOption('SCHEMA_IMPORT');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('Import schema camt.055.001.08');
  await page.getByRole('button', { name: 'Remove the filter Kind' }).click();
  await page.getByLabel('Search approvals').fill('rollback');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr')).toContainText('Rollback to ORVDEP000002');
  expect(page.url()).toContain('#/approvals?q=rollback');
  await page.locator('tbody tr').first().click();
  await expect(page.getByRole('heading', { name: 'Rollback', exact: true })).toBeVisible();

  await page.getByRole('link', { name: 'Security log', exact: true }).click();
  await page.getByLabel('Search the security log').fill('checker1');
  await expect(page.locator('tbody tr').first()).toContainText('checker1');
  await expect(page.locator('tbody tr', { hasText: 'operator1' })).toHaveCount(0);
  expect(problems).toEqual([]);
});

test('the security log shows sign-ins and refusals to those allowed to see it', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'maker1');
  await page.getByRole('link', { name: 'Security log', exact: true }).click();
  await expect(page.locator('tbody tr', { hasText: 'LOGIN OK' }).first()).toBeVisible();
  await expect(page.locator('tbody tr', { hasText: 'LOGIN FAILED' }).first()).toBeVisible();
  await page.getByLabel('All events').selectOption('LOGIN_FAILED');
  await expect(page.locator('.chip', { hasText: 'Event: LOGIN FAILED' })).toBeVisible();
  await expect(page.locator('tbody tr', { hasText: 'LOGIN OK' })).toHaveCount(0);
  // no password ever appears in the log
  expect(await page.locator('table').innerText()).not.toContain('definitely-not-the-password');
  expect(problems).toEqual([]);
});

test('the dashboard and the lists follow what happens without being reloaded', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await expect(page.locator('#live')).toHaveText('Live');
  const total = page.locator('.tile', { hasText: 'Payments in total' }).locator('.n');
  const before = Number((await total.textContent()).replace(/\D/g, ''));

  const login = await request.post('/api/auth/login', { data: { username: 'operator1', password: password('operator1') } });
  const { token } = await login.json();
  const submit = (msgId) => request.post('/api/inbound?fileName=' + msgId + '.xml', { headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'text/plain' }, data: payment(msgId, 'Live Update Supplier') });

  // a payment arrives while the dashboard is open: the figure changes by itself, sooner than any timer would have asked
  expect((await submit('LIVE-DASH-1')).ok()).toBe(true);
  await expect(total).toHaveText(String(before + 1), { timeout: 8000 });

  // the same in a list, without touching Refresh, and the search that was typed stays
  await page.getByRole('link', { name: 'Transactions', exact: true }).click();
  await page.getByLabel('Search payments').fill('live update supplier');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  expect((await submit('LIVE-LIST-2')).ok()).toBe(true);
  await expect(page.locator('tbody tr')).toHaveCount(2, { timeout: 8000 });
  await expect(page.getByLabel('Search payments')).toHaveValue('live update supplier');
  await expect(page.locator('tbody tr', { hasText: 'ACCEPTED' })).toHaveCount(2, { timeout: 20000 });

  // signing out ends the connection; signing in again brings it back
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await signIn(page, 'operator1');
  await expect(page.locator('#live')).toHaveText('Live');
  expect(problems).toEqual([]);
});

test('an incoming payment is credited, then refunded with a second person', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'Instructions', exact: true }).click();
  await page.getByRole('button', { name: 'Submit an instruction' }).click();
  await page.locator('input[type=file]').setInputFiles(path.join(SAMPLES, 'pacs008-incoming.xml'));
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByText('Received 1 message(s).')).toBeVisible();

  // four payments arrive: one is credited, two go back with a reason, one waits for a person
  await page.getByRole('link', { name: 'Transactions', exact: true }).click();
  await page.getByLabel('Search payments').fill('CLEARING-IN-2026-10-06-001');
  await page.getByLabel('Search payments').fill('RENT-OCT');
  await expect(page.locator('tbody tr', { hasText: 'CREDITED' })).toHaveCount(1, { timeout: 20000 });
  await expect(page.locator('tbody tr')).toContainText('from Camille Laurent');
  await page.getByLabel('Search payments').fill('INV-2291');
  await expect(page.locator('tbody tr', { hasText: 'RETURNED' })).toHaveCount(1, { timeout: 20000 });
  await expect(page.locator('tbody tr')).toContainText('AC04');

  await page.getByLabel('Search payments').fill('RENT-OCT');
  await page.locator('tbody tr', { hasText: 'CREDITED' }).click();
  await expect(page.getByText('Incoming credit transfer')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Progress of the payment' })).toContainText('Credited (done)');
  await expect(page.getByRole('button', { name: 'Request cancellation' })).toHaveCount(0);

  page.once('dialog', (dialog) => dialog.accept('Paid twice by mistake'));
  await page.getByRole('button', { name: 'Request refund' }).click();
  await expect(page.getByText('was created. It takes effect when another user approves it.')).toBeVisible();
  const approvalId = await page.getByRole('link', { name: /^ORVAPR/ }).textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + approvalId + '/approve', { comment: 'checked with the customer' });
  expect(decided.status).toBe('APPROVED');

  // the credit is reversed and the payment sent back
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('list', { name: 'Progress of the payment' })).toContainText('Sent back (done)', { timeout: 2000 });
  }).toPass({ timeout: 30000 });
  await expect(page.getByText('sent back: CUST Paid twice by mistake')).toBeVisible();
  await expect(page.getByText('POSTING REVERSED')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Request refund' })).toHaveCount(0);
  expect(problems).toEqual([]);
});

test("a recall of the sender's bank is decided from the review queue", async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  // the sample files with ids of their own, so that this test does not meet the payments of the one before
  const own = (file) => Buffer.from(fs.readFileSync(path.join(SAMPLES, file), 'utf8').replaceAll('CLEARING-IN-2026-10-06-001', 'CLEARING-IN-R9')
    .replaceAll('RECALL-2026-10-07-001', 'RECALL-R9').replaceAll('SNDTX-', 'SNDR9-').replaceAll('RENT-OCT', 'RENT-R9').replaceAll('INV-2291', 'INV-R9')
    .replaceAll('GIFT-1', 'GIFT-R9').replaceAll('DONATION-7', 'DONATION-R9'));
  const submit = async (name, file) => {
    await page.getByRole('link', { name: 'Instructions', exact: true }).click();
    await page.getByRole('button', { name: 'Submit an instruction' }).click();
    await page.locator('input[type=file]').setInputFiles({ name, mimeType: 'application/xml', buffer: own(file) });
    await page.getByRole('button', { name: 'Submit', exact: true }).click();
    await expect(page.getByText('Received 1 message(s).')).toBeVisible();
  };
  await signIn(page, 'operator1');
  await submit('in-r9.xml', 'pacs008-incoming.xml');
  await page.getByRole('link', { name: 'Transactions', exact: true }).click();
  await page.getByLabel('Search payments').fill('RENT-R9');
  await expect(page.locator('tbody tr', { hasText: 'CREDITED' })).toHaveCount(1, { timeout: 20000 });

  // the recall arrives; the credited payment shows up in the review queue with the sender's reason
  await submit('recall-r9.xml', 'camt056-recall.xml');
  await page.getByRole('link', { name: 'Responses and requests', exact: true }).click();
  await expect(page.locator('tbody tr', { hasText: 'Recall request' }).first()).toContainText('1 waiting for a decision', { timeout: 20000 });
  await page.getByRole('link', { name: 'Review queue', exact: true }).click();
  await expect(page.getByRole('heading', { name: "Recalls of the sender's bank waiting for a decision" })).toBeVisible();
  const row = page.locator('tbody tr', { hasText: 'DUPL Sent twice by mistake' });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('Camille Laurent');
  await row.click();
  await expect(page.getByText("The sender's bank asks for this payment back")).toBeVisible();
  await expect(page.getByRole('button', { name: 'Request refund' })).toHaveCount(0);

  page.once('dialog', (dialog) => dialog.accept('The customer agrees'));
  await page.getByRole('button', { name: 'Request to send it back' }).click();
  await expect(page.getByText('was created. It takes effect when another user approves it.')).toBeVisible();
  const approvalId = await page.getByRole('link', { name: /^ORVAPR/ }).textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + approvalId + '/approve', { comment: 'confirmed' });
  expect(decided.status).toBe('APPROVED');
  await expect(async () => {
    await page.reload();
    await expect(page.getByRole('list', { name: 'Progress of the payment' })).toContainText('Sent back (done)', { timeout: 2000 });
  }).toPass({ timeout: 30000 });
  await expect(page.getByText('accepted, the payment is sent back')).toBeVisible();
  await expect(page.getByText('sent back: FOCR')).toBeVisible();

  // nothing is left to decide
  await page.getByRole('link', { name: 'Review queue', exact: true }).click();
  await expect(page.locator('tbody tr', { hasText: 'DUPL Sent twice by mistake' })).toHaveCount(0);
  expect(problems).toEqual([]);
});

test('a customer instruction is entered in the Console and takes effect when a second person approves', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'Customer instructions', exact: true }).click();
  await page.getByRole('link', { name: /^Account limits/ }).click();
  await expect(page.getByText('The most a single payment from an account may be, and the most its payments of one day may add up to.')).toBeVisible();

  // values the rules refuse never become a request
  await page.getByLabel('Account', { exact: true }).fill('4051122334');
  await page.getByLabel('Most per payment').fill('0');
  page.once('dialog', (dialog) => dialog.accept('typing mistake'));
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText('Nothing was requested')).toBeVisible();
  await expect(page.getByText('perTransaction must be a number greater than zero')).toBeVisible();

  await page.getByLabel('Most per payment').fill('250000');
  page.once('dialog', (dialog) => dialog.accept('asked for by the customer'));
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText('takes effect when another user approves it.')).toBeVisible();
  const decide = async () => {
    const id = await page.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
    const decided = await api(request, 'checker1', 'post', '/api/approvals/' + id + '/approve', { comment: 'checked' });
    expect(decided.status, JSON.stringify(decided)).toBe('APPROVED');
  };
  await expect(page.locator('tbody tr', { hasText: '4051122334' })).toHaveCount(0);
  await decide();

  // the row appears by itself, shows who asked and who approved, and fills the form when clicked
  const row = page.locator('tbody tr', { hasText: '4051122334' });
  await expect(row).toHaveCount(1, { timeout: 10000 });
  await expect(row).toContainText('250000');
  await expect(row).toContainText('operator1 (approved by checker1)');
  await page.getByLabel('Account', { exact: true }).fill('');
  await row.click();
  await expect(page.getByLabel('Account', { exact: true })).toHaveValue('4051122334');
  await expect(page.getByLabel('Most per payment')).toHaveValue('250000');

  // removal is a request as well
  page.once('dialog', (dialog) => dialog.accept('no longer wanted'));
  await row.getByRole('button', { name: 'Request removal' }).click();
  await expect(page.getByText('takes effect when another user approves it.')).toBeVisible();
  await expect(row).toHaveCount(1);
  await decide();
  await expect(row).toHaveCount(0, { timeout: 10000 });

  // the other kinds are a tab away, each with its own columns and form
  await page.getByRole('link', { name: /^Direct debit mandates/ }).click();
  await expect(page.getByLabel('Mandate reference')).toBeVisible();
  await expect(page.getByLabel('Type (RCUR recurrent, OOFF one-off)')).toBeVisible();
  expect(problems).toEqual([]);
});

test('an account is opened and funded in the Console, each with a second person', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'Accounts', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Open an account' })).toBeVisible();
  const decide = async () => {
    const id = await page.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
    const decided = await api(request, 'checker1', 'post', '/api/approvals/' + id + '/approve', { comment: 'checked' });
    expect(decided.status, JSON.stringify(decided)).toBe('APPROVED');
  };
  const open = async (number, name, type) => {
    await page.getByLabel('Account number').fill(number);
    await page.getByLabel('Name of the account').fill(name);
    await page.getByLabel('Type', { exact: true }).selectOption(type);
    await page.getByLabel('Currency', { exact: true }).fill('zar');
    page.once('dialog', (dialog) => dialog.accept('new customer'));
    await page.getByRole('button', { name: 'Submit account for approval' }).click();
    await expect(page.getByText('takes effect when another user approves it.')).toBeVisible();
    await decide();
    await expect(page.locator('tbody tr', { hasText: number })).toHaveCount(1, { timeout: 10000 });
  };
  // an account number that is no account number is refused before it becomes a request
  await page.getByLabel('Account number').fill('1');
  page.once('dialog', (dialog) => dialog.accept('typing mistake'));
  await page.getByRole('button', { name: 'Submit account for approval' }).click();
  await expect(page.getByText('Nothing was requested')).toBeVisible();

  await open('4088000001', 'Console Test Customer', 'CUSTOMER');
  await open('9988000001', 'Console Test Cash', 'SUSPENSE');

  await page.getByLabel('Account debited').fill('9988000001');
  await page.getByLabel('Account credited').fill('4088000001');
  await page.getByLabel('Amount').fill('1250.50');
  await page.getByLabel('Currency of the posting').fill('ZAR');
  await page.getByLabel('What the posting is for').fill('Cash paid in');
  page.once('dialog', (dialog) => dialog.accept('counter receipt 17'));
  await page.getByRole('button', { name: 'Submit posting for approval' }).click();
  await expect(page.getByText('takes effect when another user approves it.')).toBeVisible();
  await decide();
  // the balance changes by itself, and the account page shows the entry
  await expect(page.locator('tbody tr', { hasText: '4088000001' })).toContainText('1,250.50', { timeout: 10000 });
  await page.locator('tbody tr', { hasText: '4088000001' }).click();
  await expect(page.getByRole('heading', { name: 'Account 4088000001' })).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await expect(page.locator('tbody tr').first()).toContainText('Cash paid in');
  await expect(page.locator('tbody tr').first()).toContainText('9988000001');
  expect(problems).toEqual([]);
});

/** The code an authenticator app shows for a key: RFC 6238 with SHA-1, 30 seconds, 6 digits. */
function oneTimeCode(key, stepsFromNow) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of key.replace(/\s/g, '')) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const bytes = Buffer.from((bits.match(/.{8}/g) || []).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000) + stepsFromNow));
  const hash = require('crypto').createHmac('sha1', bytes).update(counter).digest();
  const at = hash[hash.length - 1] & 15;
  return String((hash.readUInt32BE(at) & 0x7fffffff) % 1000000).padStart(6, '0');
}

test('a reference table is uploaded as a spreadsheet file and deployed when a checker approves', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'maker1');
  await page.getByRole('link', { name: 'Studio', exact: true }).click();
  await page.getByRole('link', { name: 'reference.BicDirectory' }).click();
  await page.getByRole('button', { name: 'Rows (CSV)' }).click();
  await expect(page.getByRole('link', { name: 'Download the rows as CSV' })).toBeVisible();
  // the rows edited in place: a cell changed, a row added, a row removed, all one request
  const rowsBefore = await page.locator('table.grid tbody tr').count();
  expect(rowsBefore).toBeGreaterThan(3);
  await page.getByLabel('name of row 1').fill('Orvanta Bank (edited)');
  await page.getByRole('button', { name: 'Add a row' }).click();
  await page.getByLabel('bic of row ' + (rowsBefore + 1)).fill('NEWBZAJJ');
  await page.getByLabel('name of row ' + (rowsBefore + 1)).fill('New Bank');
  await page.getByLabel('country of row ' + (rowsBefore + 1)).fill('ZA');
  await page.getByRole('button', { name: 'Remove row 3' }).click();
  await page.getByLabel('Comment for the approver of the edited rows').fill('edited in the table');
  await page.getByRole('button', { name: 'Submit the edited rows for approval' }).click();
  await expect(page.getByText(rowsBefore + ' row(s), 1 added, 1 changed, 1 removed')).toBeVisible();
  const edited = await page.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
  const editedDecision = await api(request, 'checker1', 'post', '/api/approvals/' + edited + '/approve', { comment: 'checked' });
  expect(editedDecision.status, JSON.stringify(editedDecision)).toBe('APPROVED');
  await page.getByRole('link', { name: 'Studio', exact: true }).click();
  await page.getByRole('link', { name: 'reference.BicDirectory' }).click();
  await page.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(page.locator('textarea').first()).toHaveValue(/Orvanta Bank \(edited\)/);
  await expect(page.locator('textarea').first()).toHaveValue(/NEWBZAJJ/);
  await page.getByRole('button', { name: 'Rows (CSV)' }).click();
  // a file without the key column is refused before anything is requested
  await page.getByLabel('CSV rows').fill('name,country\nSomewhere Bank,ZA\n');
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText("the header line needs the key column 'bic'")).toBeVisible();
  await page.getByLabel('CSV rows').fill('bic,name,country\nTESTZAJJ,"Test Bank, Limited",ZA\nFIRNZAJJ,FirstRand Bank (renamed),ZA\n');
  await page.getByLabel('Comment for the approver', { exact: true }).fill('two banks from the directory file');
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText('1 added, 1 changed, 0 removed')).toBeVisible();
  const id = await page.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + id + '/approve', { comment: 'checked' });
  expect(decided.status, JSON.stringify(decided)).toBe('APPROVED');
  await page.getByRole('link', { name: 'Studio', exact: true }).click();
  await page.getByRole('link', { name: 'reference.BicDirectory' }).click();
  await page.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(page.locator('textarea').first()).toHaveValue(/TESTZAJJ/);
  await expect(page.locator('textarea').first()).toHaveValue(/FirstRand Bank \(renamed\)/);
  expect(problems).toEqual([]);
});

test('a payment is initiated from the form with a template and is processed once a checker approves', async ({ page, request }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'New payment', exact: true }).click();
  await page.getByLabel('Debtor name').fill('Karoo Mining Supplies');
  await page.getByLabel('Debtor account').fill('4051122334');
  await page.getByLabel('Creditor name').fill('Tokyo Parts KK');
  await page.getByLabel('Creditor account').fill('62011223344');
  await page.getByLabel('Creditor bank (BIC)').fill('FIRNZAJJ');
  await page.getByLabel('Currency').fill('ZAR');
  page.once('dialog', (dialog) => dialog.accept('Tokyo Parts'));
  await page.getByRole('button', { name: 'Save as template' }).click();
  await expect(page.getByText('Template "Tokyo Parts" was saved.')).toBeVisible();
  // the template fills the form on the next visit; the amount is still needed
  await page.getByRole('link', { name: 'New payment', exact: true }).click();
  await page.getByLabel('Template').selectOption({ label: 'Tokyo Parts' });
  await expect(page.getByLabel('Creditor name')).toHaveValue('Tokyo Parts KK');
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText("a payment needs 'amount'")).toBeVisible();
  await page.getByLabel('Amount').fill('450.00');
  await page.getByLabel('End-to-end reference').fill('FORM-UI-450');
  await page.getByLabel('Remittance information').fill('Invoice 2026-118');
  await page.getByLabel('Why (for the approver)').fill('the October invoice');
  await page.getByRole('button', { name: 'Submit for approval' }).click();
  await expect(page.getByText('Initiate a payment of 450.00 ZAR from 4051122334 to Tokyo Parts KK')).toBeVisible();
  const id = await page.getByRole('link', { name: /^ORVAPR/ }).last().textContent();
  const decided = await api(request, 'checker1', 'post', '/api/approvals/' + id + '/approve', { comment: 'checked' });
  expect(decided.status, JSON.stringify(decided)).toBe('APPROVED');
  expect(decided.result.outcome).toBe('SUBMITTED');
  // the payment is in the list like any other
  await page.getByRole('link', { name: 'Transactions', exact: true }).click();
  await expect(page.locator('tbody tr', { hasText: 'FORM-UI-450' })).toHaveCount(1, { timeout: 15000 });
  await expect(page.locator('tbody tr', { hasText: 'FORM-UI-450' })).toContainText('Tokyo Parts KK');
  expect(problems).toEqual([]);
});

test('a nostro statement is submitted and read with its balances and entries', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'operator1');
  await page.getByRole('link', { name: 'Instructions', exact: true }).click();
  await page.getByRole('button', { name: 'Submit an instruction' }).click();
  await page.locator('input[type=file]').setInputFiles(path.join(SAMPLES, 'mt940-nostro.fin'));
  await page.getByRole('button', { name: 'Submit', exact: true }).click();
  await expect(page.getByText('Received 1 message(s).')).toBeVisible();

  await page.getByRole('link', { name: 'Statements', exact: true }).click();
  await expect(page.getByText('Statements of our accounts at correspondents and clearing systems')).toBeVisible();
  const row = page.locator('tbody tr').first();
  await expect(row).toBeVisible({ timeout: 15000 });
  await expect(row.locator('.badge')).toHaveCount(1);
  // the statement's page: balances, the balance check and every entry with its result
  await row.click();
  await expect(page.getByText(/^Statement .* of account /)).toBeVisible();
  await expect(page.getByText('Opening balance')).toBeVisible();
  await expect(page.getByText('Closing balance')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Entries' })).toBeVisible();
  await expect(page.locator('tbody tr').first()).toBeVisible();
  // the filters narrow the list
  await page.goBack();
  await page.getByLabel('Any balance check').selectOption('FAILED');
  await expect(page.getByText('No statement matches these filters.')).toBeVisible();
  expect(problems).toEqual([]);
});

test('a channel is edited in a form and the model text follows', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'maker1');
  await page.getByRole('link', { name: 'Studio', exact: true }).click();
  await page.getByRole('link', { name: 'channels.CorporateIsoInbound' }).click();
  await page.getByRole('button', { name: 'Design', exact: true }).click();
  await expect(page.getByText('Where messages come in or go out')).toBeVisible();
  await expect(page.getByLabel('purpose', { exact: true })).toHaveValue('instruction');
  await expect(page.getByLabel('duplicates', { exact: true })).toHaveValue('HOLD');
  // a setting changed in the form, a transport added: both reach the model text
  await page.getByLabel('heldExpiryDays', { exact: true }).fill('7');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  const added = page.getByLabel('Transports (inbound) 4 type');
  await added.selectOption('sftp');
  await page.getByLabel('host', { exact: true }).last().fill('files.partner.example');
  await page.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(page.locator('textarea').first()).toHaveValue(/heldExpiryDays: 7/);
  await expect(page.locator('textarea').first()).toHaveValue(/type: sftp[\s\S]*host: files\.partner\.example/);
  // the model is checked like any other: an sftp transport without its settings is a problem, not a surprise later
  await page.getByRole('button', { name: 'Validate' }).click();
  await expect(page.getByText(/problem\(s\)\. Nothing was changed\./)).toBeVisible();
  // a connector has its own form
  await page.getByRole('link', { name: 'connectors.SanctionsScreening' }).click();
  await page.getByRole('button', { name: 'Design', exact: true }).click();
  await expect(page.getByText('An external system a flow calls.')).toBeVisible();
  await expect(page.getByLabel('type', { exact: true })).toHaveValue('http');
  // and a test case: what is given, what the connectors answer, what must hold
  await page.getByRole('link', { name: 'tests.ClosedDebtorAccountIsRejected' }).click();
  await page.getByRole('button', { name: 'Design', exact: true }).click();
  await expect(page.getByLabel('Model under test')).toHaveValue('payments.flows.TransactionProcessing');
  await expect(page.getByLabel('Expectations (one expression per line)')).toHaveValue(/result\.code == 'AC04'/);
  await page.getByLabel('Expectations (one expression per line)').fill("result.status == 'REJECTED'\nresult.code == 'AC04'\nnot exists(txn.screening)\nresult.message != null");
  await page.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(page.locator('textarea').first()).toHaveValue(/result\.message != null/);
  expect(problems).toEqual([]);
});

test('a message specification is edited in a form and the model text follows', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'maker1');
  await page.getByRole('link', { name: 'Studio', exact: true }).click();
  await page.getByRole('link', { name: 'specs.swift.MT103' }).click();
  await page.getByRole('button', { name: 'Design', exact: true }).click();
  await expect(page.getByText('Field rules of an MT message, sequence by sequence')).toBeVisible();
  await expect(page.getByLabel('Message type', { exact: true })).toHaveValue('MT103');
  await expect(page.getByLabel('tag', { exact: true }).first()).toHaveValue('20');
  // a field added at the end of the sequence, with its format; a name changed: both reach the model text
  await page.getByRole('button', { name: 'Add field' }).click();
  await page.getByLabel('tag', { exact: true }).last().fill('77T');
  await page.getByLabel('field name', { exact: true }).last().fill('envelope contents');
  await page.getByLabel('format', { exact: true }).last().fill('9000z');
  await page.getByLabel('field name', { exact: true }).first().fill('sender reference, edited');
  await page.getByRole('button', { name: 'Model', exact: true }).click();
  await expect(page.locator('textarea').first()).toHaveValue(/77T/);
  await expect(page.locator('textarea').first()).toHaveValue(/9000z/);
  await expect(page.locator('textarea').first()).toHaveValue(/sender reference, edited/);
  // the specification is still a valid one
  await page.getByRole('button', { name: 'Validate' }).click();
  await expect(page.getByText('The model compiles together with the rest of the deployment.')).toBeVisible();
  // a new specification starts from a template in the form
  await page.getByRole('button', { name: 'New message specification' }).click();
  await expect(page.getByLabel('Message type', { exact: true })).toHaveValue('MT103');
  await expect(page.getByLabel('tag', { exact: true }).first()).toHaveValue('20');
  expect(problems).toEqual([]);
});

test('a user turns on the second step, signs in with a code, and turns it off again', async ({ page }) => {
  const problems = [];
  watch(page, problems);
  await signIn(page, 'auditor1');
  await page.getByRole('link', { name: 'My account' }).click();
  await expect(page.getByRole('heading', { name: 'My account' })).toBeVisible();
  await page.getByRole('button', { name: 'Set up the second step' }).click();
  const key = (await page.locator('dd code').first().textContent()).replace(/\s/g, '');
  expect(key).toMatch(/^[A-Z2-7]{32}$/);

  // a wrong code turns nothing on
  await page.getByLabel('Code shown by the app').fill('000000');
  await page.getByRole('button', { name: 'Turn the second step on' }).click();
  await expect(page.getByText('that is not the current code')).toBeVisible();
  await page.getByLabel('Code shown by the app').fill(oneTimeCode(key, 0));
  await page.getByRole('button', { name: 'Turn the second step on' }).click();
  // the recovery codes are shown once; the page goes on when the user has saved them
  await expect(page.getByRole('heading', { name: 'Recovery codes' })).toBeVisible();
  expect((await page.locator('pre').textContent()).trim().split(/\r?\n/)).toHaveLength(8);
  await page.getByRole('button', { name: 'I have saved them' }).click();
  await expect(page.getByText(/On since/)).toBeVisible();
  await expect(page.getByText('Recovery codes left: 8.')).toBeVisible();

  // signing in now asks for the code after the password
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, 'auditor1');
  const code = page.getByLabel('Code from your authenticator app');
  await expect(code).toBeVisible();
  await expect(page.getByText('Enter the code from your authenticator app.')).toBeVisible();
  await code.fill(oneTimeCode(key, 1));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByRole('link', { name: 'My account' }).click();
  await expect(page.getByText(/On since/)).toBeVisible();

  // turning it off takes a current code; afterwards the password alone signs in again
  await page.getByLabel('Current code', { exact: true }).fill(oneTimeCode(key, 1));
  await page.getByRole('button', { name: 'Turn the second step off' }).click();
  await expect(page.getByRole('button', { name: 'Set up the second step' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await signIn(page, 'auditor1');
  await expect(page.getByRole('link', { name: 'My account' })).toBeVisible();
  expect(problems).toEqual([]);
});
