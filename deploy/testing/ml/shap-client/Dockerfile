# shap-client (ML service of the risk prediction CDS services): the Python runtime only; the code and the models of
# the shap-client checkout are mounted at /app (docker-compose.yml, SHAP_CLIENT_DIR), so a restart picks up changes.
#   docker build -t stage/shap-client:testing ml/shap-client
FROM python:3.13-slim

# libgomp: OpenMP of xgboost / lightgbm
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/matplotlib
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
