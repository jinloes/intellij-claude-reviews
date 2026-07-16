import { expect, test, type Page } from '@playwright/test'
import { examplePr, installHostFixture, pushHostMessage } from '../a11y/hostFixture'

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

async function openDraftReview(page: Page) {
  const pr = { ...examplePr, hasReviewDraft: true }
  await pushHostMessage(page, { type: 'prListLoaded', prs: [pr] })
  await page.getByRole('button', { name: new RegExp(examplePr.title) }).click()

  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.find((message) => message.type === 'selectPR')
  })).toMatchObject({ type: 'selectPR', number: 42, owner: 'acme', repo: 'platform' })

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

test('setup recovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, { type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Authenticate GitHub CLI to continue.' })
  await expectViewportFilled(page)
  await expect(page).toHaveScreenshot('setup-dark.png')
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

test('Verify with AI keeps review, chat, and footer usable in a constrained viewport', async ({ page }) => {
  await page.setViewportSize({ width: 600, height: 500 })
  await page.goto('/')
  await openDraftReview(page)

  await page.getByRole('button', { name: 'Verify with AI' }).click()
  const expectedQuestion =
    'Verify this review comment on src/auth.ts line 2:\n\n' +
    '> Explain this security behavior.\n\n' +
    'Is this issue actually present in the diff?'
  await expect.poll(() => page.evaluate(() => {
    const fixture = (window as unknown as {
      __hostFixture: { outgoing: Array<Record<string, unknown>> }
    }).__hostFixture
    return fixture.outgoing.filter((message) => message.type === 'askClaude')
  })).toEqual([{ protocolVersion: 1, type: 'askClaude', context: '', question: expectedQuestion }])

  const chat = page.getByRole('region', { name: 'Chat' })
  const body = page.getByTestId('review-scroll-body')
  const chatPanel = page.getByTestId('chat-panel')
  const savedButton = page.getByRole('button', { name: 'Saved' })
  await expect(chat).toBeVisible()
  await expect(body).toBeVisible()
  await expect(savedButton).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Ask about this pull request' })).toBeVisible()
  await expect(page.getByTestId('chat-messages')).toContainText('Verify this review comment on src/auth.ts line 2:')
  await expect(page.getByTestId('chat-messages')).toContainText('Explain this security behavior.')
  await expect(page.getByTestId('chat-messages')).toContainText('Is this issue actually present in the diff?')

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
