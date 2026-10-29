FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
ENV DATA_DIR=/data
EXPOSE 8788
CMD ["gunicorn","-b","0.0.0.0:8788","--preload","--workers","2","--threads","4","--timeout","600","app:app"]
