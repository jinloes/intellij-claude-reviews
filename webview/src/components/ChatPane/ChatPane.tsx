import { useEffect, useRef, useState } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { AlertTriangle, Check, Loader2, Send, X, XCircle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { onHostMessage, sendToHost, type PR } from '@/bridge/types'
import { LiveStatus } from '../a11y/LiveStatus'
import { useI18n } from '@/i18n/I18nProvider'
import { parseStructuredResult, type ExampleFixResult, type StructuredResult, type VerifyResult } from './structuredResult'

interface Message {
  role: 'user' | 'assistant'
  content: string
  isError?: boolean
}

interface Props {
  pr: PR
  selectedContext?: string
  onContextUsed?: () => void
  pendingMessage?: { q: string; ctx: string; id: number }
  onPendingMessageSent?: () => void
  contextSummary?: string[]
}

function prKey(pr: Pick<PR, 'owner' | 'repo' | 'number'>): string {
  return `${pr.owner}/${pr.repo}#${pr.number}`
}

export function ChatPane({
  pr,
  selectedContext,
  onContextUsed,
  pendingMessage,
  onPendingMessageSent,
  contextSummary = [],
}: Props) {
  const t = useI18n()
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState('')
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const messagesRef = useRef<HTMLDivElement>(null)
  const sentPendingMessageIdRef = useRef<number | null>(null)

  useEffect(() => {
    setMessages([])
    setStreaming('')
    setInput('')
    setBusy(false)
  }, [pr.number, pr.owner, pr.repo])

  useEffect(() => {
    return onHostMessage((msg) => {
      if ('prKey' in msg && msg.prKey && msg.prKey !== prKey(pr)) return

      switch (msg.type) {
        case 'chatChunk':
          setStreaming((s) => s + msg.chunk)
          break
        case 'chatResponse':
          setStreaming('')
          setMessages((prev) => [...prev, { role: 'assistant', content: msg.response }])
          setBusy(false)
          break
        case 'chatError':
          setStreaming('')
          setMessages((prev) => [
            ...prev,
            { role: 'assistant', content: msg.message, isError: true },
          ])
          setBusy(false)
          break
        default:
          break
      }
    })
  }, [pr])

  useEffect(() => {
    if (!pendingMessage || busy || sentPendingMessageIdRef.current === pendingMessage.id) return
    const { q, ctx, id } = pendingMessage
    sentPendingMessageIdRef.current = id
    setMessages((prev) => [...prev, { role: 'user', content: q }])
    setBusy(true)
    onPendingMessageSent?.()
    sendToHost({ type: 'askClaude', context: ctx, question: q })
  }, [pendingMessage, busy, onPendingMessageSent])

  useEffect(() => {
    const messagesElement = messagesRef.current
    if (messagesElement) {
      messagesElement.scrollTop = messagesElement.scrollHeight
    }
  }, [messages.length, busy])

  function handleClear() {
    setMessages([])
    setStreaming('')
    setBusy(false)
    sendToHost({ type: 'clearChat' })
  }

  function handleSend() {
    const q = input.trim()
    if (!q || busy) return
    const ctx = selectedContext ?? ''
    setMessages((prev) => [...prev, { role: 'user', content: q }])
    setInput('')
    setBusy(true)
    onContextUsed?.()
    sendToHost({ type: 'askClaude', context: ctx, question: q })
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  const hasContent = messages.length > 0 || !!streaming || busy

  return (
    <section className="flex flex-1 min-h-0 flex-col border-t border-border bg-card" aria-labelledby="chat-heading">
      <LiveStatus message={busy ? 'AI response in progress' : streaming ? 'AI response started' : ''} />
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-border shrink-0">
        <h2 id="chat-heading" className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Chat
        </h2>
        {contextSummary.length > 0 && (
          <span className="min-w-0 flex-1 truncate px-2 text-[11px] text-muted-foreground">
            Context: {contextSummary.join(', ')}
          </span>
        )}
        {hasContent && (
          <Button variant="ghost" size="sm" onClick={handleClear} className="h-6 px-2 text-xs">
            Clear
          </Button>
        )}
      </div>

      {/* Keep this flexible region mounted so an empty chat anchors its composer to the panel bottom. */}
      <div ref={messagesRef} data-testid="chat-messages" className="flex-1 min-h-0 overflow-y-auto p-3 space-y-3">
        {hasContent && (
          <>
          {messages.map((m, i) => {
            const structured = m.role === 'assistant' && !m.isError ? parseStructuredResult(m.content) : null
            return (
              <div key={i} className={cn('flex flex-col gap-1', m.role === 'user' ? 'items-end' : 'items-start')}>
                <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">
                  {m.role === 'user' ? 'you' : 'ai'}
                </span>
                {structured ? (
                  <StructuredResultCard result={structured} />
                ) : (
                  <div
                    className={cn(
                      'rounded-md px-3 py-2 text-sm max-w-[90%]',
                      m.role === 'user'
                        ? 'bg-primary text-primary-foreground'
                        : m.isError
                          ? 'bg-destructive/20 text-destructive border border-destructive/40'
                          : 'bg-secondary text-secondary-foreground',
                    )}
                  >
                    {m.isError ? (
                      m.content
                    ) : (
                      <div className={cn(
                        'prose prose-sm max-w-none [&_code]:font-mono [&_code]:text-xs [&_code]:px-1 [&_code]:rounded [&_p]:my-0.5 [&_ul]:my-1 [&_li]:my-0 [&_pre]:my-1 [&_pre]:p-2 [&_pre]:rounded [&_blockquote]:border-l-2 [&_blockquote]:pl-2 [&_blockquote]:italic',
                        m.role === 'user'
                          ? 'prose-invert [&_code]:bg-primary-foreground/20 [&_pre]:bg-primary-foreground/20 [&_blockquote]:border-primary-foreground/40 [&_a]:text-primary-foreground'
                          : 'prose-invert [&_code]:bg-background/50 [&_pre]:bg-background/50 [&_blockquote]:border-muted-foreground/40 [&_a]:text-primary',
                      )}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.content}</ReactMarkdown>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}


          {busy && !streaming && (
            <div className="flex flex-col gap-1 items-start">
              <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">ai</span>
              <div className="bg-secondary rounded-md px-3 py-2 flex gap-1">
                {[0, 200, 400].map((delay) => (
                  <span
                    key={delay}
                    className="w-1.5 h-1.5 rounded-full bg-muted-foreground animate-bounce"
                    style={{ animationDelay: `${delay}ms` }}
                  />
                ))}
              </div>
            </div>
          )}

          {streaming && (
            <div className="flex flex-col gap-1 items-start">
              <span className="text-[10px] font-medium tracking-widest uppercase text-muted-foreground px-1">ai</span>
              <div className="bg-secondary text-secondary-foreground rounded-md px-3 py-2 text-sm max-w-[90%]">
                <div className="prose prose-sm prose-invert max-w-none [&_code]:font-mono [&_code]:text-xs [&_code]:bg-background/50 [&_code]:px-1 [&_code]:rounded [&_p]:my-0.5">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{streaming}</ReactMarkdown>
                </div>
                <span className="inline-block w-2 h-3.5 bg-primary animate-pulse ml-0.5 align-text-bottom" />
              </div>
            </div>
          )}
          </>
        )}
      </div>

      {/* Selected context badge */}
      {selectedContext && (
        <div className="mx-3 mb-1 flex items-center gap-2 rounded border border-border bg-muted/50 px-2 py-1">
          <span className="flex-1 truncate text-xs text-muted-foreground">{selectedContext}</span>
          <Button
            variant="ghost"
            size="sm"
            onClick={onContextUsed}
            className="h-6 w-6 p-0 shrink-0"
            aria-label="Clear selected context"
          >
            <X className="w-3 h-3" />
          </Button>
        </div>
      )}

      {/* Input */}
      <div className="flex gap-2 p-3 pt-2 shrink-0">
        <label htmlFor="pr-chat-input" className="sr-only">{t('chat.input')}</label>
        <Textarea
          id="pr-chat-input"
          className="min-h-[60px] resize-none text-sm bg-background border-input focus-visible:ring-ring"
          placeholder="Ask about this PR…"
          title="Enter to send · Shift+Enter for newline"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          rows={2}
          disabled={busy}
        />
        <Button
          size="sm"
          onClick={handleSend}
          disabled={busy || !input.trim()}
          className="self-end shrink-0"
          title="Send (Enter)"
          aria-label="Send"
        >
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
        </Button>
      </div>
      <p className="px-3 pb-2 text-[11px] text-muted-foreground">
        Uses the active PR context shown above · right-click selected text to ask about it
      </p>
    </section>
  )
}

const VERDICT_STYLE: Record<VerifyResult['verdict'], { label: string; className: string; Icon: typeof Check }> = {
  valid: { label: 'Valid', className: 'text-status-approve border-status-approve/50 bg-status-approve/10', Icon: Check },
  invalid: { label: 'Invalid', className: 'text-status-changes border-status-changes/50 bg-status-changes/10', Icon: XCircle },
  unclear: { label: 'Unclear', className: 'text-status-suggestion border-status-suggestion/50 bg-status-suggestion/10', Icon: AlertTriangle },
}

const ACTION_LABEL: Record<VerifyResult['action'], string> = {
  keep: 'Keep as-is',
  revise: 'Revise',
  delete: 'Delete',
}

function StructuredResultCard({ result }: { result: StructuredResult }) {
  return (
    <div className="w-full max-w-[90%] rounded-md border border-border bg-secondary text-secondary-foreground overflow-hidden">
      {result.kind === 'verify' ? <VerifyResultCard result={result} /> : <ExampleFixResultCard result={result} />}
    </div>
  )
}

function VerifyResultCard({ result }: { result: VerifyResult }) {
  const { label, className, Icon } = VERDICT_STYLE[result.verdict]
  return (
    <div className="p-3 space-y-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className={cn('inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-[11px] font-medium', className)}>
          <Icon className="w-3 h-3" />
          {label}
        </span>
        <span className="text-[11px] text-muted-foreground">Suggested action: {ACTION_LABEL[result.action]}</span>
      </div>
      <p className="whitespace-pre-wrap">{result.why}</p>
      {result.action === 'revise' && result.replacementComment && (
        <div>
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Suggested replacement</p>
          <p className="whitespace-pre-wrap rounded bg-background/50 px-2 py-1.5 text-sm">{result.replacementComment}</p>
        </div>
      )}
    </div>
  )
}

function TextList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div>
      <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">{title}</p>
      <ul className="list-disc space-y-0.5 pl-4">
        {items.map((item, i) => (
          <li key={i}>{item}</li>
        ))}
      </ul>
    </div>
  )
}

function ExampleFixResultCard({ result }: { result: ExampleFixResult }) {
  return (
    <div className="p-3 space-y-2.5 text-sm">
      <TextList title="Approach" items={result.approach} />
      {result.examplePatch && (
        <div>
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Example patch</p>
          <div className="prose prose-sm prose-invert max-w-none [&_code]:font-mono [&_code]:text-xs [&_pre]:my-0 [&_pre]:bg-background/50 [&_pre]:p-2 [&_pre]:rounded">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{result.examplePatch}</ReactMarkdown>
          </div>
        </div>
      )}
      <div>
        <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1">Why</p>
        <p className="whitespace-pre-wrap">{result.why}</p>
      </div>
      <TextList title="Risks" items={result.risks} />
      <TextList title="Test updates" items={result.testUpdates} />
      <TextList title="Missing context" items={result.missingContext} />
    </div>
  )
}


