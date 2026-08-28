import { forwardRef, useImperativeHandle } from 'react'
import { ChevronDown, ChevronUp, ExternalLink, MessageSquare } from 'lucide-react'
import type { PR, ReviewResult } from '../../bridge/types'
import { Button } from '@/components/ui/button'
import {
  ContextMenu,
  ContextMenuContent,
  ContextMenuItem,
  ContextMenuLabel,
  ContextMenuSeparator,
  ContextMenuTrigger,
} from '@/components/ui/context-menu'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { ChatPane } from '../ChatPane'
import { LiveStatus } from '../a11y/LiveStatus'
import { AccessibleResizer } from '../layout/AccessibleResizer'
import { chatHeightBounds } from './chatHeight'
import { ReviewActivityLog } from './ReviewActivityLog'
import { PaneContent } from './ReviewContent'
import { ReviewFooter } from './ReviewFooter'
import { ReviewOverrides } from './ReviewOverrides'
import { QualityCheckBadge, ReviewQualityCheckCard } from './ReviewQuality'
import { useReviewController } from './useReviewController'

interface Props {
  pr: PR | null
  onDirtyStateChange?: (dirty: boolean) => void
}

export interface ReviewPaneHandle {
  discardPendingChanges: () => boolean
}

const VERDICT_COLOR: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'text-status-approve',
  REQUEST_CHANGES: 'text-status-changes',
  COMMENT: 'text-status-comment',
}

const VERDICT_LABEL: Record<ReviewResult['verdict'], string> = {
  APPROVE: 'Approve',
  REQUEST_CHANGES: 'Request Changes',
  COMMENT: 'Comment',
}

