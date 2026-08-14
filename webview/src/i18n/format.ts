export type MessageValues = Record<string, string | number>

export function pseudoLocalize(message: string): string {
  const expanded = message.replace(/[aeiouAEIOU]/g, (value) => `${value}${value.toLowerCase()}`)
  return `⟦${expanded}~~~⟧`
}

export function formatMessage(message: string, values: MessageValues = {}): string {
  return message.replace(/\{(\w+)\}/g, (match, key: string) => String(values[key] ?? match))
}


