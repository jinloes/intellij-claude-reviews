import { expect, test, type Page } from '@playwright/test'
import { examplePr, installHostFixture, pushHostMessage } from '../a11y/hostFixture'
import { MIN_REVIEW_BODY_HEIGHT } from '../src/components/ReviewPane/chatHeight'
import { buildExampleFixPrompt, buildVerifyCommentPrompt } from '../src/components/ReviewPane/verifyPrompt'

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
})

async function expectViewportFilled(page: Page) {
  const dimensions = await page.locator('#root').evaluate((root) => {
    const main = root.querySelector('main')
    return {
      rootHeight: root.getBoundingClientRect().height,
      mainHeight: main?.getBoundingClientRect().height,
      viewportHeight: window.innerHeight,
    }
  })

  expect(dimensions.rootHeight).toBe(dimensions.viewportHeight)
  expect(dimensions.mainHeight).toBe(dimensions.viewportHeight)
}

async function expectNoHorizontalOverflow(page: Page, selector: string) {
  const dimensions = await page.locator(selector).evaluate((element) => ({
    clientWidth: element.clientWidth,
    scrollWidth: element.scrollWidth,
  }))
  expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth)
}

const reviewDiff = `diff --git a/src/auth.ts b/src/auth.ts
--- a/src/auth.ts
+++ b/src/auth.ts
@@ -1,1 +1,2 @@
 export const ready = true
+export const accessible = true
`
const reviewComment = {
  file: 'src/auth.ts',
  line: 2,
  type: 'issue',
  body: 'Explain this security behavior.',
}

async function selectExamplePr(page: Page, hasReviewDraft: boolean) {
  const pr = { ...examplePr, hasReviewDraft }
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr] })
  await page.locator('nav li > button').first().click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.find((message) => message.type === 'selectPR')
  })).toMatchObject({ type: 'selectPR', number: 42, owner: 'acme', repo: 'platform' })
}

async function openDraftReview(page: Page) {
  await selectExamplePr(page, true)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-1',
    diff: reviewDiff,
    validationDiff: reviewDiff,
    result: {
      summary: 'Authentication review',
      verdict: 'COMMENT',
      lineComments: [reviewComment],
    },
  })
}

async function openNoDraftReview(page: Page, diff = reviewDiff) {
  await selectExamplePr(page, false)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'NO_DRAFT',
    diff,
    validationDiff: diff,
    providerReadiness: { provider: 'claude', available: true, detail: 'Ready' },
  })
}

async function openGeneratingReview(page: Page, activityCount = 1) {
  await openNoDraftReview(page)
  await page.getByRole('button', { name: 'Generate Review' }).click()
  for (let index = 0; index < activityCount; index += 1) {
    await pushHostMessage(page, {
      type: 'reviewGenerating',
      prKey: 'acme/platform#42',
      message: index === 0 ? 'read_file' : `tool_${index}`,
    })
  }
}

async function openLongestRiskySubmit(page: Page) {
  await selectExamplePr(page, true)
  await pushHostMessage(page, {
    type: 'draftLoaded',
    prKey: 'acme/platform#42',
    prState: 'DRAFT_PRESENT',
    reviewId: 'draft-risky',
    diff: reviewDiff,
    validationDiff: reviewDiff,
    result: {
      summary: [
        'Authentication changes need a careful final review before publishing.',
        'Confirm the trust boundary, stale anchor, and stated evidence.',
        'This deliberately exercises the longest supported submit confirmation.',
      ].join('\n\n'),
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
}

test('setup recovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, { type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Authenticate GitHub CLI to continue.' })
  await expectViewportFilled(page)
  await expect(page).toHaveScreenshot('setup-dark.png')
})

