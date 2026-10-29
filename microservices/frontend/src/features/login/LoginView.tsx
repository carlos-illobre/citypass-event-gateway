import { useContext, useState, type FormEvent } from 'react'
import {
  Center, Paper, Stack, TextInput, PasswordInput, Button, Title, Text, Image, Group,
} from '@mantine/core'
import { IconLogin } from '@tabler/icons-react'
import { AuthContext } from '@/contexts/auth-context'
import { auth } from '@/api/auth'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/**
 * Ingreso a la consola, con el usuario y la contraseña de LDAP de cada persona.
 *
 * Qué puede tocar cada uno lo decide el `namespace` del token, que login-federado deriva del
 * módulo de la persona: alguien de Reclamos sólo opera sobre `com.citypass.reclamos.*`.
 */
export function LoginView() {
  const { setSession } = useContext(AuthContext)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState<unknown>(null)

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setLoading(true); setError(null)
    auth.login({ username, password })
      .then(setSession)
      .catch(setError)
      .finally(() => setLoading(false))
  }

  return (
    <Center mih="100vh" bg="var(--mantine-color-body)">
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
            Ingresá con tu usuario de CityPass (LDAP). Vas a operar sobre el namespace de tu
            módulo.
          </Text>
        </Stack>
      </Paper>
    </Center>
  )
}
