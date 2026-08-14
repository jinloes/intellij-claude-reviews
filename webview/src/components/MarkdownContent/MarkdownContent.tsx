import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { cn } from '@/lib/utils'

const components: Components = {
  ul: ({ className, ...props }) => <ul className={cn('my-1 list-disc space-y-0.5 pl-5', className)} {...props} />,
  ol: ({ className, ...props }) => <ol className={cn('my-1 list-decimal space-y-0.5 pl-5', className)} {...props} />,
  li: ({ className, ...props }) => <li className={cn('my-0.5', className)} {...props} />,
}

export function MarkdownContent({
  children,
  className,
}: {
  children: string
  className?: string
}) {
  return (
    <div
      className={cn(
        'max-w-none text-sm [&_a]:text-primary [&_a]:underline [&_blockquote]:border-l-2 [&_blockquote]:pl-2',
        '[&_code]:rounded [&_code]:bg-muted [&_code]:px-1 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-xs',
        '[&_h1]:font-semibold [&_h2]:font-semibold [&_h3]:font-semibold [&_p]:my-1',
        '[&_pre]:my-1 [&_pre]:overflow-x-auto [&_pre]:rounded [&_pre]:bg-muted [&_pre]:p-2',
        className,
      )}
    >
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{children}</ReactMarkdown>
    </div>
  )
}