export const ReviewPane = forwardRef<ReviewPaneHandle, Props>(function ReviewPane(
  { pr, onDirtyStateChange },
  ref,
) {
  const { model, actions, refs } = useReviewController({ pr, onDirtyStateChange })

  useImperativeHandle(
    ref,
    () => ({ discardPendingChanges: actions.discardPendingChanges }),
    [actions.discardPendingChanges],
  )

  if (!pr) {
    return (
      <div className="flex min-h-0 flex-1 items-center justify-center bg-background">
        <span className="text-sm text-muted-foreground italic">← select a pull request</span>
      </div>
    )
  }

  const commentCount = model.inlineComments.length
  const orphanCount = model.orphanComments.length
  const totalCount = commentCount + orphanCount

  const reviewOverrides = model.showReviewOverrides ? (
    <ReviewOverrides
      focusAreas={model.focusAreasOverride}
      customInstructions={model.customInstructionsOverride}
      chunkedMode={model.chunkedMode}
      preflight={model.preflight}
      recommendation={model.recommendation}
      onFocusAreasChange={actions.setFocusAreasOverride}
      onCustomInstructionsChange={actions.setCustomInstructionsOverride}
      onChunkedModeChange={actions.setChunkedMode}
    />
  ) : null

  const paneContent = (
    <PaneContent
      state={model.state}
      focusedCommentIdx={model.focusedCommentIdx}
      onGenerate={actions.generate}
      onVerifyComment={model.hasReview ? actions.verifyComment : undefined}
      onSuggestFixComment={model.hasReview ? actions.suggestFixComment : undefined}
      editCommentHandlers={actions.editCommentHandlers}
      inlineComments={model.inlineComments}
      orphanComments={model.orphanComments}
      onEditOrphan={actions.orphanHandlers.onEditOrphan}
      onDeleteOrphan={actions.orphanHandlers.onDeleteOrphan}
      onReloadDraft={actions.reloadDraft}
      onRetryDelete={actions.deleteDraft}
      onKeepDraft={actions.keepDraft}
      onReanchor={actions.reanchorDraft}
      onOpenSettings={actions.openSettings}
      onOpenAuthGuide={actions.openAuthGuide}
    />
  )

  return (
    <TooltipProvider delayDuration={400}>
      <div ref={refs.paneRef} data-testid="review-pane-content" className="flex min-h-0 flex-1 flex-col bg-background">
        <LiveStatus message={model.statusMessage} />
        <div className="shrink-0 px-4 py-2.5 border-b border-border bg-card">
          <div className="flex items-center gap-2 min-w-0">
            <span className="font-mono text-xs text-muted-foreground shrink-0">#{pr.number}</span>
            <span className="text-sm font-medium truncate flex-1" title={pr.title}>{pr.title}</span>

            {commentCount > 0 && (
              <div className="flex items-center gap-0.5 shrink-0">
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={actions.focusPreviousComment}
                  disabled={model.focusedCommentIdx <= 0}
                  aria-label="Previous comment"
                >
                  <ChevronUp className="w-3.5 h-3.5" />
                </Button>
                <span className="text-xs text-muted-foreground font-mono px-0.5 tabular-nums">
                  {model.focusedCommentIdx + 1}/{commentCount}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0"
                  onClick={actions.focusNextComment}
                  disabled={model.focusedCommentIdx >= commentCount - 1}
                  aria-label="Next comment"
                >
                  <ChevronDown className="w-3.5 h-3.5" />
                </Button>
              </div>
            )}

            {model.showChat && (
              <Button
                variant={model.chatVisible ? 'secondary' : 'ghost'}
                size="sm"
                className="h-6 px-2 text-xs shrink-0 gap-1.5"
                onClick={actions.toggleChat}
                title={model.chatVisible ? 'Collapse chat' : 'Open chat'}
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Chat
              </Button>
            )}

            {model.showChat && model.selectedContext && !model.chatVisible && (
              <Button
                variant="secondary"
                size="sm"
                className="h-6 px-2 text-xs shrink-0 gap-1.5"
                onClick={actions.openChat}
                title="Open chat with selected text attached"
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Ask selection
              </Button>
            )}

            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  className="h-6 w-6 p-0 shrink-0 text-muted-foreground"
                  onClick={actions.openPr}
                  aria-label="Open PR on GitHub"
                >
                  <ExternalLink className="w-3.5 h-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Open on GitHub</TooltipContent>
            </Tooltip>
          </div>

          <div className="flex items-center gap-1.5 mt-1 text-xs">
            {model.hasReview && model.result ? (
              <>
                <span className={cn('font-mono font-semibold tracking-wide', VERDICT_COLOR[model.result.verdict])}>
                  {VERDICT_LABEL[model.result.verdict]}
                </span>
                {totalCount > 0 && (
                  <>
                    <span className="text-muted-foreground">·</span>
                    <span className="text-muted-foreground">
                      {totalCount} comment{totalCount !== 1 ? 's' : ''}
                    </span>
                  </>
                )}
                {orphanCount > 0 && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <span className="text-status-suggestion font-mono cursor-help">· {orphanCount} unanchored</span>
                    </TooltipTrigger>
                    <TooltipContent>
                      Comment{orphanCount !== 1 ? 's' : ''} GitHub cannot attach inline — line is outside the PR's hunks.
                    </TooltipContent>
                  </Tooltip>
                )}
                {model.state.kind === 'reviewUnsaved' && (
                  <span className="text-status-suggestion font-mono">· unsaved</span>
                )}
              </>
            ) : (
              <span className="font-mono text-muted-foreground">{pr.owner}/{pr.repo}</span>
            )}
          </div>
        </div>

        <ContextMenu>
          <ContextMenuTrigger asChild>
            <div ref={refs.reviewBodyRef} data-testid="review-scroll-body" className="flex-1 overflow-y-auto min-h-0">
              {model.state.kind === 'noDraft' && paneContent}
              {reviewOverrides}
              {model.state.kind === 'generating' && paneContent}
              {model.activity.outcome !== 'idle' && (
                <div className="px-4 pt-3">
                  <ReviewActivityLog activity={model.activity} />
                </div>
              )}
              {model.result && model.qualityReport && model.qualityRiskCount > 0 && !model.qualityExpanded && (
                <div className="px-4 pt-3">
                  <QualityCheckBadge count={model.qualityRiskCount} onReview={actions.runQualityCheck} />
                </div>
              )}
              {model.result && model.qualityReport && model.qualityExpanded && (
                <div className="px-4 pt-3">
                  <ReviewQualityCheckCard
                    report={model.qualityReport}
                    onApplyRepair={actions.applyQualityRepair}
                    onCollapse={actions.collapseQualityCheck}
                  />
                </div>
              )}
              {model.state.kind !== 'noDraft' && model.state.kind !== 'generating' && paneContent}
            </div>
          </ContextMenuTrigger>
          <ContextMenuContent>
            {model.selectedContext ? (
              <>
                <ContextMenuLabel className="text-[10px] font-normal text-muted-foreground max-w-[220px] truncate py-1">
                  "{model.selectedContext.length > 60
                    ? `${model.selectedContext.slice(0, 60)}…`
                    : model.selectedContext}"
                </ContextMenuLabel>
                <ContextMenuSeparator />
                {(['What does this do?', 'Why is this here?', 'Is this correct?', 'Can this be simplified?'] as const)
                  .map((question) => (
                    <ContextMenuItem
                      key={question}
                      onSelect={() => actions.askAboutSelection(question)}
                      className="gap-2 text-xs"
                    >
                      <MessageSquare className="w-3.5 h-3.5" />
                      {question}
                    </ContextMenuItem>
                  ))}
              </>
            ) : (
              <ContextMenuItem disabled className="gap-2 text-xs opacity-60">
                <MessageSquare className="w-3.5 h-3.5" />
                Select text to chat about it
              </ContextMenuItem>
            )}
          </ContextMenuContent>
        </ContextMenu>

        {model.showChat && (
          <div
            data-testid="chat-panel"
            className="flex min-h-0 flex-col border-t border-border overflow-hidden"
            style={{ height: model.chatVisible ? model.chatHeight : 0 }}
          >
            {model.chatVisible && (
              <AccessibleResizer
                label="Resize chat panel"
                orientation="horizontal"
                value={model.chatHeight}
                min={chatHeightBounds(model.chatAvailableHeight).min}
                max={chatHeightBounds(model.chatAvailableHeight).max}
                onChange={actions.setChatHeight}
                onCommit={actions.commitChatHeight}
                onPointerDown={actions.startChatResize}
              />
            )}
            <ChatPane
              pr={pr}
              selectedContext={model.selectedContext}
              onContextUsed={actions.clearSelectedContext}
              pendingMessage={model.pendingChatMessage ?? undefined}
              onPendingMessageSent={actions.pendingMessageSent}
              contextSummary={model.contextSummary}
              onApplyVerifyAction={actions.applyVerifyAction}
            />
          </div>
        )}

        <ReviewFooter
          state={model.state}
          saving={model.saving}
          autosaveDirty={model.autosaveDirty}
          submitting={model.submitting}
          deleting={model.deleting}
          onSave={actions.save}
          onSubmit={actions.submit}
          onCancel={actions.cancel}
          onRegenerate={actions.generate}
          onDelete={actions.deleteDraft}
          onRunQualityCheck={actions.runQualityCheck}
          inlineCommentCount={commentCount}
          orphanCommentCount={orphanCount}
          summary={model.result?.summary ?? ''}
          qualityReport={model.qualityReport}
          diffUnavailable={model.diffUnavailable}
        />
      </div>
    </TooltipProvider>
  )
})
