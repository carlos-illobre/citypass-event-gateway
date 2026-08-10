import os

KAFKA_BOOTSTRAP_SERVERS = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
SCHEMA_REGISTRY_URL = os.getenv("SCHEMA_REGISTRY_URL", "http://localhost:8081")
CONSUMER_GROUP_ID = os.getenv("CONSUMER_GROUP_ID", "anomaly-detector-group")
PORT = int(os.getenv("PORT", "8084"))

# Isolation Forest
# Cuántos eventos acumular antes del primer entrenamiento
MIN_SAMPLES_TO_TRAIN = int(os.getenv("MIN_SAMPLES_TO_TRAIN", "50"))
# Cada cuántos eventos nuevos re-entrenar el modelo
RETRAIN_EVERY_N = int(os.getenv("RETRAIN_EVERY_N", "100"))
# Fracción de puntos considerados anómalos (0.05 = 5%)
CONTAMINATION = float(os.getenv("CONTAMINATION", "0.05"))

# Tópico donde publicar anomalías detectadas
ANOMALIES_TOPIC = "sistema.anomalia.detectada"
# Cuántas anomalías recientes guardar en memoria
MAX_ANOMALIES_HISTORY = int(os.getenv("MAX_ANOMALIES_HISTORY", "200"))
