import { useContext, useState, type FormEvent } from 'react'
import {
  Center, Paper, Stack, TextInput, PasswordInput, Button, Title, Text, Image, Group,
} from '@mantine/core'
import { IconLogin } from '@tabler/icons-react'
import { AuthContext } from '@/contexts/auth-context'
import { auth } from '@/api/auth'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/**
 * Ingreso a la consola.
 *
 * Lo que este formulario llama usuario y contraseña viaja como `client_id`/`client_secret`
 * en un `client_credentials` de OAuth2: la identidad del sistema es el **grupo**, no una
 * persona. Es el mismo contrato que usa cualquier servicio que consume el bus.
 */
export function LoginView() {
  const { setToken } = useContext(AuthContext)
  const [username, setUsername] = useState('grupo1')
  const [password, setPassword] = useState('grupo1')
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState<unknown>(null)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setLoading(true); setError(null)
    auth.login({ username, password })
      .then(({ token }) => setToken(token))
      .catch(setError)
      .finally(() => setLoading(false))
  }

  return (
    <Center mih="100vh" bg="gray.0">
      <Paper withBorder shadow="sm" p="xl" w={380} radius="md">
        <Stack gap="lg">
          <Group justify="center" gap="xs">
            <Image src="/logo-citypass.svg" w={40} h={40} alt="" />
            <div>
              <Title order={3}>CityPass+</Title>
              <Text size="xs" c="dimmed" ta="center">Consola del bus de eventos</Text>
            </div>
          </Group>

          <form onSubmit={submit}>
            <Stack gap="sm">
              <TextInput label="Usuario" autoComplete="username" value={username}
                onChange={e => setUsername(e.target.value)} required />
              <PasswordInput label="Contraseña" autoComplete="current-password" value={password}
                onChange={e => setPassword(e.target.value)} required />
              {error !== null && (
                <ProblemAlert message={error instanceof Error ? error.message : String(error)} error={error} />
              )}
              <Button type="submit" fullWidth loading={loading} leftSection={<IconLogin size={16} />}>
                Ingresar
              </Button>
            </Stack>
          </form>

          <Text size="xs" c="dimmed" ta="center">
            La identidad del sistema es el grupo, no una persona: <code>grupo1</code> a{' '}
            <code>grupo8</code>, con la contraseña igual al usuario en el simulador de
            desarrollo.
          </Text>
        </Stack>
      </Paper>
    </Center>
  )
}
