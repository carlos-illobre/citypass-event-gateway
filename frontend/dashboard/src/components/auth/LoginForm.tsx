import { useContext, useState, type FormEvent } from 'react'
import { Box, Button, Center, PasswordInput, Paper, Stack, Text, TextInput, Title } from '@mantine/core'
import { AuthContext } from '@/contexts/auth-context'
import { auth } from '@/api/auth'
import { ErrorBanner } from '@/components/ui/ErrorBanner'
import { ThemeToggle } from '@/components/ui/ThemeToggle'

export function LoginForm() {
  const { setToken } = useContext(AuthContext)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    auth.login({ username, password })
      .then(({ token }) => setToken(token))
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false))
  }

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" radius="md" w={380} pos="relative">
        <Box pos="absolute" top="0.75rem" right="0.75rem"><ThemeToggle /></Box>
        <Title order={1} fz="xl" ta="center">Consola del bus</Title>
        <Text size="sm" c="dimmed" ta="center" mb="lg">CityPass+ · Event Driven Architecture</Text>

        <form onSubmit={handleSubmit}>
          <Stack gap="sm">
            <TextInput
              label="Usuario"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoComplete="username"
              required
            />

            <PasswordInput
              label="Contraseña"
              value={password}
              onChange={e => setPassword(e.target.value)}
              autoComplete="current-password"
              required
            />

            {error && <ErrorBanner message={error} />}

            <Button type="submit" loading={loading} fullWidth mt="xs">
              {loading ? 'Ingresando…' : 'Ingresar'}
            </Button>
          </Stack>
        </form>

        {/* El grupo 1 todavía no tiene cliente propio en el simulador de identidad: la lista va
            de `grupo2` a `grupo8`. Mientras tanto entramos con el namespace de analítica, que es
            el que mejor le calza a un tablero. */}
        <Text size="xs" c="dimmed" ta="center" mt="lg">
          El grupo 1 no tiene cliente propio todavía. Usá <code>grupo8</code> / <code>grupo8</code>.
        </Text>
      </Paper>
    </Center>
  )
}
