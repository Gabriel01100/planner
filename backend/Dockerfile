# syntax=docker/dockerfile:1
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt .
RUN --mount=type=secret,id=build_ca if [ -f /run/secrets/build_ca ]; then PIP_CERT=/run/secrets/build_ca pip install --no-cache-dir -r requirements.txt; else pip install --no-cache-dir -r requirements.txt; fi && useradd --create-home app && mkdir -p /app/data && chown app:app /app/data
COPY --chown=app:app app ./app
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
