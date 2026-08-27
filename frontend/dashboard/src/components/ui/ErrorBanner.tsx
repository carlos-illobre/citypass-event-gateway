import { Alert } from '@mantine/core'

type Props = {
  message:    string
  onDismiss?: () => void
}

export function ErrorBanner({ message, onDismiss }: Props) {
  return (
    <Alert color="red" variant="light" role="alert" withCloseButton={!!onDismiss} onClose={onDismiss}>
      {message}
    </Alert>
  )
}
