function isEditableElement(value: EventTarget | null): boolean {
  if (!(value instanceof HTMLElement)) return false
  if (value instanceof HTMLInputElement || value instanceof HTMLTextAreaElement || value instanceof HTMLSelectElement) {
    return true
  }
  if (value.isContentEditable || value.closest('[contenteditable="true"]')) return true
  const role = value.getAttribute('role')
  return role === 'textbox' || role === 'searchbox' || role === 'combobox'
}

export function isEditableEvent(event: KeyboardEvent): boolean {
  const path = event.composedPath()
  return (path.length > 0 ? path : [event.target]).some((target) => isEditableElement(target))
}

export function shouldFocusPrFilter(event: KeyboardEvent): boolean {
  return event.key === '/'
    && !event.defaultPrevented
    && !event.isComposing
    && !event.metaKey
    && !event.ctrlKey
    && !event.altKey
    && !isEditableEvent(event)
}
