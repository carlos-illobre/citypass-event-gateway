import { useContext, useEffect, useState } from "react"
import { AuthContext } from "../contexts/AuthContext"
import { fetchEventTypes } from "../api/fetchEventTypes"

export function EventTypeList() {
  const { token } = useContext(AuthContext)
  const [eventTypes, setEventTypes] = useState<Array<string>>([])

  useEffect(() => {
    fetchEventTypes(token)
    .then(response => {
      setEventTypes(response)
    })
    .catch(error => alert(error))
  }, [])

  return (
    <div>
      <h2>Event Schemas</h2>
      <ul>
        { eventTypes.map(item => <li key="item">{item}</li>) }
      </ul>
      <code>{token}</code>
    </div>
  )
}