FROM python:3.12-alpine@sha256:e064671ca452259bab50e8e2c7bd896e39e9a343bb06ca25234adf6fe18d0fc7
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN pip install --no-cache-dir --root-user-action=ignore pg8000==1.31.5
COPY app/app.py /opt/triage/app.py
COPY cambios/ /opt/triage/cambios/
USER 1000:1000
HEALTHCHECK --interval=5s --timeout=3s --retries=3 --start-period=5s CMD wget -q -O /dev/null http://127.0.0.1:8080/salud || exit 1
CMD ["python", "/opt/triage/app.py"]
