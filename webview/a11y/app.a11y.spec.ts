import { test, expect } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { exampleDiff, examplePr, installHostFixture, pushHostMessage } from './hostFixture'

async function expectNoViolations(page: import('@playwright/test').Page, include?: string) {
  const builder = new AxeBuilder({ page })
  const results = await (include ? builder.include(include) : builder).analyze()
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
  await page.setViewportSize({ width: 320, height: 568 })
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, {
    type: 'setupRequired',
    reason: 'gh_not_authenticated',
    detail: 'Authenticate GitHub CLI.',
    providerReadiness: {
      provider: 'claude',
      available: false,
      detail: 'Claude Code authentication must be repaired before reviews can run.',
      authCommand: 'claude auth login',
      authenticationStatus: 'unavailable',
    },
  })
  const main = page.getByRole('main', { name: 'PR Pilot setup' })
  await expect(main).toBeVisible()
  const horizontalGeometry = await main.evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
  }))
  expect(horizontalGeometry.scrollWidth).toBeLessThanOrEqual(horizontalGeometry.clientWidth)
  await expectNoViolations(page)
})

test('longest risky submit dialog is accessible, viewport-safe, and requires acknowledgement', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    result: {
      summary: 'Authentication review\n\nConfirm each unresolved trust concern before publishing this deliberately long summary.',
      verdict: 'COMMENT',
      lineComments: [
        {
          file: 'src/auth.ts',
          line: 2,
          type: 'issue',
          body: 'Authentication may be bypassed.',
          severity: 'major',
          confidence: 'low',
          rationale: 'The model marked this claim as low confidence.',
        },
        {
          file: 'src/auth.ts',
          line: 1,
          type: 'issue',
          body: 'Document the trust boundary.',
          severity: 'minor',
          confidence: 'high',
        },
        {
          file: 'src/auth.ts',
          line: 999,
          type: 'note',
          body: 'This anchor no longer exists.',
          severity: 'minor',
          confidence: 'high',
          rationale: 'The target line is outside every current hunk.',
        },
      ],
    },
  })
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  const dialog = page.getByRole('alertdialog')
  const submit = page.getByRole('button', { name: 'Submit Comment' })
  await expect(dialog).toContainText('3 unresolved trust risks')
  await expect(submit).toBeDisabled()
  const box = await dialog.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(16)
  expect(box!.y).toBeGreaterThanOrEqual(16)
  expect(box!.x + box!.width).toBeLessThanOrEqual(304)
  expect(box!.y + box!.height).toBeLessThanOrEqual(552)
  await expectNoViolations(page, '[role="alertdialog"]')
  await page.getByRole('checkbox', { name: /I reviewed these unresolved trust risks/ }).check()
  await expect(submit).toBeEnabled()
})

test('dark and high-contrast primary controls have no axe violations', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  for (const theme of ['dark', 'highContrastDark', 'highContrastLight'] as const) {
    await pushHostMessage(page, { type: 'themeChanged', theme })
    await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
    await page.getByRole('button', { name: /Improve authentication/ }).click()
    await pushHostMessage(page, {
      type: 'draftLoaded',
      prKey: 'acme/platform#42',
      prState: 'NO_DRAFT',
      diff: exampleDiff,
      validationDiff: exampleDiff,
      providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
    })

    await expect(page.getByRole('button', { name: 'Generate Review' })).toBeVisible()
    await page.waitForTimeout(200)
    await expectNoViolations(page)
    await page.getByRole('button', { name: 'Show pull requests' }).click()
  }
})

test('Comment can be selected from an Approve split menu', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    result: { summary: 'Ready to submit', verdict: 'APPROVE', lineComments: [] },
  })

  await page.getByRole('button', { name: 'More submit options' }).click()
  const comment = page.getByRole('menuitem', { name: 'Comment' })
  await expect(comment).toBeEnabled()
  await comment.click()
  await expect(page.getByRole('alertdialog')).toContainText('Submit comment?')
  await page.getByRole('button', { name: 'Submit Comment' }).click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<{ type?: string; verdict?: string }> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'submitReview')
  })).toEqual([expect.objectContaining({ verdict: 'COMMENT' })])
})

test('selected review and chat have no axe violations in a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: exampleDiff,
    validationDiff: exampleDiff,
    result: { summary: '- Check authentication\n- Check keyboard behavior', verdict: 'COMMENT', lineComments: [] },
  })
  await page.getByRole('button', { name: 'Chat' }).click()

  await expect(page.getByRole('region', { name: 'Chat' })).toBeVisible()
  await expectNoViolations(page)
})

test('unrenderable diff warning and submit acknowledgement have no axe violations', async ({ page }) => {
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await page.getByRole('button', { name: /Improve authentication/ }).click()
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: 'not a unified diff',
    validationDiff: 'not a unified diff',
    result: { summary: 'Review summary', verdict: 'COMMENT', lineComments: [] },
  })

  await expect(page.getByRole('alert')).toContainText('could not render this diff')
  await expectNoViolations(page)
  await page.getByRole('button', { name: 'Comment', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Submit Comment' })).toBeDisabled()
  await expectNoViolations(page)
})
