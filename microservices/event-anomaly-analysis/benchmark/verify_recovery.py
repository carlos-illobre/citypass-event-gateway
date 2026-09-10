"""Verifica que la proyección persista tras reiniciar los tres motores aislados."""

from run_persistence_benchmark import CouchProjectionStore, MongoProjectionStore, PostgreSqlProjectionStore


def main() -> None:
    event_id = "benchmark-00000000"
    for adapter in (MongoProjectionStore(), CouchProjectionStore(), PostgreSqlProjectionStore()):
        if adapter.by_event_id(event_id) is None:
            raise RuntimeError(f"{adapter.name} did not retain {event_id} after restart")
        print(f"{adapter.name}: recovery verified")


if __name__ == "__main__":
    main()
