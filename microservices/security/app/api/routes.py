import csv
import io
from datetime import datetime, timedelta, timezone
from uuid import UUID

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from sqlalchemy import func, inspect, select

from app.database.connection import SessionLocal
from app.database.models import ModelRun, SecurityAlert, SecurityCluster, SecurityEvent

router = APIRouter(prefix="/api/v1/security")


class ReviewRequest(BaseModel):
    status: str


def dump(row):
    """Serializa sólo columnas ORM, usando el nombre físico del contrato JSON.

    Algunas columnas, como ``metadata``, tienen un atributo Python distinto
    (``metadata_json``) para no colisionar con atributos internos de SQLAlchemy.
    """
    return {
        attribute.columns[0].name: getattr(row, attribute.key)
        for attribute in inspect(row).mapper.column_attrs
    }
def page_result(db, query, count_query, page, size):
    total = db.scalar(count_query) or 0
    return {"page": page, "size": size, "total": total, "pages": (total + size - 1) // size,
            "items": [dump(row) for row in db.scalars(query.offset((page - 1) * size).limit(size)).all()]}


@router.get("/status")
def status():
    with SessionLocal() as db:
        return {"events": db.scalar(select(func.count()).select_from(SecurityEvent)) or 0,
            "events_24h": db.scalar(select(func.count()).select_from(SecurityEvent).where(SecurityEvent.received_at >= datetime.now(timezone.utc) - timedelta(days=1))) or 0,
            "alerts": db.scalar(select(func.count()).select_from(SecurityAlert)) or 0,
            "high": db.scalar(select(func.count()).select_from(SecurityAlert).where(SecurityAlert.severity == "HIGH")) or 0,
            "critical": db.scalar(select(func.count()).select_from(SecurityAlert).where(SecurityAlert.severity == "CRITICAL")) or 0,
            "topics": db.scalar(select(func.count(func.distinct(SecurityEvent.topic)))) or 0,
            "sources": db.scalar(select(func.count(func.distinct(SecurityEvent.source)))) or 0,
            "clusters": db.scalar(select(func.count()).select_from(SecurityCluster)) or 0}


@router.get("/events")
def events(page:int=Query(1,ge=1), size:int=Query(50,ge=1,le=200), topic:str|None=None,
           source:str|None=None, suspicious:bool|None=None, date_from:datetime|None=None, date_to:datetime|None=None):
    conditions=[]
    if topic: conditions.append(SecurityEvent.topic == topic)
    if source: conditions.append(SecurityEvent.source == source)
    if suspicious is not None: conditions.append(SecurityEvent.is_suspicious == suspicious)
    if date_from: conditions.append(SecurityEvent.received_at >= date_from)
    if date_to: conditions.append(SecurityEvent.received_at <= date_to)
    query=select(SecurityEvent).where(*conditions).order_by(SecurityEvent.received_at.desc())
    count=select(func.count()).select_from(SecurityEvent).where(*conditions)
    with SessionLocal() as db:
        result=page_result(db,query,count,page,size)
        alert_risks=dict(db.execute(select(SecurityAlert.event_id,SecurityAlert.risk_score).where(SecurityAlert.event_id.in_([x["id"] for x in result["items"]]))).all()) if result["items"] else {}
        for item in result["items"]: item["risk_score"]=alert_risks.get(item["id"],0)
        return result


@router.get("/events/{event_id}")
def event(event_id:UUID):
    with SessionLocal() as db:
        row=db.get(SecurityEvent,event_id)
        if not row: raise HTTPException(404,"Evento no encontrado")
        result=dump(row);result["features"]=row.features.vector if row.features else None
        result["alerts"]=[dump(a) for a in row.alerts];return result


