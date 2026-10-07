// Every screen of the Console, at a desktop and a phone width, in the light and the dark theme:
// no accessibility problem that axe can find against WCAG 2.1 A and AA, and nothing wider than the window.
// It runs after console.spec.js (files run in name order), so the screens are full of what those tests made.
//
// An automated check finds only part of what an audit by a person finds: it cannot judge whether a label
// makes sense, whether the order of things is logical, or how the page reads in a screen reader.
const { test, expect } = require('@playwright/test');
const { AxeBuilder } = require('@axe-core/playwright');

const password = (user) => process.env['ORVANTA_TEST_PASSWORD_' + user.toUpperCase()];

async function signIn(page, user) {
  await page.goto('/');
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(password(user));
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.locator('.side').waitFor();
}

/** The address of a detail page: the first row of a list is opened and its address kept. */
async function firstOf(page, hash) {
  await page.goto('/' + hash);
  await page.locator('tbody tr').first().waitFor();
  await page.locator('tbody tr').first().click();
  await page.waitForFunction((list) => location.hash !== list && location.hash.split('?')[0] !== list, hash);
  return page.evaluate(() => location.hash);
}

async function screens(page) {
  const list = [
    ['dashboard', '#/dashboard', '.chart'],
    ['instructions', '#/instructions', '.pager'],
    ['submit an instruction', '#/upload', 'input[type=file]'],
    ['transactions', '#/transactions', '.pager'],
    ['transactions with filters', '#/transactions?status=ACCEPTED&q=mokoena&from=2020-01-01', '.chip'],
    ['review queue', '#/review', 'h2'],
    ['charge claims', '#/claims', '.pager'],
    ['my account', '#/account', 'h2'],
    ['accounts', '#/ledger', '.formgrid'],
    ['one account', '#/ledger/4088000001', 'tbody tr'],
    ['customer instructions', '#/data', '.pager'],
    ['customer instructions: mandates', '#/data/data.Mandates', '.formgrid'],
    ['outbound files', '#/outbound', '.pager'],
    ['responses and requests', '#/responses', '.pager'],
    ['statements', '#/statements', '.pager'],
    ['daily report', '#/daily', 'h2'],
    ['failed events', '#/deadletters', '.pager'],
    ['studio', '#/studio', '.models'],
    ['studio: model text', '#/studio/payments.rules.TransactionValidation', 'textarea'],
    ['deployments', '#/deployments', 'tbody tr'],
    ['message checks', '#/checks', 'textarea'],
    ['approvals', '#/approvals', '.pager'],
    ['users and roles', '#/users', 'tbody tr'],
    ['security log', '#/security', '.pager'],
  ];
  list.push(['instruction', await firstOf(page, '#/instructions'), '.kv']);
  list.push(['transaction', await firstOf(page, '#/transactions'), '.progress']);
  list.push(['outbound file', await firstOf(page, '#/outbound'), 'pre']);
  list.push(['deployment', await firstOf(page, '#/deployments'), 'h2']);
  list.push(['approval', await firstOf(page, '#/approvals'), '.timeline']);
  return list;
}

const DESIGNERS = [['flow designer', 'payments.flows.TransactionProcessing', '.fd-canvas .fd-step'], ['rule set designer', 'payments.rules.TransactionValidation', '.fd-canvas .fd-step'],
  ['decision table designer', 'payments.routing.OutboundRouting', '.dt'], ['mapping designer', 'payments.outbound.CanonicalToMt103', '.fd-canvas .fd-step']];

async function check(page, name, problems) {
  await page.waitForTimeout(250);          // the page fades in; colours are judged when it has
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  for (const v of result.violations) {
    problems.push(`${name}: ${v.id} (${v.impact}): ${v.help}; for example ${v.nodes[0].target.join(' ')}` + (v.nodes.length > 1 ? ` and ${v.nodes.length - 1} more` : ''));
  }
  const wider = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  if (wider > 1) problems.push(`${name}: the page is ${wider}px wider than the window`);
}

for (const [scheme, width, height] of [['light', 1280, 800], ['dark', 1280, 800], ['light', 390, 844], ['dark', 390, 844]]) {
  test(`every screen passes the accessibility check and fits the window: ${scheme} theme, ${width}px wide`, async ({ browser }) => {
    test.setTimeout(240_000);
    const page = await (await browser.newContext({ colorScheme: scheme, viewport: { width, height } })).newPage();
    const problems = [];
    await page.goto('/');
    await page.locator('#username').waitFor();
    await check(page, 'sign-in', problems);
    await signIn(page, 'maker1');
    for (const [name, hash, ready] of await screens(page)) {
      await page.goto('/' + hash);
      await page.locator(ready).first().waitFor();
      await check(page, name, problems);
    }
    for (const [name, model, ready] of DESIGNERS) {
      await page.goto('/#/studio/' + model);
      await page.getByRole('button', { name: 'Design' }).click();
      await page.locator(ready).first().waitFor();
      await page.locator(ready).first().click();          // a step chosen, so that its form is checked too
      await check(page, name, problems);
    }
    expect(problems, problems.join('\n')).toEqual([]);
  });
}
