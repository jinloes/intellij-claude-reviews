import React, { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'
import { I18nProvider, localeFromLocation } from './i18n/I18nProvider'
import { applyHostTheme } from './theme/hostTheme'

applyHostTheme(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')

// Dev-only a11y diagnostics in browser console.
if (import.meta.env.DEV) {
  void import('@axe-core/react').then(({ default: axe }) => {
    void axe(React, createRoot, 1000)
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider locale={localeFromLocation()}>
      <App />
    </I18nProvider>
  </StrictMode>,
)
