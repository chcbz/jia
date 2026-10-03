#!/usr/bin/env node
import fs from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const required = name => {
  const value = process.env[name]
  if (!value || value.trim() !== value) throw new Error(`${name} is required`)
  return value
}
const exactId = name => {
  const value = required(name)
  if (value.length > 200 || /[\u0000-\u001f\u007f]/u.test(value)) throw new Error(`${name} is invalid`)
  return value
}
const privateHost = host => {
  if (host === 'localhost' || host === '127.0.0.1' || host === '::1') return true
  const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host)
  if (!match) return false
  const octets = match.slice(1).map(Number)
  if (octets.some(value => value > 255)) return false
  return octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168)
}
const privateOrigin = (name, value) => {
  const url = new URL(value)
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      url.pathname !== '/' || url.search || url.hash || !privateHost(url.hostname)) {
    throw new Error(`${name} must be a loopback/RFC1918 HTTP(S) origin`)
  }
  return url.origin
}
const safeHeader = (name, value) => {
  if (!value || value.length > 200 || /[^\x20-\x7e]/u.test(value)) return null
  if (name === 'etag') return /^(?:W\/)?"[A-Za-z0-9._:-]{1,160}"$/.test(value) ? value : null
  try {
    const location = new URL(value, apiOrigin)
    return location.origin === apiOrigin ? location.pathname : null
  } catch {
    return null
  }
}
const webOrigin = privateOrigin('CYF_WEB_ORIGIN', required('CYF_WEB_ORIGIN'))
const apiOrigin = privateOrigin('CYF_FIXTURE_API_ORIGIN', required('CYF_FIXTURE_API_ORIGIN'))
const jobId = exactId('CYF_FIXTURE_JOB_ID')
const workId = exactId('CYF_FIXTURE_WORK_ID')
const editionId = exactId('CYF_FIXTURE_EDITION_ID')
const webRoot = path.resolve(required('CYF_WEB_ROOT'))
const resultFile = path.resolve(required('CYF_BROWSER_RESULT_FILE'))
const tokenFile = path.resolve(required('CYF_FIXTURE_ADMIN_TOKEN_FILE'))
const playwrightEntry = process.env.CYF_PLAYWRIGHT_ENTRY
  ? path.resolve(required('CYF_PLAYWRIGHT_ENTRY'))
  : path.join(webRoot, 'node_modules', 'playwright', 'index.js')
const { chromium } = await import(pathToFileURL(playwrightEntry).href)
const browserChannel = process.env.CYF_BROWSER_CHANNEL
if (browserChannel !== undefined && !['chrome', 'msedge'].includes(browserChannel)) {
  throw new Error('CYF_BROWSER_CHANNEL must be chrome or msedge when set')
}
const token = (await fs.readFile(tokenFile, 'utf8')).trim()
if (!token || token.length > 16384 || /\s/u.test(token)) throw new Error('fixture token file is invalid')
await fs.mkdir(path.dirname(resultFile), { recursive: true })

const evidence = {
  status: 'failed',
  runtimeEvidence: 'UNVERIFIED',
  apiOrigin,
  jobId,
  workId,
  editionId,
  viewports: [],
  apiResponses: []
}
const browser = await chromium.launch(browserChannel === undefined
  ? { headless: true }
  : { headless: true, channel: browserChannel })
const requiredApi = [
  `/archive/admin/v1/jobs/${encodeURIComponent(jobId)}`,
  `/archive/admin/v1/works/${encodeURIComponent(workId)}/editions`,
  `/archive/admin/v1/works/${encodeURIComponent(workId)}/editions/${encodeURIComponent(editionId)}`,
  `/archive/v1/editions/${encodeURIComponent(editionId)}/catalog`
]

