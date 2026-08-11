const URL = 'http://localhost:8083/auth/login'

type LoginCredentials = {
  username: string
  password: string
}

type LoginResult = 
  | { success: true; token: string }
  | { success: false; error: string }

export async function fetchLogin({ username, password }: LoginCredentials): Promise<LoginResult> {
  try {
    const response = await fetch(URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ username, password }),
    })

    const data = await response.json()

    if (!response.ok) {
      return { 
        success: false, 
        error: data.error || 'Error en el servidor' 
      }
    }

    return { 
      success: true, 
      token: data.token 
    }

  } catch (error) {
    return { 
      success: false, 
      error: 'No se pudo conectar al servidor' 
    }
  }
}