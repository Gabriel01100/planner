"""One source of truth for candidate generation and independent validation."""
from calendar import monthrange
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo


def occurs(a, day):
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


def eligible_day(a, day):
    return (day >= a.startDate and (not a.deadline or day <= a.deadline)
            and (not a.until or day <= a.until) and day not in a.skippedDates and day.weekday() in a.days
            and (a.frequency == 'Única' or occurs(a, day))
            and (a.frequency != 'Única' or a.fixedStart is None or day == a.startDate))


def overlap(start, end, slot, gap=0):
    return start < slot.end + gap and end + gap > slot.start


def instant(day, minute, zone):
    return datetime.combine(day, datetime.min.time(), tzinfo=zone) + timedelta(minutes=minute)


def valid_wall_time(day, start, end, zone):
    begin, finish = instant(day, start, zone), instant(day, end, zone)
    # Reject missing wall times and intervals whose real duration differs across a DST jump.
    return (begin.astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None) == begin.replace(tzinfo=None)
            and finish.astimezone(timezone.utc).astimezone(zone).replace(tzinfo=None) == finish.replace(tzinfo=None)
            and (finish.astimezone(timezone.utc) - begin.astimezone(timezone.utc)).total_seconds() == (end-start)*60)


def slot_errors(state, a, slot, occupied, *, now=None, check_dependencies=True):
    errors = []
    if a.status == 'Cancelada': errors.append('Actividad cancelada')
    if not eligible_day(a, slot.date): errors.append('Fecha fuera de días permitidos, recurrencia o límites')
    if slot.end - slot.start != a.duration: errors.append('Duración incorrecta')
    if a.fixedStart is not None and slot.start != a.fixedStart: errors.append('Horario fijo incumplido')
    for windows in [state.availability, a.windows]:
        if not any(slot.date.weekday() in w.days and w.start <= slot.start and slot.end <= w.end for w in windows):
            errors.append('Fuera de disponibilidad')
    if any(slot.date.weekday() in w.days and slot.start < w.end and slot.end > w.start for w in a.excluded):
        errors.append('Horario excluido')
    if any(b.date == slot.date and overlap(slot.start, slot.end, b, state.preferences.transition) for b in state.blocked):
        errors.append('Bloqueo')
    if any(s.date == slot.date and overlap(slot.start, slot.end, s, state.preferences.transition) for s in occupied):
        errors.append('Superposición')
    zone = ZoneInfo(state.preferences.timezone)
    if not valid_wall_time(slot.date, slot.start, slot.end, zone): errors.append('Horario inexistente o cambio horario')
    if now is not None and instant(slot.date, slot.start, zone).astimezone(timezone.utc) < (now.astimezone(timezone.utc) + timedelta(minutes=a.leadMinutes)):
        errors.append('Anticipación insuficiente')
    if sum(s.end-s.start for s in occupied if s.date == slot.date) + a.duration > state.preferences.maxDailyMinutes:
        errors.append('Carga diaria máxima')
    if check_dependencies:
        by_id = {item.id: item for item in state.activities}
        for dependency in a.dependencies:
            if by_id[dependency].status != 'Completada' and not any(s.activityId == dependency and (s.date, s.end) <= (slot.date, slot.start) for s in occupied):
                errors.append('Dependencia pendiente')
    return errors
