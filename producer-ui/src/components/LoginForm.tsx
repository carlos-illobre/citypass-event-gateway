import './LoginForm.css'
import { useContext, useState } from 'react'

import { fetchLogin } from '../api/auth/fetchLogin'
import { AuthContext } from '../contexts/AuthContext'


export function LoginForm() {
  const [formData, setFormData] = useState({
    username: 'grupo3',
    password: 'grupo3',
    message: ''
  })

  const { setToken } = useContext(AuthContext)

  const handleInputChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    setFormData({...formData, [event.target.name]: event.target.value})
  }

  const handleSubmit = (event: React.SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    fetchLogin(formData)
      .then((loginResult) => {
        if (loginResult.success) {
          setFormData({...formData, message: loginResult.token})
          setToken(loginResult.token)
        } else {
          setFormData({...formData, message: loginResult.error})
        }
      })
      .catch((error) => {
        alert(error)
        setFormData({...formData, message: error.error})
      })
  }

  return (
    <form onSubmit={handleSubmit} className="login-form">
      <label htmlFor="username">Username:</label>
      <input
        type="text" 
        id="username"
        name="username"
        value={formData.username}
        onChange={handleInputChange}
        required
        />
      <label htmlFor="password">Password:</label>
      <input
        type="password"
        id="password"
        name="password"
        value={formData.password}
        onChange={handleInputChange}
        required
        />
      <button type="submit">Login</button>
      <span className="error-message">{formData.message}</span>
    </form>
  )
}