test('narrow setup keeps every recovery action reachable without horizontal overflow', async ({ page }) => {
  for (const width of [320, 400]) {
    await page.setViewportSize({ width, height: 568 })
    await page.goto('/')
    await pushHostMessage(page, { type: 'themeChanged', theme: width === 320 ? 'dark' : 'light' })
    await pushHostMessage(page, {
      type: 'setupRequired',
      reason: 'gh_not_authenticated',
      detail: 'Authenticate GitHub CLI to continue.',
      providerReadiness: {
        provider: 'claude',
        available: false,
        detail: 'Claude Code authentication must be repaired before reviews can run.',
        authCommand: 'claude auth login',
        authenticationStatus: 'unavailable',
      },
    })

    const main = page.getByRole('main', { name: 'PR Pilot setup' })
    const title = page.getByRole('heading', { name: 'GitHub not connected' })
    await expect(title).toBeInViewport()
    await expectNoHorizontalOverflow(page, 'main[aria-label="PR Pilot setup"]')

    if (width === 320) {
      await expect(page).toHaveScreenshot('setup-narrow-dark.png')
    }

    const finalAction = page.getByRole('button', { name: 'Check status' })
    for (let press = 0; press < 20 && !(await finalAction.evaluate((element) => element === document.activeElement)); press += 1) {
      await page.keyboard.press('Tab')
    }
    await expect(finalAction).toBeFocused()
    await expect(finalAction).toBeInViewport()
    expect(await main.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
  }
})

test('setup remains usable at 200% zoom', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, {
    type: 'setupRequired',
    reason: 'gh_not_authenticated',
    detail: 'Authenticate GitHub CLI to continue.',
    providerReadiness: {
      provider: 'claude',
      available: false,
      detail: 'Claude Code authentication must be repaired before reviews can run.',
      authCommand: 'claude auth login',
      authenticationStatus: 'unavailable',
    },
  })
  await page.evaluate(() => {
    document.documentElement.style.zoom = '2'
  })

  const main = page.getByRole('main', { name: 'PR Pilot setup' })
  await expect(page.getByRole('heading', { name: 'GitHub not connected' })).toBeInViewport()
  await expectNoHorizontalOverflow(page, 'main[aria-label="PR Pilot setup"]')

  const finalAction = page.getByRole('button', { name: 'Check status' })
  for (let press = 0; press < 20 && !(await finalAction.evaluate((element) => element === document.activeElement)); press += 1) {
    await page.keyboard.press('Tab')
  }
  await expect(finalAction).toBeFocused()
  await expect(finalAction).toBeInViewport()
  expect(await main.evaluate((element) => element.scrollTop)).toBeGreaterThan(0)
})

test('populated discovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'light' })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr, { ...examplePr, number: 43, title: 'Add long translated review workflow guidance', isDraft: true }] })
  await expectViewportFilled(page)
  await expect(page).toHaveScreenshot('discovery-light.png')
})

test('narrow pseudo-localized layout', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/?locale=pseudo')
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expect(page).toHaveScreenshot('narrow-pseudo.png')
})

test('PR-list toolbar adapts to minimum, default, and wide containers', async ({ page }) => {
  await page.setViewportSize({ width: 1_200, height: 720 })
  for (const [paneWidth, theme, locale] of [
    [220, 'light', 'pseudo'],
    [280, 'dark', 'en'],
    [340, 'highContrastDark', 'en'],
    [420, 'light', 'en'],
  ] as const) {
    await page.goto(`/?locale=${locale}`)
    await page.evaluate((width) => localStorage.setItem('claude-reviews:divider-width', String(width)), paneWidth)
    await page.reload()
    await pushHostMessage(page, { type: 'themeChanged', theme })
    await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })

    const shell = page.getByTestId('pr-list-shell')
    const toolbar = page.getByTestId('pr-list-toolbar')
    const labels = toolbar.locator('.pr-list-toolbar-label')
    const settings = toolbar.locator('button').nth(0)
    const refresh = toolbar.locator('button').nth(1)
    await expect(shell).toHaveCSS('width', `${paneWidth}px`)
    await expectNoHorizontalOverflow(page, '[data-testid="pr-list-toolbar"]')

    for (const action of [settings, refresh]) {
      const box = await action.boundingBox()
      expect(box).not.toBeNull()
      expect(box!.width).toBeGreaterThanOrEqual(24)
      expect(box!.height).toBeGreaterThanOrEqual(24)
    }
    if (paneWidth <= 280) {
      await expect(labels.first()).toBeHidden()
    } else {
      await expect(labels.first()).toBeVisible()
    }

    if (paneWidth === 220) {
      await settings.focus()
      await expect(page.getByRole('tooltip')).toBeVisible()
      await page.keyboard.press('Escape')
      await expect(shell).toHaveScreenshot('pr-toolbar-min-pseudo.png')
    }
    if (paneWidth === 280) {
      await expect(shell).toHaveScreenshot('pr-toolbar-default-dark.png')
    }
  }

  await page.setViewportSize({ width: 520, height: 720 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expect(page.getByTestId('pr-list-toolbar').locator('.pr-list-toolbar-label').first()).toBeVisible()
  await expectNoHorizontalOverflow(page, '[data-testid="pr-list-toolbar"]')
})

