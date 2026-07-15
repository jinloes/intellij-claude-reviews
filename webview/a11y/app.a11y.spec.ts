import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { exampleDiff, examplePr, installHostFixture, pushHostMessage } from './hostFixture'

async function expectNoViolations(page: import('@playwright/test').Page) {
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations, results.violations.map((v) => `${v.id} (${v.impact}): ${v.description} [${v.nodes.length}]`).join('\n')).toEqual([])
}

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
  await page.goto('/')
  await page.waitForLoadState('networkidle')
})

test('populated discovery and provider-ready review have no axe violations', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expectNoViolations(page)
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded', prKey: 'acme/platform#42', prState: 'NO_DRAFT', diff: exampleDiff,
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  })
  await expectNoViolations(page)
})

test('full-workspace setup has no axe violations', async ({ page }) => {
  await pushHostMessage(page, { type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Authenticate GitHub CLI.' })
  await expect(page.getByRole('main', { name: 'PR Pilot setup' })).toBeVisible()
  await expectNoViolations(page)
})

test('risky submit dialog is accessible and requires acknowledgement', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    result: {
      summary: 'Authentication review',
      verdict: 'COMMENT',
      lineComments: [{ file: 'src/auth.ts', line: 2, type: 'issue', body: 'Explain this security behavior.', severity: 'major', confidence: 'high' }],
    },
  })
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  const submit = page.getByRole('button', { name: 'Submit Comment' })
  await expect(submit).toBeDisabled()
  await expectNoViolations(page)
  await page.getByRole('checkbox', { name: /I reviewed these unresolved trust risks/ }).check()
  await expect(submit).toBeEnabled()
})
