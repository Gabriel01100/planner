"""Deterministic bounded greedy planner. Priority 1 is highest; never mutates input."""
from datetime import date, datetime, timedelta
from calendar import monthrange
from zoneinfo import ZoneInfo
from .models import Activity, PlanRequest, PlanResult, Slot, Pending


def occurs(a: Activity, day: date) -> bool:
    if day < a.startDate or (a.until and day > a.until) or day in a.skippedDates:
        return False
    delta = (day - a.startDate).days
    if a.frequency == 'Única':
        return day == a.startDate
    if a.frequency == 'Diaria':
        return delta % a.interval == 0 and day.weekday() in a.days
    if a.frequency == 'Semanal':
        return (delta // 7) % a.interval == 0 and day.weekday() in a.days
    if a.frequency == 'Mensual':
        months = (day.year - a.startDate.year) * 12 + day.month - a.startDate.month
        return months % a.interval == 0 and day.day == min(a.startDate.day, monthrange(day.year, day.month)[1])
    return delta % a.interval == 0 and day.weekday() in a.days


def overlap(start, end, slot, gap=0):
    return start < slot.end + gap and end + gap > slot.start


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
        if s.end - s.start != a.duration:
            errors.append(f'Duración incorrecta: {a.title}')
        if s.date < a.startDate or (a.deadline and s.date > a.deadline):
            errors.append(f'Fecha fuera de límites: {a.title}')
        if a.frequency != 'Única' and not occurs(a, s.date):
            errors.append(f'Fecha fuera de recurrencia: {a.title}')
        if a.fixedStart is not None and (s.start != a.fixedStart or a.frequency == 'Única' and s.date != a.startDate):
            errors.append(f'Horario fijo incumplido: {a.title}')
        for windows in [state.availability, a.windows]:
            if not any(s.date.weekday() in w.days and w.start <= s.start and s.end <= w.end for w in windows):
                errors.append(f'Fuera de disponibilidad: {a.title}')
        if any(s.date.weekday() in w.days and s.start < w.end and s.end > w.start for w in a.excluded):
            errors.append(f'Horario excluido: {a.title}')
        if any(b.date == s.date and overlap(s.start, s.end, b, state.preferences.transition) for b in state.blocked):
            errors.append(f'Bloqueo: {a.title}')
        for other in schedule[:i]:
            if other.date == s.date and overlap(s.start, s.end, other, state.preferences.transition):
                errors.append(f'Superposición: {a.title} {s.date}')
    return sorted(set(errors))


def plan(req: PlanRequest):
    state = req.state
    by_id = {a.id: a for a in state.activities}
    last = req.start + timedelta(days=req.days - 1)
    now = req.now or datetime.now(ZoneInfo(state.preferences.timezone))
    if now.tzinfo is None:
        now = now.replace(tzinfo=ZoneInfo(state.preferences.timezone))
    now = now.astimezone(ZoneInfo(state.preferences.timezone))
    result = [s.model_copy(deep=True) for s in state.schedule
              if s.completed or by_id[s.activityId].status == 'Completada' or not req.start <= s.date <= last]
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
    occupied = {}
    for slot in result + state.blocked:
        occupied.setdefault(slot.date, []).append(slot)
    jobs.sort(key=lambda j: (j[0].fixedStart is None, j[0].priority, j[2], str(j[0].id), j[1]))
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
                stop = start + a.duration
                if stop > 1440 or datetime.combine(day, datetime.min.time(), tzinfo=now.tzinfo) + timedelta(minutes=start) < earliest:
                    continue
                if not all(any(day.weekday() in w.days and w.start <= start and stop <= w.end for w in windows)
                           for windows in [state.availability, a.windows]):
                    continue
                if any(day.weekday() in w.days and start < w.end and stop > w.start for w in a.excluded):
                    continue
                if any(s.date == day and overlap(start, stop, s, state.preferences.transition) for s in occupied.get(day, [])):
                    continue
                candidates.append(((offset, abs(start - a.preferredStart), start), Slot(activityId=a.id, date=day, start=start, end=stop)))
            if candidates:
                break
        if candidates:
            selected = min(candidates, key=lambda c: c[0])[1]
            result.append(selected)
            occupied.setdefault(selected.date, []).append(selected)
        else:
            pending.append(Pending(activityId=a.id, date=first, reason='Sin espacio compatible con duración, disponibilidad, anticipación y fecha límite.'))
    result.sort(key=lambda s: (s.date, s.start, str(s.activityId)))
    old = {(s.activityId, s.date, s.start, s.end) for s in state.schedule}
    changes = [f'{by_id[s.activityId].title}: {s.date} {s.start // 60:02}:{s.start % 60:02}; disponibilidad y prioridad {by_id[s.activityId].priority}'
               for s in result if (s.activityId, s.date, s.start, s.end) not in old]
    return PlanResult(schedule=result, pending=pending, conflicts=validate(state, result), changes=changes)
