import logging, threading
from datetime import datetime, timezone
from confluent_kafka import Consumer, KafkaError
from app.kafka.deserializer import AvroDeserializer, MalformedEvent

log=logging.getLogger(__name__)
BUSINESS_TOPIC_PATTERN = r"^com\.citypass\..+"
SECURITY_TOPIC_PREFIX = "com.citypass.security."

def should_consume_topic(topic: str) -> bool:
    import re
    return re.match(BUSINESS_TOPIC_PATTERN, topic) is not None and not topic.startswith(SECURITY_TOPIC_PREFIX)


class SecurityConsumer:
    def __init__(self,cfg,service):
        self.cfg=cfg; self.service=service; self.running=False; self.connected=False
        self.deserializer=AvroDeserializer(cfg.schema_registry_url,cfg.schema_registry_timeout_seconds)
        self.consumer=Consumer({"bootstrap.servers":cfg.kafka_bootstrap_servers,"group.id":cfg.security_consumer_group,"auto.offset.reset":"earliest","enable.auto.commit":False,"allow.auto.create.topics":False,"topic.metadata.refresh.interval.ms":5000})
    def start(self): self.running=True; threading.Thread(target=self.run,daemon=True,name="security-kafka").start()
    def stop(self): self.running=False
    def run(self):
        # librdkafka usa regex POSIX y no acepta negative lookahead. La exclusión
        # del namespace Security se aplica antes de deserializar o confirmar.
        self.consumer.subscribe([BUSINESS_TOPIC_PATTERN])
        self.connected=True; log.info("[SECURITY][KAFKA] connected group=%s pattern=%s excluded_prefix=%s",self.cfg.security_consumer_group,BUSINESS_TOPIC_PATTERN,SECURITY_TOPIC_PREFIX)
        try:
            while self.running:
                msg=self.consumer.poll(1)
                if msg is None: continue
                if msg.error():
                    if msg.error().code()!=KafkaError._PARTITION_EOF: log.error("[SECURITY][KAFKA] %s",msg.error())
                    continue
                if not should_consume_topic(msg.topic()):
                    log.debug("[SECURITY][KAFKA] ignored topic=%s",msg.topic())
                    self.consumer.commit(message=msg,asynchronous=False)
                    continue
                ts_type,ts_ms=msg.timestamp(); kafka_ts=datetime.fromtimestamp(ts_ms/1000,timezone.utc) if ts_ms and ts_ms>0 else None
                try:
                    sid,event=self.deserializer.deserialize(msg.value())
                    self.service.process(msg.topic(),msg.partition(),msg.offset(),kafka_ts,sid,event,len(msg.value() or b''))
                except MalformedEvent as exc:
                    log.warning("[SECURITY][ALERT][CRITICAL] malformed topic=%s offset=%s error=%s",msg.topic(),msg.offset(),exc)
                    self.service.malformed(msg.topic(),msg.partition(),msg.offset(),kafka_ts,msg.value(),exc)
                except Exception:
                    # Una falla de DB/análisis deja el offset sin confirmar para que Kafka
                    # reentregue. No se reclasifica como mensaje malformado.
                    log.exception("[SECURITY][EVENT] processing failed; offset not committed")
                    continue
                self.consumer.commit(message=msg,asynchronous=False)
        finally: self.connected=False; self.consumer.close()