try {
  for (const viewport of [{ width: 900, height: 600 }, { width: 540, height: 960 }]) {
    const context = await browser.newContext({ viewport })
    await context.addInitScript(value => {
      localStorage.setItem('api_token', JSON.stringify({ data: value, expTime: Date.now() + 3_500_000 }))
    }, token)
    const page = await context.newPage()
    const viewportResponses = []
    page.on('response', response => {
      let url
      try { url = new URL(response.url()) } catch { return }
      if (url.origin !== apiOrigin) return
      const record = {
        method: response.request().method(),
        path: url.pathname,
        status: response.status()
      }
      const etag = safeHeader('etag', response.headers().etag)
      const location = safeHeader('location', response.headers().location)
      if (etag) record.etag = etag
      if (location) record.location = location
      viewportResponses.push(record)
      evidence.apiResponses.push({ viewport: `${viewport.width}x${viewport.height}`, ...record })
    })


    await page.goto(`${webOrigin}/juyiting`, { waitUntil: 'domcontentloaded', timeout: 30_000 })
    const onboarding = page.locator('.onboarding-dialog')
    if (await onboarding.isVisible().catch(() => false)) {
      const later = onboarding.getByRole('button', { name: '稍后', exact: true })
      const skip = onboarding.getByRole('button', { name: '跳过本版本', exact: true })
      if (await later.isVisible().catch(() => false)) await later.click()
      else if (await skip.isVisible().catch(() => false)) await skip.click()
    }

    const libraryButton = page.getByRole('button', { name: '典籍阁', exact: true }).first()
    await libraryButton.waitFor({ state: 'visible', timeout: 30_000 })
    await libraryButton.click()
    await page.locator('#library-maintenance-tab').click()
    await page.locator('#library-maintenance-panel').waitFor({ state: 'visible', timeout: 30_000 })

    const jobSelect = page.locator('label').filter({ hasText: '选择维护单' }).locator('select').first()
    await jobSelect.waitFor({ state: 'visible', timeout: 30_000 })
    await jobSelect.selectOption(jobId)
    await page.getByText(`作业 ${jobId}`, { exact: false }).waitFor({ state: 'visible', timeout: 30_000 })

    await page.getByRole('button', { name: '版本与记录', exact: true }).click()
    const historyPanel = page.locator('#archive-panel-history')
    await historyPanel.waitFor({ state: 'visible', timeout: 30_000 })
    const workSelect = historyPanel.locator('label').filter({ hasText: '作品' }).locator('select').first()
    await workSelect.waitFor({ state: 'visible', timeout: 30_000 })
    await workSelect.selectOption(workId)
    const editionButton = page.getByRole('button', { name: editionId, exact: true })
    await editionButton.waitFor({ state: 'visible', timeout: 30_000 })
    await editionButton.click()
    await page.getByText(`版本 ${editionId}`, { exact: false }).waitFor({ state: 'visible', timeout: 30_000 })

    const openReader = page.getByRole('button', { name: '阅读已核验版本', exact: true })
    await openReader.waitFor({ state: 'visible', timeout: 30_000 })
    await openReader.click()
    await page.locator('.archive-reader-fullscreen').waitFor({ state: 'visible', timeout: 30_000 })
    await page.locator('.reader-paragraph').first().waitFor({ state: 'visible', timeout: 30_000 })

    for (const expectedPath of requiredApi) {
      const match = viewportResponses.find(row => row.path === expectedPath && row.status >= 200 && row.status < 300)
      if (!match) throw new Error(`Missing successful actual API response: ${expectedPath}`)
    }
    const blockPrefix = `/archive/v1/editions/${encodeURIComponent(editionId)}/chapters/`
    const hasExactBody = viewportResponses.some(row => row.status >= 200 && row.status < 300 &&
      (row.path === `/archive/v1/editions/${encodeURIComponent(editionId)}/preface` || row.path.startsWith(blockPrefix)))
    if (!hasExactBody) throw new Error('Reader did not load the exact edition preface/chapter API')

    const screenshot = path.join(path.dirname(resultFile), `browser-${viewport.width}x${viewport.height}.png`)
    await page.screenshot({ path: screenshot, fullPage: true })
    evidence.viewports.push({
      ...viewport,
      screenshot,
      maintenanceVisible: true,
      readerVisible: true,
      paragraphCount: await page.locator('.reader-paragraph').count()
    })
    await context.close()
  }
  evidence.status = 'passed'
  evidence.runtimeEvidence = 'BROWSER_PATH_OBSERVED'
} catch (error) {
  evidence.errorType = error?.constructor?.name || 'Error'
  evidence.errorMessage = String(error?.message || 'browser harness failed').slice(0, 500)
  throw error
} finally {
  await browser.close()
  await fs.writeFile(resultFile, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 })
}
