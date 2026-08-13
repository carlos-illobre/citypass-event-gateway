import './ErrorBanner.css'

type Props = {
  message:   string
  onDismiss?: () => void
}

export function ErrorBanner({ message, onDismiss }: Props) {
  return (
    <p
      className={`error-banner${onDismiss ? ' error-banner--dismissable' : ''}`}
      onClick={onDismiss}
      role="alert"
    >
      {message}{onDismiss && ' — clic para cerrar'}
    </p>
  )
}
