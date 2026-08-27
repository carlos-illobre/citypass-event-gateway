import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@mantine/core/styles.css'
import './index.css'
import { MantineProvider } from '@mantine/core'
import { theme } from './theme'
import App from './App.tsx'
import { AuthProvider } from '@/contexts/AuthContext.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <AuthProvider>
        <App />
      </AuthProvider>
    </MantineProvider>
  </StrictMode>,
)
