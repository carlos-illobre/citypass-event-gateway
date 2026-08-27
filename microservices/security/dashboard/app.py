import os
from datetime import date, timedelta
import pandas as pd
import requests
import streamlit as st

API=os.getenv("SECURITY_API_URL","http://security:8084").rstrip("/")+"/api/v1/security"
st.set_page_config(page_title="CityPass+ Security",layout="wide")
st.title("CityPass+ · Security")

def api_error(action,exc):
    response=getattr(exc,"response",None)
    status=f" (HTTP {response.status_code})" if response is not None else ""
    detail=""
    if response is not None:
        try: detail=response.json().get("detail","")
        except ValueError: detail=""
    st.error(f"No se pudo {action}{status}. {detail or 'Intentá nuevamente o revisá el estado de Security API.'}")
    st.stop()

def get(path,**params):
    try:
        response=requests.get(f"{API}{path}",params={k:v for k,v in params.items() if v not in (None,"")},timeout=10)
        response.raise_for_status();return response.json()
    except requests.RequestException as exc:api_error("consultar Security API",exc)
def patch(path,json=None):
    try:
        response=requests.patch(f"{API}{path}",json=json,timeout=10);response.raise_for_status();return response.json()
    except requests.RequestException as exc:api_error("guardar el cambio",exc)
def csv_bytes(path):
    try:
        response=requests.get(f"{API}{path}",timeout=10);response.raise_for_status();return response.content
    except requests.RequestException as exc:api_error("exportar el CSV",exc)
def frame(items): return pd.DataFrame(items)

status=get("/status")

labels=("Eventos totales","Últimas 24 h","Tópicos","Sources","Clusters","Alertas","HIGH","CRITICAL")
values=(status["events"],status["events_24h"],status["topics"],status["sources"],status["clusters"],status["alerts"],status["high"],status["critical"])
for column,label,value in zip(st.columns(8),labels,values):column.metric(label,value)

events_tab,clusters_tab,models_tab,alerts_tab,stats_tab=st.tabs(["Eventos","Clusters","Modelos","Alertas","Estadísticas"])
with events_tab:
    c1,c2,c3,c4=st.columns(4)
    topic=c1.text_input("Topic",key="event_topic");source=c2.text_input("Source",key="event_source")
    suspicious=c3.selectbox("Suspicious",["Todos","Sí","No"]);since=c4.date_input("Desde",date.today()-timedelta(days=7))
    suspicious_value=None if suspicious=="Todos" else suspicious=="Sí"
    data=get("/events",size=200,topic=topic,source=source,suspicious=suspicious_value,date_from=f"{since.isoformat()}T00:00:00Z")
    columns=["received_at","topic","source","event_type","cluster_number","distance_to_centroid","distance_threshold","risk_score","is_suspicious","event_id"]
    st.caption(f"{data['total']} eventos encontrados");st.dataframe(frame(data["items"]).reindex(columns=columns),use_container_width=True)

with clusters_tab:
    topic=st.text_input("Filtrar topic",key="cluster_topic");data=get("/clusters",size=500,topic=topic)
    df=frame(data["items"]);st.dataframe(df.reindex(columns=["topic","model_version","cluster_number","sample_count","distance_threshold","centroid","created_at"]),use_container_width=True)
    if not df.empty:
        chart=df.groupby(["topic","cluster_number"],as_index=False)["sample_count"].sum()
        st.bar_chart(chart,x="cluster_number",y="sample_count",color="topic")

with models_tab:
    topic=st.text_input("Filtrar topic",key="model_topic");data=get("/models",size=500,topic=topic)
    st.dataframe(frame(data["items"]).reindex(columns=["topic","model_version","sample_count","selected_k","silhouette_score","window_size","trained_at","status"]),use_container_width=True)

with alerts_tab:
    c1,c2,c3,c4=st.columns(4)
    severity=c1.selectbox("Severidad",["Todas","INFO","LOW","MEDIUM","HIGH","CRITICAL"])
    alert_type=c2.text_input("Tipo");topic=c3.text_input("Topic",key="alert_topic");source=c4.text_input("Source",key="alert_source")
    data=get("/alerts",size=200,severity=None if severity=="Todas" else severity,alert_type=alert_type,topic=topic,source=source)
    df=frame(data["items"]);st.dataframe(df.reindex(columns=["created_at","severity","risk_score","alert_type","topic","source","reason","publication_status","publication_attempts","acknowledged","review_status","alert_id"]),use_container_width=True)
    if not df.empty:
        selected=st.selectbox("Alerta para gestionar",df["alert_id"].tolist())
        a,b,c=st.columns(3)
        if a.button("Acknowledge"):patch(f"/alerts/{selected}/acknowledge");st.success("Alerta reconocida");st.rerun()
        if b.button("TRUE_POSITIVE"):patch(f"/alerts/{selected}/review",{"status":"TRUE_POSITIVE"});st.success("Revisión guardada");st.rerun()
        if c.button("FALSE_POSITIVE"):patch(f"/alerts/{selected}/review",{"status":"FALSE_POSITIVE"});st.success("Revisión guardada");st.rerun()

with stats_tab:
    events=frame(get("/events",size=200)["items"]);alerts=frame(get("/alerts",size=200)["items"])
    c1,c2=st.columns(2)
    if not events.empty:
        by_topic=events.groupby("topic",as_index=False).size().rename(columns={"size":"eventos"})
        c1.bar_chart(by_topic,x="topic",y="eventos")
        events["minute"]=pd.to_datetime(events["received_at"]).dt.floor("min")
        by_minute=events.groupby("minute",as_index=False).size().rename(columns={"size":"eventos"})
        st.line_chart(by_minute,x="minute",y="eventos")
    if not alerts.empty:
        by_severity=alerts.groupby("severity",as_index=False).size().rename(columns={"size":"alertas"})
        by_type=alerts.groupby("alert_type",as_index=False).size().rename(columns={"size":"alertas"})
        c2.bar_chart(by_severity,x="severity",y="alertas")
        st.bar_chart(by_type,x="alert_type",y="alertas")

st.sidebar.download_button("Exportar eventos CSV",csv_bytes("/export/events.csv"),"security-events.csv","text/csv")
st.sidebar.download_button("Exportar alertas CSV",csv_bytes("/export/alerts.csv"),"security-alerts.csv","text/csv")