test('no-draft hierarchy keeps the primary action ahead of optional overrides', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/?locale=pseudo')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openNoDraftReview(page)

  const generate = page.getByTestId('generate-review')
  const disclosure = page.getByTestId('review-overrides-disclosure').locator(':scope > summary')
  const [generateBox, disclosureBox] = await Promise.all([generate.boundingBox(), disclosure.boundingBox()])
  expect(generateBox).not.toBeNull()
  expect(disclosureBox).not.toBeNull()
  expect(generateBox!.y).toBeLessThan(disclosureBox!.y)
  await expect(page.locator('#review-focus-areas')).toBeHidden()
  await expect(page).toHaveScreenshot('no-draft-narrow-pseudo-high-contrast.png')
})

test('wide generation stays on a bounded reading rail without exposing provider output', async ({ page }) => {
  await page.setViewportSize({ width: 1_440, height: 900 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openGeneratingReview(page)
  await pushHostMessage(page, {
    type: 'reviewChunk',
    prKey: 'acme/platform#42',
    kind: 'thinking',
    chunk: 'PRIVATE_PROVIDER_REASONING_SENTINEL',
  })
  await pushHostMessage(page, {
    type: 'reviewChunk',
    prKey: 'acme/platform#42',
    kind: 'text',
    chunk: 'RAW_PROVIDER_TEXT_SENTINEL',
  })

  const activity = page.getByRole('region', { name: 'Review generation activity' })
  const geometry = await activity.evaluate((element) => {
    const bounds = element.getBoundingClientRect()
    return { width: bounds.width, x: bounds.x }
  })
  expect(geometry.width).toBeLessThanOrEqual(896)
  await expect(page.getByText('PRIVATE_PROVIDER_REASONING_SENTINEL')).toHaveCount(0)
  await expect(page.getByText('RAW_PROVIDER_TEXT_SENTINEL')).toHaveCount(0)
  await expect(activity.getByRole('button', { name: 'Stop generation' })).toBeVisible()
  await expect(page).toHaveScreenshot('review-generation-wide-dark.png')
})

test('long generation activity remains usable in a narrow high-contrast host', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openGeneratingReview(page, 16)

  const activity = page.getByRole('region', { name: 'Review generation activity' })
  const entries = activity.getByRole('region', { name: 'Review activity entries' })
  await expectNoHorizontalOverflow(page, '[data-testid="review-pane-shell"]')
  expect(await entries.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true)
  await entries.focus()
  await expect(entries).toBeFocused()
  await expect(page).toHaveScreenshot('review-generation-long-narrow-high-contrast.png')
})

test('generation failure puts recovery first in a narrow dark host', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 600 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openGeneratingReview(page, 5)
  await pushHostMessage(page, {
    type: 'reviewError',
    prKey: 'acme/platform#42',
    message: 'Provider failed. Check credentials and retry.',
  })

  const [alertBox, activityBox, instructionsBox] = await Promise.all([
    page.getByRole('alert').boundingBox(),
    page.getByRole('region', { name: 'Review generation activity' }).boundingBox(),
    page.getByTestId('review-overrides-disclosure').boundingBox(),
  ])
  expect(alertBox).not.toBeNull()
  expect(activityBox).not.toBeNull()
  expect(instructionsBox).not.toBeNull()
  expect(alertBox!.y).toBeLessThan(activityBox!.y)
  expect(activityBox!.y).toBeLessThan(instructionsBox!.y)
  await expect(page.getByRole('button', { name: 'Try Again' })).toBeVisible()
  await expect(page).toHaveScreenshot('review-generation-failed-narrow-dark.png')
})

test('longest risky-submit dialog stays inside a 320x568 viewport and traps focus', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openLongestRiskySubmit(page)

  const dialog = page.getByRole('alertdialog')
  const title = page.getByRole('heading', { name: 'Submit comment?' })
  const cancel = page.getByRole('button', { name: 'Cancel' })
  const submit = page.getByRole('button', { name: 'Submit Comment' })
  await expect(dialog).toContainText('3 unresolved trust risks')
  const geometry = await dialog.evaluate((element) => {
    const box = element.getBoundingClientRect()
    return {
      x: box.x,
      y: box.y,
      right: box.right,
      bottom: box.bottom,
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      scrollTop: element.scrollTop,
    }
  })
  expect(geometry.x).toBeGreaterThanOrEqual(16)
  expect(geometry.y).toBeGreaterThanOrEqual(16)
  expect(geometry.right).toBeLessThanOrEqual(304)
  expect(geometry.bottom).toBeLessThanOrEqual(552)
  expect(geometry.scrollHeight).toBeGreaterThan(geometry.clientHeight)
  expect(geometry.scrollTop).toBe(0)
  await expect(title).toBeInViewport()
  await expect(page).toHaveScreenshot('risky-submit-narrow-dark.png')

  await page.getByRole('checkbox', { name: /I reviewed these unresolved trust risks/ }).check()
  await cancel.focus()
  await page.keyboard.press('Tab')
  await expect(submit).toBeFocused()
  await expect(submit).toBeInViewport()
  await page.keyboard.press('Tab')
  expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true)
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
})

