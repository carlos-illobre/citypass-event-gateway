#!/usr/bin/env python3
"""E2E reproducible del análisis de eventos, usando los servicios reales del compose."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


GATEWAY = "http://localhost:8080"
SCHEMA_REGISTRY = "http://localhost:8081"
NORMAL_SAMPLES = 20
TIMEOUT_SECONDS = 90
EXPECTED_FEATURES = {
    "hour_of_day",
    "day_of_week",
    "topic_freq_1min",
    "topic_freq_5min",
    "payload_fields",
    "payload_size",
    "numeric_mean",
    "numeric_max",
}


def run(*arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(arguments, check=check, text=True, capture_output=True)


def request_json(
    url: str,
    *,
    token: str | None = None,
    method: str = "GET",
    body: dict | None = None,
) -> tuple[int, dict]:
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read().decode() or "{}")
        return error.code, payload


def wait_until(description: str, operation, predicate, timeout: int = TIMEOUT_SECONDS):
    deadline = time.monotonic() + timeout
    last_value = None
    while time.monotonic() < deadline:
        try:
            last_value = operation()
            if predicate(last_value):
                return last_value
        except (OSError, subprocess.SubprocessError, ValueError, json.JSONDecodeError):
            pass
        time.sleep(1)
    raise RuntimeError(f"timeout esperando {description}; último valor: {last_value!r}")


def detector_json(container: str, path: str) -> dict:
    script = (
        "import json,sys,urllib.request;"
        "print(urllib.request.urlopen('http://localhost:8084'+sys.argv[1],timeout=5)"
        ".read().decode())"
    )
    output = run("docker", "exec", container, "python", "-c", script, path).stdout
    return json.loads(output)


def publish(token: str, fqn: str, payload: dict) -> dict:
    status, response = request_json(
        f"{GATEWAY}/api/v1/event-types/{fqn}/events",
        token=token,
        method="POST",
        body=payload,
    )
    if status != 202:
        raise RuntimeError(f"publicación rechazada: HTTP {status}: {response}")
    return response


def validate_input_schema(token: str, fqn: str, schema_id: int) -> None:
    status, envelope = request_json(f"{GATEWAY}/api/v1/event-types/{fqn}", token=token)
    if status != 200:
        raise RuntimeError(f"no se pudo leer el schema de entrada: HTTP {status}")
    fields = {field["name"]: field["type"] for field in envelope["fields"]}
    if set(fields) != {"data", "metadata"}:
        raise RuntimeError(f"envelope inesperado: {set(fields)}")
    business_fields = {field["name"] for field in fields["data"]["fields"]}
    if business_fields != {"runId", "sequence", "value", "padding"}:
        raise RuntimeError(f"campos de negocio inesperados: {business_fields}")
    status, registry_response = request_json(f"{SCHEMA_REGISTRY}/schemas/ids/{schema_id}")
    if status != 200 or "schema" not in registry_response:
        raise RuntimeError(f"schema id {schema_id} ausente en Schema Registry")


def validate_anomaly(message: dict, output_topic: str, input_topic: str, event_id: str) -> None:
    required = {
        "eventId",
        "eventType",
        "timestamp",
        "source",
        "originalTopic",
        "originalEventId",
        "originalSource",
        "anomalyScore",
        "features",
    }
    if set(message) != required:
        raise RuntimeError(f"contrato de anomalía inesperado: {set(message)}")
    uuid.UUID(message["eventId"])
    if message["eventType"] != output_topic:
        raise RuntimeError("eventType no coincide con el topic E2E")
    if message["originalTopic"] != input_topic or message["originalEventId"] != event_id:
        raise RuntimeError("la anomalía no pertenece al evento controlado")
    if message["originalSource"] != "grupo3":
        raise RuntimeError("originalSource no conserva la identidad del productor")
    if not isinstance(message["anomalyScore"], (int, float)):
        raise RuntimeError("anomalyScore no es numérico")
    if set(message["features"]) != EXPECTED_FEATURES:
        raise RuntimeError("el vector publicado no contiene las ocho features")
    if not all(isinstance(value, (int, float)) for value in message["features"].values()):
        raise RuntimeError("las features publicadas no son numéricas")


def main() -> int:
    token = os.environ.get("CITYPASS_E2E_TOKEN")
    if not token:
        raise RuntimeError("CITYPASS_E2E_TOKEN es obligatorio")

    run_id = uuid.uuid4().hex[:12]
    event_name = f"E2EAnomaly{run_id}"
    fqn = f"com.citypass.movilidad.{event_name}"
    input_topic = fqn
    output_topic = f"sistema.anomalia.detectada.e2e.{run_id}"
    consumer_group = f"event-anomaly-analysis-e2e-{run_id}"
    container = f"event-anomaly-analysis-e2e-{run_id}"
    created = False

    print(f"RUN_ID={run_id}")
    print(f"INPUT_TOPIC={input_topic}")
    print(f"OUTPUT_TOPIC={output_topic}")
    try:
        status, registration = request_json(
            f"{GATEWAY}/api/v1/event-types",
            token=token,
            method="POST",
            body={
                "name": event_name,
                "fields": [
                    {"name": "runId", "type": "string"},
                    {"name": "sequence", "type": "long"},
                    {"name": "value", "type": "double"},
                    {"name": "padding", "type": "string"},
                ],
            },
        )
        if status != 201:
            raise RuntimeError(f"registro rechazado: HTTP {status}: {registration}")
        created = True
        validate_input_schema(token, fqn, registration["schemaId"])
        print(f"INPUT_SCHEMA_OK=schemaId:{registration['schemaId']}")

        command = [
            "docker", "compose", "--env-file", ".env.example", "run", "-d",
            "--name", container, "--no-deps",
            "-e", f"CONSUMER_GROUP_ID={consumer_group}",
            "-e", f"ANOMALIES_TOPIC={output_topic}",
            "-e", f"MIN_SAMPLES_TO_TRAIN={NORMAL_SAMPLES}",
            "-e", "RETRAIN_EVERY_N=1000",
            "-e", "CONTAMINATION=0.05",
            "-e", "MAX_ANOMALIES_HISTORY=100",
            "event-anomaly-analysis",
        ]
        run(*command)
        wait_until(
            "health del analizador",
            lambda: detector_json(container, "/health"),
            lambda health: health == {"status": "UP", "service": "event-anomaly-analysis"},
        )
        print("ANALYZER_HEALTHY=true")

        def assigned() -> bool:
            result = run(
                "docker", "exec", "kafka-authorizer", "kafka-consumer-groups",
                "--bootstrap-server", "localhost:29092", "--describe", "--group",
                consumer_group, check=False,
            )
            return input_topic in result.stdout

        try:
            wait_until("asignación del tópico al consumer group", assigned, bool)
        except RuntimeError as error:
            logs = run("docker", "logs", "--tail", "120", container, check=False)
            diagnostic = (logs.stdout + logs.stderr).strip()
            raise RuntimeError(f"{error}\nlogs del analizador:\n{diagnostic}") from error
        print("CONSUMER_ASSIGNED=true")

        normal_padding = "normal-" + ("x" * 32)
        for sequence in range(NORMAL_SAMPLES):
            normal_value = 100 + (sequence % 5)
            publish(token, fqn, {
                "runId": run_id,
                "sequence": normal_value,
                "value": float(normal_value),
                "padding": normal_padding,
            })

        status = wait_until(
            "entrenamiento del modelo",
            lambda: detector_json(container, "/api/v1/model/status"),
            lambda value: value["is_trained"] and value["total_events_seen"] >= NORMAL_SAMPLES,
        )
        print(f"MODEL_TRAINED=events:{status['total_events_seen']}")

        controlled = publish(token, fqn, {
            "runId": run_id,
            "sequence": -1_000_000,
            "value": -1_000_000.0,
            "padding": "outlier-" + ("Z" * 32),
        })
        controlled_event_id = controlled["metadata"]["eventId"]
        print(f"CONTROLLED_EVENT_ID={controlled_event_id}")

        anomaly = wait_until(
            "detección de la anomalía controlada",
            lambda: detector_json(container, "/api/v1/anomalies?limit=100"),
            lambda response: any(
                item.get("originalEventId") == controlled_event_id
                for item in response["anomalies"]
            ),
        )
        detected = next(
            item for item in anomaly["anomalies"]
            if item["originalEventId"] == controlled_event_id
        )
        validate_anomaly(detected, output_topic, input_topic, controlled_event_id)
        print(f"API_DETECTION_OK=score:{detected['anomalyScore']}")

        consumed = run(
            "docker", "exec", "kafka-authorizer", "kafka-console-consumer",
            "--bootstrap-server", "localhost:29092", "--topic", output_topic,
            "--from-beginning", "--timeout-ms", "10000", check=False,
        )
        messages = []
        for line in consumed.stdout.splitlines():
            try:
                messages.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        kafka_message = next(
            (item for item in messages if item.get("originalEventId") == controlled_event_id),
            None,
        )
        if kafka_message is None:
            raise RuntimeError("Kafka no devolvió la anomalía de esta ejecución")
        validate_anomaly(kafka_message, output_topic, input_topic, controlled_event_id)
        print(f"KAFKA_MESSAGE_OK=count:{len(messages)}")
        print("PASS: event-anomaly-analysis E2E")
        return 0
    finally:
        run("docker", "rm", "-f", container, check=False)
        if created:
            request_json(
                f"{GATEWAY}/api/v1/event-types/{fqn}", token=token, method="DELETE"
            )
        run(
            "docker", "exec", "kafka-authorizer", "kafka-topics",
            "--bootstrap-server", "localhost:29092", "--delete", "--topic", output_topic,
            check=False,
        )
        run(
            "docker", "exec", "kafka-authorizer", "kafka-consumer-groups",
            "--bootstrap-server", "localhost:29092", "--delete", "--group", consumer_group,
            check=False,
        )


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
