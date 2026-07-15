import { createContext, useContext, type ReactNode } from 'react'
import { englishMessages, type MessageId } from './messages.en'

export type AppLocale = 'en' | 'pseudo'

type Translate = (id: MessageId) => string

const I18nContext = createContext<Translate>((id) => englishMessages[id])

export function pseudoLocalize(message: string): string {
  const expanded = message.replace(/[aeiouAEIOU]/g, (value) => `${value}${value.toLowerCase()}`)
  return `⟦${expanded}~~~⟧`
}

export function I18nProvider({ children, locale = 'en' }: { children: ReactNode; locale?: AppLocale }) {
  const translate: Translate = (id) => locale === 'pseudo' ? pseudoLocalize(englishMessages[id]) : englishMessages[id]
  return <I18nContext.Provider value={translate}>{children}</I18nContext.Provider>
}

export function useI18n(): Translate {
  return useContext(I18nContext)
}

export function localeFromLocation(): AppLocale {
  return new URLSearchParams(window.location.search).get('locale') === 'pseudo' ? 'pseudo' : 'en'
}