test('toast theme follows the host when OS and host themes disagree', async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 600 })
  await page.emulateMedia({ colorScheme: 'light' })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openNoDraftReview(page, '')
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  await page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' }).check()
  await page.getByRole('button', { name: 'Generate Review' }).click()

  const toaster = page.locator('[data-sonner-toaster]')
  const toast = page.locator('[data-sonner-toast]')
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'dark')
  await expect(toast).toContainText('Chunked mode needs a loaded diff')
  await toast.hover()
  await expect(page).toHaveScreenshot('toast-host-dark-os-light.png')

  await page.emulateMedia({ colorScheme: 'dark' })
  await page.reload()
  await pushHostMessage(page, { type: 'themeChanged', theme: 'light' })
  await openNoDraftReview(page, '')
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  await page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' }).check()
  await page.getByRole('button', { name: 'Generate Review' }).click()
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'light')

  await page.reload()
  await pushHostMessage(page, { type: 'themeChanged', theme: 'highContrastDark' })
  await openNoDraftReview(page, '')
  await page.getByText('Review instructions (optional)').click()
  await page.getByText('Advanced review options').click()
  await page.getByRole('checkbox', { name: 'Use chunked review mode as an advanced fallback' }).check()
  await page.getByRole('button', { name: 'Generate Review' }).click()
  await expect(toaster).toHaveAttribute('data-sonner-theme', 'dark')
  await expect(toast).toContainText('Chunked mode needs a loaded diff')
  await expect.poll(() => toaster.evaluate((element) => {
    const style = getComputedStyle(element)
    const background = style.getPropertyValue('--background').trim()
    return style.getPropertyValue('--error-bg').trim() === `hsl(${background})`
  })).toBe(true)
  await expect.poll(() => toast.evaluate((element) => {
    const toastStyle = getComputedStyle(element)
    const hostStyle = getComputedStyle(document.body)
    return toastStyle.backgroundColor === hostStyle.backgroundColor
      && toastStyle.borderColor === hostStyle.color
      && toastStyle.color === hostStyle.color
  })).toBe(true)
  await toast.hover()
  await expect(page).toHaveScreenshot('toast-host-high-contrast-dark.png')
})

test('selected review and chat remain usable in a narrow dark host', async ({ page }) => {
  await page.setViewportSize({ width: 400, height: 568 })
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Chat' }).click()

  await expect(page.getByRole('region', { name: 'Chat' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  await expectNoHorizontalOverflow(page, '[data-testid="review-pane-shell"]')
  await expect(page).toHaveScreenshot('selected-review-chat-narrow-dark.png')
})

test('Verify with AI keeps review, chat, and footer usable in a constrained viewport', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 500 })
  await page.goto('/')
  await openDraftReview(page)

  await page.getByRole('button', { name: 'Verify with AI' }).click()
  const expected = buildVerifyCommentPrompt(reviewComment, reviewDiff)
  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'askClaude')
  })).toEqual([expect.objectContaining({
    protocolVersion: 1,
    type: 'askClaude',
    operationId: expect.any(String),
    context: expected.context,
    question: expected.question,
  })])

  const chat = page.getByRole('region', { name: 'Chat' })
  const body = page.getByTestId('review-scroll-body')
  const chatPanel = page.getByTestId('chat-panel')
  const savedButton = page.getByRole('button', { name: 'Saved' })
  await expect(chat).toBeVisible()
  await expect(body).toBeVisible()
  await expect(savedButton).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  await expect(page.getByTestId('chat-messages')).toContainText('Verify whether the draft review comment is supported by the pull-request evidence.')
  await expect(page.getByTestId('chat-messages')).toContainText('"verdict":"valid|invalid|unclear"')
  await expect(page.getByTestId('chat-messages')).toContainText('"evidence":["relative/path:line or symbol"]')
  await expect(page.getByTestId('chat-messages')).toContainText('"action":"keep|revise|delete"')
  await expect(chat).toContainText('Context: draft comment, diff excerpt, PR worktree (read-only)')

  const geometry = await Promise.all([body.boundingBox(), chatPanel.boundingBox(), savedButton.boundingBox()])
  expect(geometry.every((box) => box !== null)).toBe(true)
  const [bodyBox, chatBox, savedBox] = geometry
  expect(bodyBox!.height).toBeGreaterThan(0)
  expect(bodyBox!.y + bodyBox!.height).toBeLessThanOrEqual(chatBox!.y + 1)
  expect(chatBox!.y + chatBox!.height).toBeLessThanOrEqual(savedBox!.y + 1)
  expect(savedBox!.y + savedBox!.height).toBeLessThanOrEqual(501)
})

