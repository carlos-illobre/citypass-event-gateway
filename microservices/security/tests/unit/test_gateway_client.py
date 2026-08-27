from unittest.mock import Mock
import pytest,requests
from app.gateway.client import EventGatewayClient,GatewayPublicationError

def response(status=200,body=None):
    item=Mock();item.status_code=status;item.json.return_value=body or {};item.raise_for_status.side_effect=None if status<400 else requests.HTTPError(str(status));return item
def client(session):return EventGatewayClient("http://gateway:8080","http://auth:8083","security","secret","com.citypass.security.AlertaDetectada",4,session)

def test_publish_uses_real_gateway_url_method_payload_token_and_timeout():
    session=Mock();session.post.side_effect=[response(200,{"access_token":"jwt","expires_in":300}),response(202,{"metadata":{"eventId":"gw-1"}})]
    payload={"alertId":"a1","signals":["NEW_STRUCTURE"]}
    assert client(session).publish(payload)=="gw-1"
    token_call,publish_call=session.post.call_args_list
    assert token_call.args[0]=="http://auth:8083/oauth/token" and token_call.kwargs["data"]=={"grant_type":"client_credentials"}
    assert publish_call.args[0]=="http://gateway:8080/api/v1/event-types/com.citypass.security.AlertaDetectada/events"
    assert publish_call.kwargs["json"]==payload and publish_call.kwargs["timeout"]==4
    assert publish_call.kwargs["headers"]["Authorization"]=="Bearer jwt"
def test_ensure_event_type_registers_exact_fields_when_missing():
    session=Mock();session.post.side_effect=[response(200,{"access_token":"jwt"}),response(201,{"fqn":"com.citypass.security.AlertaDetectada"})]
    session.get.return_value=response(404);fields=[{"name":"alertId","type":"string"}]
    client(session).ensure_event_type(fields)
    assert session.post.call_args_list[1].args[0]=="http://gateway:8080/api/v1/event-types"
    assert session.post.call_args_list[1].kwargs["json"]=={"name":"AlertaDetectada","fields":fields}
def test_http_error_and_connection_error_are_recoverable_errors():
    http=Mock();http.post.side_effect=[response(200,{"access_token":"jwt"}),response(500)]
    with pytest.raises(GatewayPublicationError):client(http).publish({"alertId":"a"})
    disconnected=Mock();disconnected.post.side_effect=requests.ConnectionError("offline")
    with pytest.raises(GatewayPublicationError,match="OAuth2"):client(disconnected).publish({"alertId":"a"})