@router.get("/alerts")
def alerts(page:int=Query(1,ge=1),size:int=Query(50,ge=1,le=200),severity:str|None=None,
           alert_type:str|None=None,topic:str|None=None,source:str|None=None,acknowledged:bool|None=None):
    conditions=[]
    if severity: conditions.append(SecurityAlert.severity == severity.upper())
    if alert_type: conditions.append(SecurityAlert.alert_type == alert_type)
    if topic: conditions.append(SecurityAlert.topic == topic)
    if source: conditions.append(SecurityAlert.source == source)
    if acknowledged is not None: conditions.append(SecurityAlert.acknowledged == acknowledged)
    query=select(SecurityAlert).where(*conditions).order_by(SecurityAlert.created_at.desc())
    count=select(func.count()).select_from(SecurityAlert).where(*conditions)
    with SessionLocal() as db:return page_result(db,query,count,page,size)


@router.get("/alerts/{alert_id}")
def alert(alert_id:str):
    with SessionLocal() as db:
        row=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id))
        if not row:raise HTTPException(404,"Alerta no encontrada")
        return dump(row)


@router.patch("/alerts/{alert_id}/acknowledge")
def acknowledge(alert_id:str):
    with SessionLocal.begin() as db:
        row=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id))
        if not row:raise HTTPException(404,"Alerta no encontrada")
        row.acknowledged=True;row.acknowledged_at=datetime.now(timezone.utc);return dump(row)


@router.patch("/alerts/{alert_id}/review")
def review(alert_id:str,body:ReviewRequest):
    if body.status not in {"PENDING","TRUE_POSITIVE","FALSE_POSITIVE"}:raise HTTPException(422,"status inválido")
    with SessionLocal.begin() as db:
        row=db.scalar(select(SecurityAlert).where(SecurityAlert.alert_id==alert_id))
        if not row:raise HTTPException(404,"Alerta no encontrada")
        row.review_status=body.status;return dump(row)


@router.get("/clusters")
def clusters(page:int=Query(1,ge=1),size:int=Query(100,ge=1,le=500),topic:str|None=None):
    conditions=[SecurityCluster.topic==topic] if topic else []
    with SessionLocal() as db:return page_result(db,select(SecurityCluster).where(*conditions).order_by(SecurityCluster.created_at.desc()),select(func.count()).select_from(SecurityCluster).where(*conditions),page,size)


@router.get("/models")
def models(page:int=Query(1,ge=1),size:int=Query(100,ge=1,le=500),topic:str|None=None):
    conditions=[ModelRun.topic==topic] if topic else []
    with SessionLocal() as db:return page_result(db,select(ModelRun).where(*conditions).order_by(ModelRun.trained_at.desc()),select(func.count()).select_from(ModelRun).where(*conditions),page,size)


def csv_response(filename,headers,rows):
    out=io.StringIO();writer=csv.writer(out);writer.writerow(headers);writer.writerows(rows)
    return StreamingResponse(iter([out.getvalue()]),media_type="text/csv",headers={"Content-Disposition":f"attachment; filename={filename}"})


@router.get("/export/events.csv")
def export_events():
    with SessionLocal() as db: rows=db.scalars(select(SecurityEvent).order_by(SecurityEvent.received_at.desc()).limit(10000)).all()
    return csv_response("security-events.csv",["received_at","event_id","topic","source","event_type","cluster","distance","threshold","suspicious"],([x.received_at,x.event_id,x.topic,x.source,x.event_type,x.cluster_number,x.distance_to_centroid,x.distance_threshold,x.is_suspicious] for x in rows))


@router.get("/export/alerts.csv")
def export_alerts():
    with SessionLocal() as db: rows=db.scalars(select(SecurityAlert).order_by(SecurityAlert.created_at.desc()).limit(10000)).all()
    return csv_response("security-alerts.csv",["created_at","alert_id","severity","risk","type","topic","source","reason","publication_status","publication_attempts","acknowledged","review_status"],([x.created_at,x.alert_id,x.severity,x.risk_score,x.alert_type,x.topic,x.source,x.reason,x.publication_status,x.publication_attempts,x.acknowledged,x.review_status] for x in rows))
