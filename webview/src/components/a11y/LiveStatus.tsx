interface LiveStatusProps {
  message: string
  assertive?: boolean
}

export function LiveStatus({ message, assertive = false }: LiveStatusProps) {
  return (
    <div
      className="sr-only"
      role={assertive ? 'alert' : 'status'}
      aria-live={assertive ? 'assertive' : 'polite'}
      aria-atomic="true"
    >
      {message}
    </div>
  )
}
