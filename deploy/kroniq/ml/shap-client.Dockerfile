# shap-client for kroniq: the Python runtime of deploy/testing (ml/shap-client) with the code (app/) and the models
# (data/models, including the STAGE model package stage_v0_3_1) of the shap-client checkout built in. From stage-fhir-repository:
#   docker build -t nexus.srdc.com.tr:18445/stage/shap-client --build-context shap-client=<shap-client checkout> \
#     -f deploy/kroniq/ml/shap-client.Dockerfile deploy/testing/ml/shap-client
FROM python:3.13-slim

# libgomp: OpenMP of xgboost / lightgbm
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

WORKDIR /app
COPY --from=shap-client app /app/app
COPY --from=shap-client data/models /app/data/models

ENV PYTHONDONTWRITEBYTECODE=1 MPLCONFIGDIR=/tmp/matplotlib
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
