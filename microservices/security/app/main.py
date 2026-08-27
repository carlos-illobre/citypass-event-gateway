import logging
import threading
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from sqlalchemy import text
from app.api.routes import router
from app.core.config import get_settings
from app.database.connection import SessionLocal
from app.kafka.consumer import SecurityConsumer
from app.gateway.client import EventGatewayClient, load_alert_fields
from app.security.service import SecurityService

logging.basicConfig(level=logging.INFO,format="%(asctime)s %(levelname)s %(message)s")
cfg=get_settings(); service=None; consumer=None
retry_stop=threading.Event()

def retry_loop():
    while not retry_stop.wait(cfg.alert_retry_interval_seconds):
        try:service.publish_pending()
        except Exception:logging.exception("[SECURITY][GATEWAY] retry cycle failed")

@asynccontextmanager
async def lifespan(app):
    global service,consumer
    client=EventGatewayClient(cfg.event_gateway_url,cfg.auth_service_url,
        cfg.security_gateway_client_id,cfg.security_gateway_client_secret,
        cfg.security_alert_fqn,cfg.gateway_timeout_seconds)
    client.ensure_event_type(load_alert_fields())
    service=SecurityService(client)
    consumer=SecurityConsumer(cfg,service)
    service.rebuild_models()
    service.publish_pending()
    retry_stop.clear();threading.Thread(target=retry_loop,daemon=True,name="security-alert-retry").start()
    consumer.start()
    yield
    retry_stop.set();consumer.stop()

app=FastAPI(title="CityPass+ Security",version="1.0.0",lifespan=lifespan);app.include_router(router)
@app.get("/health")
def health():
    try:
        with SessionLocal() as db: db.execute(text("SELECT 1"))
        return {"status":"UP","service":"security","database":"UP","kafka_consumer":"UP" if consumer and consumer.connected else "STARTING"}
    except Exception as exc:raise HTTPException(503,{"status":"DOWN","service":"security","database":"DOWN","detail":str(exc)})
