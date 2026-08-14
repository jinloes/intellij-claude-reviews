import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { englishMessages, type MessageId } from './messages.en'
import { formatMessage, pseudoLocalize, type MessageValues } from './format'

export type AppLocale = 'en' | 'pseudo'

type Translate = (id: MessageId, values?: MessageValues) => string

const I18nContext = createContext<Translate>((id) => englishMessages[id])

function shouldPseudoLocalizeText(node: Text): boolean {
  const parent = node.parentElement
  return Boolean(
    node.data.trim()
    && parent
    && !['CODE', 'PRE', 'SCRIPT', 'STYLE', 'TEXTAREA', 'SVG'].includes(parent.tagName),
  )
}

function installPseudoLocalization(root: HTMLElement): () => void {
  const localizedTextNodes = new Map<Text, string>()
  const localizedAttributes = new Map<Element, Map<string, string>>()
  let applying = false

  const localizeText = (node: Text) => {
    if (!shouldPseudoLocalizeText(node) || node.data.includes('⟦')) return
    localizedTextNodes.set(node, node.data)
    node.data = pseudoLocalize(node.data)
  }
  const localizeElement = (element: Element) => {
    for (const attribute of ['aria-label', 'placeholder', 'title']) {
      const value = element.getAttribute(attribute)
      if (!value || value.includes('⟦')) continue
      const originals = localizedAttributes.get(element) ?? new Map<string, string>()
      originals.set(attribute, value)
      localizedAttributes.set(element, originals)
      element.setAttribute(attribute, pseudoLocalize(value))
    }
  }
  const localizeTree = (target: Node) => {
    if (applying) return
    applying = true
    if (target instanceof Text) localizeText(target)
    if (target instanceof Element) {
      localizeElement(target)
      const walker = document.createTreeWalker(target, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT)
      let node = walker.nextNode()
      while (node) {
        if (node instanceof Text) localizeText(node)
        else if (node instanceof Element) localizeElement(node)
        node = walker.nextNode()
      }
    }
    applying = false
  }

  localizeTree(root)
  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      if (mutation.type === 'characterData') localizeTree(mutation.target)
      for (const node of mutation.addedNodes) localizeTree(node)
    }
  })
  observer.observe(root, { subtree: true, childList: true, characterData: true })
  return () => {
    observer.disconnect()
    for (const [node, value] of localizedTextNodes) if (node.isConnected) node.data = value
    for (const [element, attributes] of localizedAttributes) {
      if (!element.isConnected) continue
      for (const [name, value] of attributes) element.setAttribute(name, value)
    }
  }
}

export function I18nProvider({ children, locale = 'en' }: { children: ReactNode; locale?: AppLocale }) {
  useEffect(() => {
    if (locale !== 'pseudo') return
    const root = document.getElementById('root') ?? document.body
    return installPseudoLocalization(root)
  }, [locale])
  const translate: Translate = (id, values) => {
    const message = formatMessage(englishMessages[id], values)
    return locale === 'pseudo' ? pseudoLocalize(message) : message
  }
  return <I18nContext.Provider value={translate}>{children}</I18nContext.Provider>
}

export function useI18n(): Translate {
  return useContext(I18nContext)
}

export function localeFromLocation(): AppLocale {
  return new URLSearchParams(window.location.search).get('locale') === 'pseudo' ? 'pseudo' : 'en'
}
