// Browser tests of the Orvanta Console.
//
//   mvnw install -DskipTests          (the tests run the packaged server)
//   cd console-tests && npm install && npx playwright install chromium
//   npm test
//
// The tests start their own server: port 8590, in-memory store, simulated external systems, and
// the users whose passwords are generated into target/console-tests/seed-users.yaml (not committed).
const { defineConfig } = require('@playwright/test');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const root = path.resolve(__dirname, '..');
const work = path.join(root, 'target', 'console-tests');
const seed = path.join(work, 'seed-users.yaml');
const PORT = 8590;

// the workers load this file too, so the passwords are generated once and then read back
if (!fs.existsSync(seed) || !fs.readFileSync(seed, 'utf8').includes('auditor1')) {
  fs.mkdirSync(work, { recursive: true });
  const users = [['maker1', 'DESIGNER, USER_ADMIN, OPERATOR'], ['checker1', 'APPROVER'], ['operator1', 'OPERATOR'], ['auditor1', 'APPROVER']];
  fs.writeFileSync(seed, 'users:\n' + users.map(([name, roles]) =>
    `  - username: ${name}\n    displayName: ${name}\n    roles: [${roles}]\n    password: ${crypto.randomBytes(15).toString('base64url')}\n`).join(''));
}
for (const [, name, password] of fs.readFileSync(seed, 'utf8').matchAll(/username: (\S+)[\s\S]*?password: (\S+)/g)) {
  process.env['ORVANTA_TEST_PASSWORD_' + name.toUpperCase()] = password;
}

module.exports = defineConfig({
  testDir: './tests',
  timeout: 60_000,
  // a check may follow work the server does on a busy machine: ten seconds before it counts as missing
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: path.join(work, 'results.json') }]],
  use: {
    baseURL: `http://localhost:${PORT}`,
    browserName: 'chromium',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  outputDir: path.join(work, 'artifacts'),
  webServer: {
    command: `java -jar orvanta-pay/target/orvanta-pay-0.1.0-all.jar --config=config/orvanta.yaml --store.type=memory `
      + `--server.port=${PORT} --data.dir=target/console-tests/data --security.seedFile=target/console-tests/seed-users.yaml `
      + `--security.loginAttempts=1000 --simulator.enabled=true --workspace.writeBack=false`,
    cwd: root,
    url: `http://localhost:${PORT}/api/health`,
    timeout: 90_000,
    reuseExistingServer: false,
    env: { ORVANTA_SIM_URL: `http://localhost:${PORT}/sim` },
  },
});
