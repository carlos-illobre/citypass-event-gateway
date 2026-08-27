import json
import logging
import threading
import time
from pathlib import Path

import requests

log=logging.getLogger(__name__)

class GatewayPublicationError(RuntimeError): pass

class EventGatewayClient:
    """Publica datos de negocio por el flujo OAuth2 → Event Gateway → Avro → Kafka."""
    def __init__(self,gateway_url,auth_url,client_id,client_secret,event_fqn,timeout=5,session=None):
        self.gateway_url=gateway_url.rstrip("/");self.auth_url=auth_url.rstrip("/")
        self.client_id=client_id;self.client_secret=client_secret;self.event_fqn=event_fqn
        self.timeout=timeout;self.session=session or requests.Session();self._token=None;self._expires_at=0;self._lock=threading.Lock()
    @property
    def publish_url(self):return f"{self.gateway_url}/api/v1/event-types/{self.event_fqn}/events"
    def _access_token(self):
        with self._lock:
            if self._token and time.monotonic()<self._expires_at-30:return self._token
            try:
                response=self.session.post(f"{self.auth_url}/oauth/token",auth=(self.client_id,self.client_secret),data={"grant_type":"client_credentials"},timeout=self.timeout)
                response.raise_for_status();body=response.json();self._token=body["access_token"];self._expires_at=time.monotonic()+int(body.get("expires_in",300));return self._token
            except (requests.RequestException,KeyError,ValueError) as exc:raise GatewayPublicationError(f"no se pudo obtener token OAuth2: {exc}") from exc
    def _headers(self):return {"Authorization":f"Bearer {self._access_token()}","Content-Type":"application/json"}
    def ensure_event_type(self,fields):
        headers=self._headers();url=f"{self.gateway_url}/api/v1/event-types/{self.event_fqn}"
        try:
            current=self.session.get(url,headers=headers,timeout=self.timeout)
            if current.status_code==200:return
            if current.status_code!=404:current.raise_for_status()
            name=self.event_fqn.rsplit(".",1)[1]
            created=self.session.post(f"{self.gateway_url}/api/v1/event-types",headers=headers,json={"name":name,"fields":fields},timeout=self.timeout)
            created.raise_for_status()
        except requests.RequestException as exc:raise GatewayPublicationError(f"no se pudo asegurar el event type: {exc}") from exc
    def publish(self,payload):
        try:
            response=self.session.post(self.publish_url,headers=self._headers(),json=payload,timeout=self.timeout)
            response.raise_for_status();body=response.json();return body.get("metadata",{}).get("eventId")
        except (requests.RequestException,ValueError) as exc:raise GatewayPublicationError(f"Event Gateway rechazó/no recibió la alerta: {exc}") from exc

def load_alert_fields():
    path=Path(__file__).parents[2]/"contracts"/"alert-event-fields.json"
    return json.loads(path.read_text(encoding="utf-8"))["fields"]
