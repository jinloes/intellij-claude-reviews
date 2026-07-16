export const CHAT_HEIGHT_KEY = 'claude-reviews:chat-height'
export const MIN_CHAT_HEIGHT = 200
export const MAX_CHAT_HEIGHT = 600
export const DEFAULT_CHAT_HEIGHT = 240

export function loadChatHeight(storage: Pick<Storage, 'getItem'> = localStorage): number {
  const saved = Number(storage.getItem(CHAT_HEIGHT_KEY))
  return saved >= MIN_CHAT_HEIGHT && saved <= MAX_CHAT_HEIGHT ? saved : DEFAULT_CHAT_HEIGHT
}

