// Production Web artifact + real loopback JWT/Q-R/SQL financial endpoints.
// Directory, recommendations and material observations are explicit UI fixtures.
// No provider request, production endpoint or synthetic financial response is allowed.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const assert = require('node:assert/strict');
const input = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const output = process.argv[3];
const dist = fs.realpathSync(process.env.CYF_BILLING_WEB_DIST);
const task = { id: input.taskId, taskVersion: '0', title: '内测报价验收事项', description: '核对事项报价与承办入口', status: 'open', requiredAbilities: [], assignedAgentIds: [] };
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const requests = [], exceptions = [], checks = [];
const startedAt = Date.now();
const progress = stage => {
  fs.writeFileSync(path.join(output, 'progress.json'), JSON.stringify({ stage, elapsedMs: Date.now() - startedAt, checks, requests, exceptions }, null, 2));
  console.log('BROWSER_STAGE', stage, Date.now() - startedAt);
};
let browser, ws, server;
(async () => {
  server = http.createServer((req, res) => {
    const requested = path.resolve(dist, '.' + new URL(req.url, 'http://localhost').pathname);
    if (requested !== dist && !requested.startsWith(dist + path.sep)) { res.writeHead(403).end(); return; }
    const file = fs.existsSync(requested) && fs.statSync(requested).isFile() ? requested : path.join(dist, 'index.html');
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.json': 'application/json', '.png': 'image/png', '.woff2': 'font/woff2' };
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const ui = 'http://127.0.0.1:' + server.address().port;
  const profile = path.join(output, 'profile');
  browser = spawn(process.env.CHROME_PATH, ['--headless', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', '--user-data-dir=' + profile, 'about:blank'], { stdio: 'ignore' });
  for (let n = 0; !fs.existsSync(path.join(profile, 'DevToolsActivePort')); n++) {
    if (n > 150 || browser.exitCode !== null) throw Error('browser startup unavailable');
    await pause(100);
  }
  const port = fs.readFileSync(path.join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0];
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json')).json();
  ws = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let sequence = 0;
  const pending = new Map();
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const r = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw Error('page evaluation failed: ' + r.exceptionDetails.text);
    return r.result.value;
  };
  const wait = async (expression, description) => {
    for (let n = 0; n < 100; n++) { if (await evaluate(expression)) return; await pause(100); }
    throw Error('missing UI state: ' + description);
  };
  const financial = pathname => ['/economy/wallet', '/economy/ledger', '/economy/events'].includes(pathname) ||
    pathname === '/agent/tasks/' + input.taskId + '/assignment-operation' ||
    pathname === '/agent/tasks/' + input.taskId + '/point-and-deliberate/request' ||
    pathname === '/agent/tasks/' + input.taskId + '/point-and-deliberate';
  let walletUrl;
  async function handleRequest(p) {
    const u = new URL(p.request.url);
    if (u.origin === ui) { await call('Fetch.continueRequest', { requestId: p.requestId }); return; }
    const record = { method: p.request.method, path: u.pathname, source: financial(u.pathname) ? 'actual-backend' : 'ui-fixture' };
    requests.push(record);
    const cors = { 'Access-Control-Allow-Origin': ui, 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS', 'Access-Control-Allow-Headers': 'authorization,content-type,x-request-id,x-client-id,idempotency-key', 'Access-Control-Allow-Credentials': 'true', 'Access-Control-Expose-Headers': 'X-Request-Id', 'Content-Type': 'application/json' };
    let status = 200, body;
    if (p.request.method === 'OPTIONS') { status = 204; body = ''; }
    else if (financial(u.pathname)) {
      // Transport proxy only: forward actual browser credentials/body unchanged and return
      // the real status/envelope. Never synthesize a wallet or mutation response.
      const headers = {};
      for (const [k, v] of Object.entries(p.request.headers))
        if (['authorization', 'content-type', 'x-request-id', 'x-client-id', 'idempotency-key'].includes(k.toLowerCase())) headers[k] = v;
      const response = await fetch(input.origin + u.pathname + u.search, { method: p.request.method, headers, body: ['GET', 'HEAD'].includes(p.request.method) ? undefined : p.request.postData });
      status = response.status; body = await response.text();
      record.status = status;
      const envelope = JSON.parse(body); record.code = envelope.code;
      if (u.pathname === '/economy/wallet') { walletUrl = p.request.url; record.wallet = envelope.data; }
      if (response.headers.has('x-request-id')) cors['X-Request-Id'] = response.headers.get('x-request-id');
    } else {
      let data = {}, code = 'E0';
      const agent = { agentId: input.agentId, name: '内测好汉', status: 'online', boundToMe: true, canOperate: true, systemAgent: false, abilities: [] };
      if (u.pathname === '/economy/capabilities') data = { economyPreviewEnabled: true, skillMarketplaceEnabled: false, principalScopeSha256: 'a'.repeat(64) };
      else if (u.pathname === '/user/my') data = { tenantId: input.tenant, id: input.payer, jiacn: input.owner, nickname: '内测验收' };
      else if (u.pathname === '/agent/roster') data = { items: [agent], total: '1', pageNum: 1, pageSize: 100 };
      else if (u.pathname === '/agent/map' || u.pathname === '/agent/personas/catalog') data = [];
      else if (u.pathname === '/agent/tasks/search') data = { items: [task], total: '1', pageNum: 1, pageSize: 20 };
      else if (u.pathname === '/agent/tasks/status-counts') data = { phaseCounts: { PENDING_ASSIGNMENT: '1', IN_PROGRESS: '0', PENDING_ACCEPTANCE: '0', UNKNOWN: '0', COMPLETED: '0', HISTORY: '0', total: '1' }, rawStatusCounts: { total: '1' } };
      else if (u.pathname === '/agent/' + input.agentId + '/profile-capabilities') data = { agentId: input.agentId, executionModel: 'gpt-6-luna', observedAt: 10000, lastSeenAt: 10000, abilities: [], skills: { status: 'AVAILABLE', catalogRevision: '1', reasonCode: null, items: [] } };
      else if (u.pathname === '/agent/tasks/' + input.taskId) data = task;
      else if (u.pathname === '/agent/tasks/' + input.taskId + '/requirements/current') data = { taskId: input.taskId, taskVersion: '0', requirementRevision: '1', title: task.title, description: task.description, contentSha256: 'a'.repeat(64), source: 'CREATE' };
      else if (u.pathname === '/agent/tasks/' + input.taskId + '/recommend') data = { taskId: input.taskId, taskVersion: '0', observedAt: 1000, state: 'NO_MATCH', requirementSummary: '测试材料观察', recommendations: [{ agent, recommendationLevel: 'UNKNOWN', reason: '尚无能力介绍', evidence: [], gaps: [], quote: { state: 'AVAILABLE', quoteId: input.quoteId, recommendationId: 'recommendation-1', amount: { amountMinor: '50', currency: 'WEN' }, unavailableReason: null } }], questions: [], candidateSetComplete: true };
      else if (u.pathname === '/agent/tasks/' + input.taskId + '/assignee-candidates') data = { items: [{ agentId: input.agentId, displayName: agent.name, providerDisplayName: null, isMine: true, availability: 'online' }], nextAfterAgentId: null };
      else { status = 503; code = 'FIXTURE_UNAVAILABLE'; }
      const requestId = Object.entries(p.request.headers).find(([k]) => k.toLowerCase() === 'x-request-id')?.[1] || 'browser-fixture';
      cors['X-Request-Id'] = requestId;
      body = JSON.stringify({ protocolVersion: 1, requestId, code, msg: 'declared ancillary UI fixture', data });
    }
    await call('Fetch.fulfillRequest', { requestId: p.requestId, responseCode: status, responseHeaders: Object.entries(cors).map(([name, value]) => ({ name, value })), body: Buffer.from(body).toString('base64') });
  }
  ws.onmessage = event => {
    const m = JSON.parse(event.data);
    if (m.id) { const p = pending.get(m.id); if (p) { pending.delete(m.id); m.error ? p.reject(Error(m.error.message)) : p.resolve(m.result); } }
    else if (m.method === 'Runtime.exceptionThrown') exceptions.push(m.params.exceptionDetails.text);
    else if (m.method === 'Fetch.requestPaused') handleRequest(m.params).catch(error => exceptions.push(error.message));
  };
  progress('cdp-connected');
  await call('Page.enable'); await call('Runtime.enable'); await call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await call('Page.addScriptToEvaluateOnNewDocument', { source: `localStorage.setItem('api_token',JSON.stringify({data:${JSON.stringify(input.token)},expTime:Date.now()+3600000}));` });
  await call('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  progress('open-wallet');
  await call('Page.navigate', { url: ui + '/wallet' });
  await wait("Boolean(document.querySelector('.wallet-balances'))", 'wallet loaded');
  checks.push({ stage: 'initial-wallet', text: await evaluate("document.querySelector('.wallet-balances').innerText") });
  progress('wallet-loaded');
  await call('Page.navigate', { url: ui + '/juyiting' });
  await wait("Boolean([...document.querySelectorAll('[data-workbench-tab=\"tasks\"]')].find(e=>e.getBoundingClientRect().width>0))", 'hall task navigation');
  await evaluate("document.querySelector('[aria-label=\"稍后查看新手引导\"]')?.click()");
  await evaluate("[...document.querySelectorAll('[data-workbench-tab=\"tasks\"]')].find(e=>e.getBoundingClientRect().width>0)?.click()");
  await wait("Boolean(document.querySelector('.task-card'))", 'task list');
  progress('task-list-loaded');
  await evaluate("document.querySelector('.task-card').click()");
  await wait("Boolean(document.querySelector('.matter-candidate-card'))", 'quoted candidate');
  progress('candidate-loaded');
  await evaluate("document.querySelector('.matter-candidate-card').click()");
  await wait("document.querySelector('.matter-primary-action')?.disabled === false", 'explicit confirmation');
  assert.equal(requests.filter(r => r.method === 'POST' && r.path.endsWith('/point-and-deliberate')).length, 0, 'candidate selection must not charge');
  checks.push({ stage: 'quoted', text: await evaluate("document.querySelector('.matter-quote')?.textContent") });
  assert.match(checks[1].text, /50/);
  progress('before-confirm');
  await evaluate("document.querySelector('.matter-primary-action').click()");
  progress('after-confirm-click');
  for (let n = 0; n < 100 && !requests.some(r => r.path.endsWith('/point-and-deliberate') && r.status); n++) await pause(100);
  const posts = requests.filter(r => r.method === 'POST' && r.path.endsWith('/point-and-deliberate'));
  assert.equal(posts.length, 1);
  assert.equal(posts[0].status, input.scenario === 'reserve' ? 200 : 409);
  progress('financial-response');
  if (input.scenario === 'reserve') await wait("document.body.innerText.includes('正在准备会话')", 'authoritative assignment pending feedback');
  if (input.scenario === 'insufficient') await wait("document.body.innerText.includes('可用文余额不足，无法确认承办；本次未预占费用。')", 'authoritative insufficient-funds feedback');
  // Read through the browser transport again; Java independently checks the same committed DB.
  const wallet = await evaluate(`fetch(${JSON.stringify(walletUrl)},{headers:{Authorization:'Bearer '+JSON.parse(localStorage.getItem('api_token')).data,'X-Request-Id':'browser-wallet-after'}}).then(r=>r.json())`);
  assert.equal(wallet.code, 'E0');
  assert.equal(wallet.data.available.amountMinor, input.scenario === 'reserve' ? '50' : '49');
  assert.equal(wallet.data.held.amountMinor, input.scenario === 'reserve' ? '50' : '0');
  checks.push({ stage: 'after-confirm', wallet: wallet.data, text: await evaluate('document.body.innerText') });
  for (const width of [1440, 390]) {
    await call('Emulation.setDeviceMetricsOverride', { width, height: width === 390 ? 844 : 1000, deviceScaleFactor: 1, mobile: width === 390 });
    await pause(250);
    assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, 'horizontal overflow');
    const image = await call('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    fs.writeFileSync(path.join(output, width + '.png'), Buffer.from(image.data, 'base64'));
  }
  assert.deepEqual(exceptions, []);
})().catch(error => { console.error(error.stack); process.exitCode = 1; }).finally(async () => {
  fs.writeFileSync(path.join(output, 'checks.json'), JSON.stringify({ scope: 'Web470 production bundle; actual JWT/Q-R/Tomcat/MySQL wallet and assignment; ancillary UI/material fixtures, no provider execution', scenario: input.scenario, checks, requests, exceptions }, null, 2) + '\n');
  ws?.close();
  if (browser && browser.exitCode === null) { browser.kill('SIGTERM'); await Promise.race([new Promise(resolve => browser.once('exit', resolve)), pause(1500)]); if (browser.exitCode === null) browser.kill('SIGKILL'); }
  if (server) server.closeAllConnections();
  server?.close();
  fs.rmSync(path.join(output, 'profile'), { recursive: true, force: true });
});