test('wide tall Verify layout fills the viewport instead of collapsing to content height', async ({ page }) => {
  await page.setViewportSize({ width: 2_048, height: 1_180 })
  await page.goto('/')
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Verify with AI' }).click()

  await expectViewportFilled(page)
  const prList = page.getByTestId('pr-list-shell').locator('nav')
  const reviewShell = page.getByTestId('review-pane-shell')
  const reviewContent = page.getByTestId('review-pane-content')
  const body = page.getByTestId('review-scroll-body')
  const savedButton = reviewContent.getByRole('button', { name: 'Saved', exact: true })
  await expect(prList).toBeVisible()
  await expect(reviewContent).toBeVisible()
  await expect(savedButton).toBeVisible()

  const geometry = await Promise.all([
    prList.boundingBox(),
    reviewShell.boundingBox(),
    reviewContent.boundingBox(),
    body.boundingBox(),
    savedButton.boundingBox(),
  ])
  expect(geometry.every((box) => box !== null)).toBe(true)
  const [prListBox, reviewShellBox, reviewContentBox, bodyBox, savedBox] = geometry
  expect(prListBox!.height).toBe(1_180)
  expect(reviewShellBox!.height).toBe(1_180)
  expect(reviewContentBox!.height).toBe(1_180)
  expect(bodyBox!.height).toBeGreaterThan(600)
  expect(savedBox!.y).toBeGreaterThan(1_100)
  expect(savedBox!.y + savedBox!.height).toBeLessThanOrEqual(1_181)
})

test('narrow selected review keeps Save and submit controls reachable', async ({ page }) => {
  for (const width of [320, 400]) {
    await page.setViewportSize({ width, height: 568 })
    await page.goto('/')
    await openDraftReview(page)

    for (const control of [
      page.getByRole('button', { name: 'Saved', exact: true }),
      page.getByRole('button', { name: 'Comment', exact: true }),
      page.getByRole('button', { name: 'More submit options' }),
    ]) {
      const box = await control.boundingBox()
      expect(box).not.toBeNull()
      expect(box!.x).toBeGreaterThanOrEqual(0)
      expect(box!.x + box!.width).toBeLessThanOrEqual(width)
    }
  }
})

test('persisted tall chat leaves the review body usable in a short viewport', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 500 })
  await page.goto('/')
  await page.evaluate(() => localStorage.setItem('claude-reviews:chat-height', '600'))
  await page.reload()
  await openDraftReview(page)
  await page.getByRole('button', { name: 'Chat' }).click()

  await expect.poll(async () => (await page.getByTestId('review-scroll-body').boundingBox())?.height ?? 0)
    .toBeGreaterThanOrEqual(MIN_REVIEW_BODY_HEIGHT)
  const body = await page.getByTestId('review-scroll-body').boundingBox()
  const chat = await page.getByTestId('chat-panel').boundingBox()
  expect(body).not.toBeNull()
  expect(chat).not.toBeNull()
  expect(body!.height).toBeGreaterThanOrEqual(MIN_REVIEW_BODY_HEIGHT)
  expect(chat!.height).toBeGreaterThan(0)
})

test('Suggest fix with AI sends an example-fix prompt with focused diff context', async ({ page }) => {
  await page.goto('/')
  await openDraftReview(page)

  await page.getByRole('button', { name: 'Suggest fix with AI' }).click()
  const expected = buildExampleFixPrompt(reviewComment, reviewDiff)
  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'askClaude')
  })).toEqual([expect.objectContaining({
    protocolVersion: 1,
    type: 'askClaude',
    operationId: expect.any(String),
    context: expected.context,
    question: expected.question,
  })])
})

test('clicking the gutter + button adds a new inline comment', async ({ page }) => {
  await page.goto('/')
  await openDraftReview(page)

  const gutterButton = page.getByRole('button', { name: 'Add comment on src/auth.ts, new line 1' })
  await expect(gutterButton).toBeVisible()
  await gutterButton.click()

  const commentBox = page.getByRole('textbox', { name: 'Comment on src/auth.ts, line 1' })
  await expect(commentBox).toBeVisible()
  await commentBox.fill('New inline note')
  await page.getByRole('button', { name: 'Add', exact: true }).click()

  await expect(page.getByText('New inline note')).toBeVisible()
})
