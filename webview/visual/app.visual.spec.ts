import { expect, test } from '@playwright/test'
import { examplePr, installHostFixture, pushHostMessage } from '../a11y/hostFixture'

test.beforeEach(async ({ page }) => {
  await installHostFixture(page)
})

test('setup recovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'dark' })
  await pushHostMessage(page, { type: 'setupRequired', reason: 'gh_not_authenticated', detail: 'Authenticate GitHub CLI to continue.' })
  await expect(page).toHaveScreenshot('setup-dark.png')
})

test('populated discovery layout', async ({ page }) => {
  await page.goto('/')
  await pushHostMessage(page, { type: 'themeChanged', theme: 'light' })
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr, { ...examplePr, number: 43, title: 'Add long translated review workflow guidance', isDraft: true }] })
  await expect(page).toHaveScreenshot('discovery-light.png')
})

test('narrow pseudo-localized layout', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/?locale=pseudo')
  await pushHostMessage(page, { type: 'prListLoaded', prs: [examplePr] })
  await expect(page).toHaveScreenshot('narrow-pseudo.png')
})
