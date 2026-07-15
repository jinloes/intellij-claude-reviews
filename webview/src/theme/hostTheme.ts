import type { HostTheme } from '@/bridge/types'

const THEME_CLASSES = ['dark', 'high-contrast']

export function applyHostTheme(theme: HostTheme): void {
  const root = document.documentElement
  root.classList.remove(...THEME_CLASSES)
  const dark = theme === 'dark' || theme === 'highContrastDark'
  const highContrast = theme === 'highContrastLight' || theme === 'highContrastDark'
  if (dark) root.classList.add('dark')
  if (highContrast) root.classList.add('high-contrast')
  root.dataset.theme = theme
  root.style.colorScheme = dark ? 'dark' : 'light'
}
