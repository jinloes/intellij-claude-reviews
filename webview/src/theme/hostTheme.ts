import type { HostTheme } from '@/bridge/types'

const THEME_CLASSES = ['dark', 'high-contrast']

export function sonnerThemeForHost(theme: HostTheme): 'light' | 'dark' {
  return theme === 'dark' || theme === 'highContrastDark' ? 'dark' : 'light'
}

export function applyHostTheme(theme: HostTheme): void {
  const root = document.documentElement
  root.classList.remove(...THEME_CLASSES)
  const dark = sonnerThemeForHost(theme) === 'dark'
  const highContrast = theme === 'highContrastLight' || theme === 'highContrastDark'
  if (dark) root.classList.add('dark')
  if (highContrast) root.classList.add('high-contrast')
  root.dataset.theme = theme
  root.style.colorScheme = dark ? 'dark' : 'light'
}
