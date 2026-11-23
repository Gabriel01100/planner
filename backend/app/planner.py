"""Deterministic bounded greedy planner. Priority 1 is highest; never mutates input."""
from datetime import date, datetime, timedelta
from calendar import monthrange
from zoneinfo import ZoneInfo
from .models import Activity, PlanRequest, PlanResult, Slot, Pending


from .planning_rules import occurs, overlap, slot_errors


def validate(state, schedule):
    errors = []
    by_id = {a.id: a for a in state.activities}
    keys = set()
    for i, s in enumerate(schedule):
        key = (s.activityId, s.date)
        a = by_id.get(s.activityId)
        if key in keys:
            errors.append(f'Ocurrencia duplicada: {s.activityId} {s.date}')
        keys.add(key)
        if not a:
            errors.append(f'Actividad inexistente: {s.activityId}')
            continue
        if s.completed or a.status == 'Completada':
            continue  # historical records are preserved, even when preferences change
        others = [other for index, other in enumerate(schedule) if index != i]
        errors.extend(f'{error}: {a.title} {s.date}' for error in slot_errors(state, a, s, others))
    return sorted(set(errors))


def planning_jobs(req):
    state = req.state
    by_id = {a.id: a for a in state.activities}
    last = req.start + timedelta(days=req.days - 1)
    result = [s.model_copy(deep=True) for s in state.schedule
              if s.completed or by_id[s.activityId].status == 'Completada' or (not req.start <= s.date <= last and by_id[s.activityId].status != 'Cancelada')]
    retained = {(s.activityId, s.date) for s in result}
    jobs = []
    pending = []
    for a in state.activities:
        if a.status in ['Completada', 'Cancelada']:
            continue
        if a.frequency == 'Única':
            first = max(req.start, a.startDate)
            end = min(last, a.deadline or last)
            if a.fixedStart is not None:
                end = min(end, a.startDate)
            if first <= end:
                jobs.append((a, first, end))
            elif not any(s.activityId == a.id for s in result):
                pending.append(Pending(activityId=a.id, date=a.startDate, reason='Fecha límite o fecha fija fuera del horizonte disponible.'))
        else:
            for offset in range(req.days):
                day = req.start + timedelta(days=offset)
                if occurs(a, day) and (not a.deadline or day <= a.deadline) and (a.id, day) not in retained:
                    jobs.append((a, day, day))
    if len(jobs) > 10000:
        raise ValueError("Demasiadas ocurrencias: reducí el horizonte o las actividades (máximo 10000).")
    return result, jobs, pending


def plan(req: PlanRequest):
    state = req.state
    by_id = {a.id: a for a in state.activities}
    last = req.start + timedelta(days=req.days - 1)
    now = req.now or datetime.now(ZoneInfo(state.preferences.timezone))
    if now.tzinfo is None:
        now = now.replace(tzinfo=ZoneInfo(state.preferences.timezone))
    now = now.astimezone(ZoneInfo(state.preferences.timezone))
    result, jobs, pending = planning_jobs(req)
    occupied = {}
    for slot in result + state.blocked:
        occupied.setdefault(slot.date, []).append(slot)
    jobs.sort(key=lambda j: (j[0].fixedStart is None, j[0].priority, j[2], str(j[0].id), j[1]))
    # Topological order: dependencies are scheduled before dependent jobs.
    ordered, remaining = [], jobs[:]
    while remaining:
        ids = {job[0].id for job in remaining}
        ready = [job for job in remaining if not (set(job[0].dependencies) & ids)]
        if not ready:
            raise ValueError('Dependencias cíclicas')
        ordered.extend(ready)
        remaining = [job for job in remaining if job not in ready]
    jobs = ordered
    candidate_checks = 0
    for a, first, end in jobs:
        if a.frequency == 'Única' and any(s.activityId == a.id for s in result):
            continue
        candidates = []
        earliest = now + timedelta(minutes=a.leadMinutes)
        for offset in range((end - first).days + 1):
            day = first + timedelta(days=offset)
            if day in a.skippedDates or day.weekday() not in a.days:
                continue
            starts = [a.fixedStart] if a.fixedStart is not None else range(0, 1440 - a.duration + 1, 5)
            for start in starts:
                candidate_checks += 1
                if candidate_checks > 200000:
                    raise ValueError("La planificación excede 200000 candidatos; reducí el horizonte o las actividades")
                stop = start + a.duration
                if stop > 1440:
                    continue
                candidate = Slot(activityId=a.id, date=day, start=start, end=stop)
                if slot_errors(state, a, candidate, result, now=now):
                    continue
                candidates.append(((offset, abs(start - a.preferredStart), start), Slot(activityId=a.id, date=day, start=start, end=stop)))
            if candidates:
                break
        if candidates:
            selected = min(candidates, key=lambda c: c[0])[1]
            result.append(selected)
            occupied.setdefault(selected.date, []).append(selected)
        else:
            pending.append(Pending(activityId=a.id, date=first, reason='Sin espacio compatible con disponibilidad, duración, dependencias, carga diaria, buffers, anticipación o fecha límite. Revisá estas restricciones; no se acumula otra tarea.'))
    result.sort(key=lambda s: (s.date, s.start, str(s.activityId)))
    old = {(s.activityId, s.date, s.start, s.end) for s in state.schedule}
    changes = [f'{by_id[s.activityId].title}: {s.date} {s.start // 60:02}:{s.start % 60:02}; disponibilidad y prioridad {by_id[s.activityId].priority}'
               for s in result if (s.activityId, s.date, s.start, s.end) not in old]
    return PlanResult(schedule=result, pending=pending, conflicts=validate(state, result), changes=changes)
