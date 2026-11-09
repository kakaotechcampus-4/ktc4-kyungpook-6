FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY verification ./verification
COPY agent.json .
COPY cache-tts.sh .
CMD ["sh", "-c", "exec uvicorn verification.api:app --host 0.0.0.0 --port \"${BACKEND_PORT:-8000}\" --workers 1 --timeout-graceful-shutdown 180"]
