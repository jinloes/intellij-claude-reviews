import { describe, expect, it } from 'vitest'
import { isEditableEvent, shouldFocusPrFilter } from './keyboard'

function keydown(target: HTMLElement, init: KeyboardEventInit = {}): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true, ...init })
  target.dispatchEvent(event)
  return event
}

describe('PR filter shortcut', () => {
  it('focuses search from non-editable chrome', () => {
    expect(shouldFocusPrFilter(keydown(document.body))).toBe(true)
  })

  it.each([
    ['input', document.createElement('input')],
    ['textarea', document.createElement('textarea')],
    ['select', document.createElement('select')],
  ])('does not intercept an editable %s', (_name, element) => {
    document.body.append(element)
    const event = keydown(element)
    expect(isEditableEvent(event)).toBe(true)
    expect(shouldFocusPrFilter(event)).toBe(false)
  })

  it('does not intercept contenteditable descendants', () => {
    const editor = document.createElement('div')
    editor.setAttribute('contenteditable', 'true')
    const child = document.createElement('span')
    editor.append(child)
    document.body.append(editor)
    expect(shouldFocusPrFilter(keydown(child))).toBe(false)
  })

  it('ignores modified and composing shortcuts', () => {
    expect(shouldFocusPrFilter(keydown(document.body, { metaKey: true }))).toBe(false)
    expect(shouldFocusPrFilter(keydown(document.body, { ctrlKey: true }))).toBe(false)
    expect(shouldFocusPrFilter(keydown(document.body, { isComposing: true }))).toBe(false)
  })
})
