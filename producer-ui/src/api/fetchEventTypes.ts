const URL = 'http://localhost:8080/api/v1/schemas'

export async function fetchEventTypes(token: string): Promise<Array<string>> {
    if (!token) {
        throw new Error('Token no proporcionado')
    }

    const response = await fetch(URL, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
        },
    })

    if (!response.ok) {
        if (response.status === 401) {
            throw new Error('Token inválido o expirado')
        }
        throw new Error(`HTTP error! status: ${response.status}`)
    }

    const json = await response.json()
    return json.eventTypes
}