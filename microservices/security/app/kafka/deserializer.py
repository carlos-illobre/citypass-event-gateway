import io, json, struct, requests, fastavro


class MalformedEvent(ValueError): pass


class AvroDeserializer:
    def __init__(self,url,timeout=5): self.url=url.rstrip('/'); self.timeout=timeout; self.cache={}
    def schema(self,schema_id):
        if schema_id not in self.cache:
            r=requests.get(f"{self.url}/schemas/ids/{schema_id}",timeout=self.timeout); r.raise_for_status()
            self.cache[schema_id]=fastavro.parse_schema(json.loads(r.json()["schema"]))
        return self.cache[schema_id]
    def deserialize(self,raw):
        if not raw or len(raw)<5: raise MalformedEvent("mensaje menor que el encabezado Confluent")
        if raw[0]!=0: raise MalformedEvent("magic byte inválido")
        sid=struct.unpack(">I",raw[1:5])[0]
        try: event=fastavro.schemaless_reader(io.BytesIO(raw[5:]),self.schema(sid))
        except Exception as exc: raise MalformedEvent(f"Avro/schema inválido: {exc}") from exc
        if not isinstance(event,dict) or not isinstance(event.get("metadata"),dict) or not isinstance(event.get("data"),dict): raise MalformedEvent("se requieren records metadata y data")
        return sid,event
