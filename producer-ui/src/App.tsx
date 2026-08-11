import { LoginForm } from './components/LoginForm'
import { EventTypeList } from './components/EventTypeList'
import { useContext } from 'react'
import { AuthContext } from './contexts/AuthContext'

function App() {

  const { token } = useContext(AuthContext)

  return (
    <>
      <section>
        {token ? <EventTypeList /> : <LoginForm />}
      </section>
    </>
  )
}

export default App
