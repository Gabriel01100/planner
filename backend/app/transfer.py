"""Offline, all-or-nothing transfer to an empty database. Never changes the source."""

from sqlalchemy import inspect, select, func
from .database import Base, make_engine, migrate, SchemaVersion
from .models import State, PlanResult
from .learning_models import LearningMemory


def transfer(source_url, target_url, apply=False):
    source, target = make_engine(source_url), make_engine(target_url)
    from pathlib import Path

    same_file = (
        source.dialect.name == target.dialect.name == "sqlite"
        and source.url.database
        and target.url.database
        and Path(source.url.database).resolve() == Path(target.url.database).resolve()
    )
    if source.url == target.url or same_file:
        raise ValueError("El origen y destino deben ser diferentes")
    try:
        # Reject existing records before even running target migrations/encryption.
        with target.connect() as destination:
            existing_target = set(inspect(destination).get_table_names())
            for table in Base.metadata.sorted_tables:
                if (
                    table.name != "schema_versions"
                    and table.name in existing_target
                    and destination.scalar(select(func.count()).select_from(table))
                ):
                    raise ValueError(
                        "El destino debe estar vacío; no se modifica ni migra una base con registros"
                    )
        migrate(target)
        counts = {}
        with source.connect() as origin, target.begin() as destination:
            if source.dialect.name == "sqlite":
                origin.exec_driver_sql("BEGIN")
            elif source.dialect.name == "postgresql":
                origin.exec_driver_sql(
                    "SET TRANSACTION ISOLATION LEVEL REPEATABLE READ"
                )
            existing = set(inspect(origin).get_table_names())
            if "documents" not in existing:
                raise ValueError("El origen no tiene un esquema de agenda compatible")
            if "schema_versions" in existing:
                versions = origin.execute(select(SchemaVersion.version)).scalars().all()
                if versions and max(versions) > 5:
                    raise ValueError("Origen de una versión más nueva")
            for table in Base.metadata.sorted_tables:
                if table.name == "schema_versions":
                    continue
                if destination.scalar(select(func.count()).select_from(table)):
                    raise ValueError(
                        "El destino debe estar vacío; no se reemplaza información"
                    )
            for table in Base.metadata.sorted_tables:
                if table.name == "schema_versions" or table.name not in existing:
                    continue
                rows = [dict(row) for row in origin.execute(select(table)).mappings()]
                for row in rows:
                    if table.name == "documents":
                        State.model_validate_json(row["payload"])
                    elif table.name == "learning_documents":
                        LearningMemory.model_validate_json(row["payload"])
                    elif table.name == "plan_proposals":
                        State.model_validate_json(row["previous"])
                        PlanResult.model_validate_json(row["payload"])
                counts[table.name] = len(rows)
                if apply and rows:
                    destination.execute(table.insert(), rows)
        return counts
    finally:
        source.dispose()
        target.dispose()


if __name__ == "__main__":
    import os
    import argparse
    import json

    parser = argparse.ArgumentParser(
        description="Transferencia offline a destino vacío; requiere backup y detener escrituras"
    )
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(
        json.dumps(
            {
                "applied": args.apply,
                "counts": transfer(
                    os.environ["SOURCE_DATABASE_URL"],
                    os.environ["TARGET_DATABASE_URL"],
                    args.apply,
                ),
            }
        )
    )
