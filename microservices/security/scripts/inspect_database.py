from sqlalchemy import text
from app.database.connection import engine
with engine.connect() as db:
    for table in ("security_events","event_features","model_runs","security_clusters","security_alerts"):
        print(table,db.scalar(text(f"SELECT count(*) FROM {table}")))
