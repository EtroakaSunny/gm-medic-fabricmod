FROM docker.io/library/python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY static ./static
COPY run.py .

RUN mkdir -p /app/data
RUN useradd -u 1000 -m gmmedic && chown -R gmmedic:gmmedic /app
USER gmmedic

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8765/',timeout=3).status==200 else 1)"

CMD ["python", "run.py"]
