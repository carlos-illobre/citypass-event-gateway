from pathlib import Path

def test_initial_migration_is_explicit_and_complete():
    source=(Path(__file__).parents[2]/"alembic"/"versions"/"0001_initial.py").read_text(encoding="utf-8")
    assert "create_all" not in source and "drop_all" not in source
    for table in ("security_events","event_features","security_clusters","model_runs","security_alerts"):
        assert f'op.create_table("{table}"' in source
    assert 'sa.UniqueConstraint("topic", "partition", "offset", name="uq_event_kafka_position")' in source
    assert "def downgrade()" in source
